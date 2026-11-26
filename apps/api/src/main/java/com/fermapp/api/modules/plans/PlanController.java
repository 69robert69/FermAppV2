package com.fermapp.api.modules.plans;

import com.fasterxml.jackson.databind.JsonNode;
import com.fermapp.api.common.error.Errors;
import com.fermapp.api.common.util.Text;
import com.fermapp.api.common.util.Times;
import com.fermapp.api.common.web.ApiResponse;
import com.fermapp.api.domain.geo.Geo;
import com.fermapp.api.domain.geo.Geo.GeoMetrics;
import com.fermapp.api.domain.model.CropCode;
import com.fermapp.api.domain.model.Role;
import com.fermapp.api.domain.plan.ParcelPlanner;
import com.fermapp.api.domain.plan.ParcelPlanner.ParcelPlanData;
import com.fermapp.api.domain.plan.ParcelPlanner.ParcelPlanInput;
import com.fermapp.api.domain.rotation.Rotation.CropHistoryEntry;
import com.fermapp.api.modules.common.AccessGuard;
import com.fermapp.api.modules.common.Views.ParcelView;
import com.fermapp.api.modules.parcels.ParcelDtos.ParcelDraft;
import com.fermapp.api.modules.parcels.ParcelService;
import com.fermapp.api.modules.parcels.PolygonInput;
import com.fermapp.api.modules.plans.PlanService.SiteData;
import com.fermapp.api.modules.soil.SoilService;
import com.fermapp.api.persistence.entity.CropSeason;
import com.fermapp.api.persistence.entity.Parcel;
import com.fermapp.api.persistence.entity.ParcelPlan;
import com.fermapp.api.persistence.repository.CropSeasonRepository;
import com.fermapp.api.persistence.repository.ParcelPlanRepository;
import com.fermapp.api.persistence.repository.ParcelRepository;
import com.fermapp.api.security.Access;
import com.fermapp.api.security.AccessGroup;
import com.fermapp.api.security.AuthContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The parcel wizard and the technology plan (plan.routes.ts). */
@RestController
@RequestMapping("/api/v1/parcels")
@Tag(name = "Technology plan")
public class PlanController {

    private static final String LAST_CROP = "WHEAT|BARLEY|CORN|SUNFLOWER|RAPESEED|SOY|ALFALFA|OAT|POTATO|BEET|VEGETABLES|OTHER";

    public record AnalyzeRequest(@NotNull @Pattern(regexp = LAST_CROP) String lastCrop, @Size(max = 60) String lastCropName,
                                 @NotNull CropCode plannedCrop, UUID parcelId, @Size(max = 120) String name,
                                 @Valid PolygonInput geometry) {
        public AnalyzeRequest {
            lastCropName = Text.trim(lastCropName);
            name = Text.trim(name);
        }

        @AssertTrue(message = "Send either the existing parcel or the outline of the new parcel.")
        public boolean isExactlyOneTarget() {
            return (parcelId != null) != (geometry != null);
        }
    }

    public record CompleteRequest(@NotNull @Pattern(regexp = LAST_CROP) String lastCrop, @Size(max = 60) String lastCropName,
                                  @NotNull CropCode plannedCrop, UUID parcelId, @Valid ParcelDraft draft) {
        public CompleteRequest {
            lastCropName = Text.trim(lastCropName);
        }

        @AssertTrue(message = "Send either the existing parcel or the data of the new parcel.")
        public boolean isExactlyOneTarget() {
            return (parcelId != null) != (draft != null);
        }
    }

    public record CompleteResponse(ParcelView parcel, JsonNode plan) {
    }

    public record PlanVersion(UUID id, int year, CropCode crop, boolean active, String trigger, Instant createdAt, JsonNode data) {
    }

    public record PlanHistory(PlanVersion active, List<PlanVersion> history) {
    }

    private final PlanService planService;
    private final ParcelService parcelService;
    private final SoilService soil;
    private final AccessGuard access;
    private final ParcelRepository parcels;
    private final CropSeasonRepository seasons;
    private final ParcelPlanRepository plans;
    private final TransactionTemplate tx;

    public PlanController(PlanService planService, ParcelService parcelService, SoilService soil, AccessGuard access,
                          ParcelRepository parcels, CropSeasonRepository seasons, ParcelPlanRepository plans,
                          TransactionTemplate tx) {
        this.planService = planService;
        this.parcelService = parcelService;
        this.soil = soil;
        this.access = access;
        this.parcels = parcels;
        this.seasons = seasons;
        this.plans = plans;
        this.tx = tx;
    }

