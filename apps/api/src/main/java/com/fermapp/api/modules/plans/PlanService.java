package com.fermapp.api.modules.plans;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fermapp.api.common.cache.CacheService;
import com.fermapp.api.common.util.Concurrency;
import com.fermapp.api.common.util.Js;
import com.fermapp.api.common.util.Times;
import com.fermapp.api.domain.crops.Crops;
import com.fermapp.api.domain.crops.Crops.SoilTexture;
import com.fermapp.api.domain.model.CropCode;
import com.fermapp.api.domain.model.Role;
import com.fermapp.api.domain.model.SoilStatus;
import com.fermapp.api.domain.model.TaskPriority;
import com.fermapp.api.domain.model.TaskType;
import com.fermapp.api.domain.plan.ParcelPlanner;
import com.fermapp.api.domain.plan.ParcelPlanner.ParcelPlanData;
import com.fermapp.api.domain.plan.ParcelPlanner.ParcelPlanInput;
import com.fermapp.api.domain.plan.ParcelPlanner.PlanClimate;
import com.fermapp.api.domain.plan.ParcelPlanner.PlanSoil;
import com.fermapp.api.domain.plan.ParcelPlanner.PlanStep;
import com.fermapp.api.domain.plan.ParcelPlanner.RankedCrop;
import com.fermapp.api.domain.rotation.Rotation.CropHistoryEntry;
import com.fermapp.api.domain.soil.SoilRules;
import com.fermapp.api.domain.soil.SoilRules.SoilClassification;
import com.fermapp.api.domain.soil.SoilRules.SoilInput;
import com.fermapp.api.integrations.NasaPowerClient;
import com.fermapp.api.integrations.NasaPowerClient.ClimateNormals;
import com.fermapp.api.integrations.SoilGridsClient;
import com.fermapp.api.persistence.entity.CropRecommendationRecord;
import com.fermapp.api.persistence.entity.CropSeason;
import com.fermapp.api.persistence.entity.Parcel;
import com.fermapp.api.persistence.entity.ParcelPlan;
import com.fermapp.api.persistence.entity.SoilProfile;
import com.fermapp.api.persistence.entity.Task;
import com.fermapp.api.persistence.repository.CropRecommendationRepository;
import com.fermapp.api.persistence.repository.CropSeasonRepository;
import com.fermapp.api.persistence.repository.ParcelPlanRepository;
import com.fermapp.api.persistence.repository.ParcelRepository;
import com.fermapp.api.persistence.repository.SoilProfileRepository;
import com.fermapp.api.persistence.repository.TaskRepository;
import com.fermapp.api.realtime.RealtimeEvent;
import com.fermapp.api.realtime.RealtimeHub;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Technology plans: site data, saving, regeneration and role filtering (plan.service.ts). */
@Service
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);
    private static final long SOIL_CACHE_SECONDS = 86_400;

    /** Soil and climate data for the location; an unavailable source is null and does not stop planning. */
    public record SiteData(PlanSoil soil, PlanClimate climate) {
    }

    private record CachedSoil(double ph, SoilTexture texture, String label) {
    }

    private final SoilProfileRepository soilProfiles;
    private final CropSeasonRepository seasons;
    private final ParcelRepository parcels;
    private final ParcelPlanRepository plans;
    private final TaskRepository tasks;
    private final CropRecommendationRepository recommendations;
    private final SoilGridsClient soilGrids;
    private final NasaPowerClient nasa;
    private final CacheService cache;
    private final RealtimeHub realtime;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;

    public PlanService(SoilProfileRepository soilProfiles, CropSeasonRepository seasons, ParcelRepository parcels,
                       ParcelPlanRepository plans, TaskRepository tasks, CropRecommendationRepository recommendations,
                       SoilGridsClient soilGrids, NasaPowerClient nasa, CacheService cache, RealtimeHub realtime,
                       TransactionTemplate tx, ObjectMapper mapper) {
        this.soilProfiles = soilProfiles;
        this.seasons = seasons;
        this.parcels = parcels;
        this.plans = plans;
        this.tasks = tasks;
        this.recommendations = recommendations;
        this.soilGrids = soilGrids;
        this.nasa = nasa;
        this.cache = cache;
        this.realtime = realtime;
        this.tx = tx;
        this.mapper = mapper;
    }

    private static SoilTexture texture(String value) {
        if (value == null) {
            return null;
        }
        for (SoilTexture texture : SoilTexture.values()) {
            if (texture.name().equals(value)) {
                return texture;
            }
        }
        return null;
    }

    public SiteData loadSiteData(double lat, double lng, UUID parcelId) {
        Optional<SoilProfile> profile = parcelId == null ? Optional.empty() : soilProfiles.findByParcelId(parcelId);
        PlanSoil saved = profile
                .filter(p -> p.getStatus() == SoilStatus.READY && p.getPh() != null && p.getTexture() != null && texture(p.getTexture()) != null)
                .map(p -> new PlanSoil(p.getPh(), texture(p.getTexture()), p.getLabel()))
                .orElse(null);

        Concurrency.Pair<PlanSoil, PlanClimate> site = Concurrency.both(
                () -> saved != null ? saved : soilFromSoilGrids(lat, lng),
                () -> climate(lat, lng));
        return new SiteData(site.first(), site.second());
    }

    private PlanSoil soilFromSoilGrids(double lat, double lng) {
        try {
            CachedSoil soil = cache.cached("plan-soil:" + Js.toFixed(lat, 4) + ":" + Js.toFixed(lng, 4), SOIL_CACHE_SECONDS,
                    CachedSoil.class, () -> {
                        SoilInput values = soilGrids.fetch(lat, lng);
                        if (values == null) {
                            throw new IllegalStateException("SoilGrids has no data for this location.");
                        }
                        SoilClassification soilClass = SoilRules.classifySoil(values);
                        return new CachedSoil(values.ph(), soilClass.texture(), soilClass.label());
                    });
            return new PlanSoil(soil.ph(), soil.texture(), soil.label());
        } catch (RuntimeException error) {
            log.warn("Soil unavailable for plan: {}", error.getMessage());
            return null;
        }
    }

    private PlanClimate climate(double lat, double lng) {
        try {
            ClimateNormals normals = nasa.getClimateNormals(lat, lng);
            return normals == null ? null
                    : new PlanClimate(normals.seasonTempC(), normals.annualPrecipitationMm(), normals.gddBase10());
        } catch (RuntimeException error) {
            log.warn("Climate unavailable for plan: {}", error.getMessage());
            return null;
        }
    }

    /** The parcel history, completed with the last crop declared in the wizard. */
    public static List<CropHistoryEntry> historyWithLastCrop(List<CropHistoryEntry> history, String lastCrop, Instant now) {
        if (ParcelPlanner.OTHER_CROP.equals(lastCrop)) {
            return history;
        }
        int year = ParcelPlanner.nextSeasonYear(now) - 1;
        List<CropHistoryEntry> result = new ArrayList<>(history.stream().filter(entry -> entry.year() != year).toList());
        result.add(new CropHistoryEntry(CropCode.valueOf(lastCrop), year));
        return result;
    }

    public static String lastCropLabel(String lastCrop, String name) {
        if (ParcelPlanner.OTHER_CROP.equals(lastCrop)) {
            String trimmed = name == null ? "" : name.trim();
            return trimmed.isEmpty() ? "Other crop" : trimmed;
        }
        return Crops.name(CropCode.valueOf(lastCrop));
    }

    public List<CropHistoryEntry> parcelCropHistory(UUID parcelId, Integer beforeYear) {
        List<CropSeason> list = beforeYear == null
                ? seasons.findByParcelIdOrderByYearDesc(parcelId)
                : seasons.findByParcelIdAndYearLessThanOrderByYearDesc(parcelId, beforeYear);
        return list.stream().map(season -> new CropHistoryEntry(season.getCrop(), season.getYear())).toList();
    }

    public JsonNode toJson(ParcelPlanData plan) {
        return mapper.valueToTree(plan);
    }

    /**
     * Replaces the year's plan and its not-started tasks. Work that was started or completed is kept.
     * Must run inside a transaction.
     */
    public ParcelPlan savePlan(UUID farmId, UUID parcelId, ParcelPlanData plan, String trigger) {
        Set<String> keptSteps = new HashSet<>(tasks.findKeptStepKeys(parcelId, plan.year(), plan.crop()));
        tasks.deleteNotStartedPlanTasks(parcelId, plan.year());
        plans.deactivate(parcelId, plan.year());

        ParcelPlan saved = new ParcelPlan();
        saved.setParcelId(parcelId);
        saved.setYear(plan.year());
        saved.setCrop(plan.crop());
        saved.setTrigger(trigger);
        saved.setActive(true);
        saved.setData(toJson(plan));
        plans.saveAndFlush(saved);

        List<Task> created = new ArrayList<>();
        for (PlanStep step : plan.steps()) {
            if (step.past() || keptSteps.contains(step.key())) {
                continue;
            }
            Task task = new Task();
            task.setFarmId(farmId);
            task.setParcelId(parcelId);
            task.setPlanId(saved.getId());
            task.setPlanStepKey(step.key());
            task.setType(TaskType.valueOf(step.taskType()));
            task.setTitle(step.title() + ": " + plan.cropName());
            task.setDescription("Generated automatically from the " + plan.year() + " technology plan.");
            task.setPriority(TaskPriority.valueOf(step.priority()));
            task.setDueDate(Instant.parse(step.date()));
            created.add(task);
        }
        tasks.saveAll(created);

        recommendations.deleteForYear(parcelId, plan.year());
        List<CropRecommendationRecord> records = new ArrayList<>();
        for (RankedCrop item : plan.recommendations()) {
            CropRecommendationRecord record = new CropRecommendationRecord();
            record.setParcelId(parcelId);
            record.setYear(plan.year());
            record.setCrop(item.crop());
            record.setScore(item.score());
            List<String> reasons = new ArrayList<>();
            reasons.add(item.reason());
            reasons.addAll(item.reasons());
            record.setReasons(mapper.valueToTree(reasons));
            records.add(record);
        }
        recommendations.saveAll(records);

        Parcel parcel = parcels.findById(parcelId).orElseThrow();
        parcel.setPlannedCrop(plan.crop());
        parcels.save(parcel);
        return saved;
    }

    public void announcePlan(UUID farmId, UUID parcelId) {
        Map<String, UUID> payload = Map.of("parcelId", parcelId);
        realtime.emitToFarm(farmId, RealtimeEvent.TASK_UPDATED, payload);
        realtime.emitToFarm(farmId, RealtimeEvent.PARCEL_UPDATED, payload);
    }

    /**
     * Recalculates everything that depends on the crop: rotation, calendar, water, fertilization,
     * monitoring, tasks and recommendations. Completed years remain unchanged.
     */
    public ParcelPlan regeneratePlan(UUID farmId, UUID parcelId, int year, boolean cropChanged, Instant now) {
        if (year < Times.utcYear(now)) {
            return null;
        }
        Optional<Parcel> parcel = parcels.findById(parcelId);
        Optional<CropSeason> season = seasons.findByParcelIdAndYear(parcelId, year);
        if (parcel.isEmpty() || season.isEmpty()) {
            return null;
        }
        List<CropHistoryEntry> history = parcelCropHistory(parcelId, year);
        SiteData site = loadSiteData(parcel.get().getCenterLat(), parcel.get().getCenterLng(), parcelId);
        ParcelPlanData plan = ParcelPlanner.buildParcelPlan(new ParcelPlanInput(season.get().getCrop(), parcel.get().getName(),
                parcel.get().getAreaHa(), history, null, site.soil(), site.climate(), now, year, season.get().getSowingDate()));

        ParcelPlan saved = tx.execute(status -> {
            ParcelPlan result = savePlan(farmId, parcelId, plan, "CROP_CHANGED");
            CropSeason current = seasons.findById(season.get().getId()).orElseThrow();
            // Values entered by the user remain, as long as the crop is the same.
            if (cropChanged || current.getExpectedHarvestDate() == null) {
                current.setExpectedHarvestDate(Instant.parse(plan.harvestDate()));
            }
            if (cropChanged || current.getExpectedYieldTHa() == null) {
                current.setExpectedYieldTHa(plan.report().expectedYieldTHa());
            }
            seasons.save(current);
            return result;
        });
        announcePlan(farmId, parcelId);
        return saved;
    }

    /** Saving a crop succeeds even when recalculating the plan fails. */
    public void regeneratePlanSafely(UUID farmId, UUID parcelId, int year, boolean cropChanged) {
        try {
            regeneratePlan(farmId, parcelId, year, cropChanged, Times.now());
        } catch (RuntimeException error) {
            log.error("Plan recalculation failed", error);
        }
        realtime.emitToFarm(farmId, RealtimeEvent.PARCEL_UPDATED, Map.of("parcelId", parcelId));
    }

    /** Deleting the crop also removes its not-started tasks. */
    public void removePlan(UUID farmId, UUID parcelId, int year) {
        tx.executeWithoutResult(status -> {
            tasks.deleteNotStartedPlanTasks(parcelId, year);
            plans.deactivate(parcelId, year);
        });
        announcePlan(farmId, parcelId);
    }

    /** Financial data is not sent to operators. */
    public static JsonNode planForRole(JsonNode data, Role role) {
        if (role != Role.OPERATOR || data == null || !data.has("report")) {
            return data;
        }
        ObjectNode copy = data.deepCopy();
        ObjectNode report = (ObjectNode) copy.get("report");
        report.remove(List.of("expectedProfitEurHa", "priceEurPerT", "costEurHa"));
        return copy;
    }
}