    private ParcelPlanData buildPlanFor(AuthContext auth, UUID parcelId, String name, PolygonInput geometry, String lastCrop,
                                        String lastCropName, CropCode plannedCrop) {
        Parcel existing = parcelId != null ? access.requireActiveParcel(auth.farmId(), parcelId) : null;
        GeoMetrics metrics = existing != null ? null : Geo.computeGeoMetrics(geometry.toPolygon());
        double lat = existing != null ? existing.getCenterLat() : metrics.center().lat();
        double lng = existing != null ? existing.getCenterLng() : metrics.center().lng();
        Instant now = Times.now();

        SiteData site = planService.loadSiteData(lat, lng, existing != null ? existing.getId() : null);
        List<CropHistoryEntry> history = existing != null
                ? planService.parcelCropHistory(existing.getId(), ParcelPlanner.nextSeasonYear(now))
                : List.of();
        return ParcelPlanner.buildParcelPlan(new ParcelPlanInput(plannedCrop,
                existing != null ? existing.getName() : (name != null ? name : "New parcel"),
                existing != null ? existing.getAreaHa() : metrics.areaHa(),
                PlanService.historyWithLastCrop(history, lastCrop, now),
                PlanService.lastCropLabel(lastCrop, lastCropName), site.soil(), site.climate(), now, null, null));
    }

    @PostMapping("/wizard/analyze")
    @Access(AccessGroup.PLANNERS)
    @Operation(summary = "Analyse the parcel and generate the plan without saving anything")
    public ResponseEntity<ApiResponse<JsonNode>> analyze(AuthContext auth, @Valid @RequestBody AnalyzeRequest body) {
        ParcelPlanData plan = buildPlanFor(auth, body.parcelId(), body.name(), body.geometry(), body.lastCrop(),
                body.lastCropName(), body.plannedCrop());
        return ApiResponse.ok(PlanService.planForRole(planService.toJson(plan), auth.role()));
    }

    @PostMapping("/wizard/complete")
    @Access(AccessGroup.PLANNERS)
    @Operation(summary = "Save the parcel, crops, technology plan and tasks")
    public ResponseEntity<ApiResponse<CompleteResponse>> complete(AuthContext auth, @Valid @RequestBody CompleteRequest body) {
        if (body.draft() != null && auth.role() == Role.AGRONOMIST) {
            throw Errors.forbidden("Only managers can create new parcels.");
        }
        ParcelPlanData plan = buildPlanFor(auth, body.parcelId(), body.draft() != null ? body.draft().name() : null,
                body.draft() != null ? body.draft().geometry() : null, body.lastCrop(), body.lastCropName(), body.plannedCrop());
        int lastYear = ParcelPlanner.nextSeasonYear(Times.now()) - 1;
        if (plan.year() <= lastYear) {
            throw Errors.badRequest("The plan year must be after the last crop.");
        }

        Parcel parcel = tx.execute(status -> {
            Parcel target;
            if (body.parcelId() != null) {
                target = parcels.findById(body.parcelId()).orElseThrow(Errors::recordNotFound);
            } else {
                target = parcelService.newParcel(auth.farmId(), body.draft());
            }
            target.setLastCrop(body.lastCrop());
            target = parcels.saveAndFlush(target);

            if (!ParcelPlanner.OTHER_CROP.equals(body.lastCrop())) {
                CropSeason previous = seasons.findByParcelIdAndYear(target.getId(), lastYear).orElseGet(CropSeason::new);
                previous.setParcelId(target.getId());
                previous.setYear(lastYear);
                previous.setCrop(CropCode.valueOf(body.lastCrop()));
                seasons.save(previous);
            }
            CropSeason planned = seasons.findByParcelIdAndYear(target.getId(), plan.year()).orElseGet(CropSeason::new);
            planned.setParcelId(target.getId());
            planned.setYear(plan.year());
            planned.setCrop(plan.crop());
            planned.setExpectedHarvestDate(Instant.parse(plan.harvestDate()));
            planned.setExpectedYieldTHa(plan.report().expectedYieldTHa());
            seasons.saveAndFlush(planned);
            planService.savePlan(auth.farmId(), target.getId(), plan, "WIZARD");
            return target;
        });

        if (body.draft() != null) {
            soil.loadInBackground(parcel);
        }
        planService.announcePlan(auth.farmId(), parcel.getId());
        Parcel fresh = parcels.findById(parcel.getId()).orElseThrow();
        return ApiResponse.created(new CompleteResponse(ParcelView.of(fresh),
                PlanService.planForRole(planService.toJson(plan), auth.role())));
    }

    @GetMapping("/{id}/plan")
    @Access(AccessGroup.ALL)
    @Operation(summary = "The active plan and saved reports of the parcel")
    public ResponseEntity<ApiResponse<PlanHistory>> plan(AuthContext auth, @PathVariable UUID id) {
        access.requireParcel(auth.farmId(), id);
        List<PlanVersion> history = plans.findByParcelIdOrderByCreatedAtDesc(id, PageRequest.of(0, 20)).stream()
                .map((ParcelPlan item) -> new PlanVersion(item.getId(), item.getYear(), item.getCrop(), item.isActive(),
                        item.getTrigger(), item.getCreatedAt(), PlanService.planForRole(item.getData(), auth.role())))
                .toList();
        PlanVersion active = history.stream().filter(PlanVersion::active).findFirst().orElse(null);
        return ApiResponse.ok(new PlanHistory(active, history));
    }
}
