package com.fermapp.api.modules.crops;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fermapp.api.common.error.Errors;
import com.fermapp.api.common.util.Concurrency;
import com.fermapp.api.common.util.Text;
import com.fermapp.api.common.util.Times;
import com.fermapp.api.common.web.ApiResponse;
import com.fermapp.api.domain.crops.CropRules;
import com.fermapp.api.domain.crops.CropRules.ClimateFit;
import com.fermapp.api.domain.crops.CropRules.CropDevelopment;
import com.fermapp.api.domain.crops.CropRules.CropRecommendation;
import com.fermapp.api.domain.crops.CropRules.DailyTemperature;
import com.fermapp.api.domain.crops.CropRules.SoilFit;
import com.fermapp.api.domain.crops.Crops;
import com.fermapp.api.domain.crops.Crops.CropProfile;
import com.fermapp.api.domain.crops.Crops.SoilTexture;
import com.fermapp.api.domain.model.CropCode;
import com.fermapp.api.domain.model.SoilStatus;
import com.fermapp.api.domain.rotation.Rotation;
import com.fermapp.api.domain.rotation.Rotation.CropHistoryEntry;
import com.fermapp.api.domain.rotation.Rotation.RotationResult;
import com.fermapp.api.integrations.NasaPowerClient;
import com.fermapp.api.integrations.NasaPowerClient.ClimateNormals;
import com.fermapp.api.integrations.NasaPowerClient.ClimateYear;
import com.fermapp.api.integrations.NasaPowerClient.PowerDay;
import com.fermapp.api.modules.common.AccessGuard;
import com.fermapp.api.modules.common.Views.CropSeasonView;
import com.fermapp.api.modules.plans.PlanService;
import com.fermapp.api.persistence.entity.CropRecommendationRecord;
import com.fermapp.api.persistence.entity.CropSeason;
import com.fermapp.api.persistence.entity.Parcel;
import com.fermapp.api.persistence.entity.SoilProfile;
import com.fermapp.api.persistence.repository.CropRecommendationRepository;
import com.fermapp.api.persistence.repository.CropSeasonRepository;
import com.fermapp.api.persistence.repository.SoilProfileRepository;
import com.fermapp.api.security.Access;
import com.fermapp.api.security.AccessGroup;
import com.fermapp.api.security.AuthContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.openapitools.jackson.nullable.JsonNullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Crop catalog, crop history, rotation, recommendations, climate and crop development (crops.routes.ts). */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Crops and rotation")
public class CropController {

    private static final Logger log = LoggerFactory.getLogger(CropController.class);
    private static final int MAX_CLIMATE_YEARS = 10;

    public record SeasonCreate(@NotNull CropCode crop, @NotNull @Min(1990) @Max(2100) Integer year,
                               Instant sowingDate, Instant expectedHarvestDate, Instant harvestDate,
                               @DecimalMin("0") @DecimalMax("1000000") Double yieldT,
                               @DecimalMin("0") @DecimalMax("100") Double harvestMoisture,
                               @DecimalMin("0") @DecimalMax("500") Double expectedYieldTHa,
                               @DecimalMin("0") @DecimalMax("1000000") Double expectedPricePerT,
                               @DecimalMin("0") @DecimalMax("1000000") Double actualPricePerT,
                               @Size(max = 2000) String notes) {
        public SeasonCreate {
            notes = Text.trim(notes);
        }
    }

    public record SeasonUpdate(JsonNullable<@NotNull CropCode> crop,
                               JsonNullable<@NotNull @Min(1990) @Max(2100) Integer> year,
                               JsonNullable<Instant> sowingDate, JsonNullable<Instant> expectedHarvestDate,
                               JsonNullable<Instant> harvestDate,
                               JsonNullable<@DecimalMin("0") @DecimalMax("1000000") Double> yieldT,
                               JsonNullable<@DecimalMin("0") @DecimalMax("100") Double> harvestMoisture,
                               JsonNullable<@DecimalMin("0") @DecimalMax("500") Double> expectedYieldTHa,
                               JsonNullable<@DecimalMin("0") @DecimalMax("1000000") Double> expectedPricePerT,
                               JsonNullable<@DecimalMin("0") @DecimalMax("1000000") Double> actualPricePerT,
                               JsonNullable<@Size(max = 2000) String> notes) {
        public SeasonUpdate {
            crop = orUndefined(crop);
            year = orUndefined(year);
            sowingDate = orUndefined(sowingDate);
            expectedHarvestDate = orUndefined(expectedHarvestDate);
            harvestDate = orUndefined(harvestDate);
            yieldT = orUndefined(yieldT);
            harvestMoisture = orUndefined(harvestMoisture);
            expectedYieldTHa = orUndefined(expectedYieldTHa);
            expectedPricePerT = orUndefined(expectedPricePerT);
            actualPricePerT = orUndefined(actualPricePerT);
            notes = notes == null || !notes.isPresent() ? JsonNullable.undefined() : JsonNullable.of(Text.trim(notes.get()));
        }

        private static <T> JsonNullable<T> orUndefined(JsonNullable<T> value) {
            return value == null ? JsonNullable.undefined() : value;
        }
    }

    public record EvaluateRequest(@NotNull CropCode crop, @NotNull @Min(1990) @Max(2100) Integer year) {
    }

    public record RecommendationsResponse(int year, String soil, ClimateNormals climate, List<CropRecommendation> recommendations) {
    }

    public record ClimateResponse(String source, List<ClimateYear> years) {
    }

    public record DevelopmentResponse(String sowingDate, String dataUntil, @JsonUnwrapped CropDevelopment development) {
    }

    private final CropSeasonRepository seasons;
    private final SoilProfileRepository soilProfiles;
    private final CropRecommendationRepository recommendations;
    private final AccessGuard access;
    private final PlanService plans;
    private final NasaPowerClient nasa;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;

    public CropController(CropSeasonRepository seasons, SoilProfileRepository soilProfiles,
                          CropRecommendationRepository recommendations, AccessGuard access, PlanService plans,
                          NasaPowerClient nasa, TransactionTemplate tx, ObjectMapper mapper) {
        this.seasons = seasons;
        this.soilProfiles = soilProfiles;
        this.recommendations = recommendations;
        this.access = access;
        this.plans = plans;
        this.nasa = nasa;
        this.tx = tx;
        this.mapper = mapper;
    }

    private static BigDecimal decimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private List<CropHistoryEntry> parcelHistory(UUID parcelId) {
        return seasons.findByParcelIdOrderByYearDesc(parcelId).stream()
                .map(season -> new CropHistoryEntry(season.getCrop(), season.getYear())).toList();
    }

    private CropSeason requireSeason(UUID farmId, UUID id) {
        return seasons.findInFarm(id, farmId).orElseThrow(() -> Errors.notFound("Season"));
    }

    @GetMapping("/crops")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Catalog of predefined crops")
    public ResponseEntity<ApiResponse<List<CropProfile>>> catalog() {
        return ApiResponse.ok(Crops.catalog());
    }

    @GetMapping("/parcels/{id}/seasons")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Crop history for the last 10 years")
    public ResponseEntity<ApiResponse<List<CropSeasonView>>> history(AuthContext auth, @PathVariable UUID id) {
        access.requireParcel(auth.farmId(), id);
        return ApiResponse.ok(seasons.findByParcelIdAndYearGreaterThanEqualOrderByYearDesc(id, Times.currentLocalYear() - 10)
                .stream().map(CropSeasonView::of).toList());
    }

    @PostMapping("/parcels/{id}/seasons")
    @Access(AccessGroup.PLANNERS)
    @Operation(summary = "Add a crop to the parcel history")
    public ResponseEntity<ApiResponse<CropSeasonView>> addSeason(AuthContext auth, @PathVariable UUID id,
                                                                @Valid @RequestBody SeasonCreate body) {
        access.requireParcel(auth.farmId(), id);
        CropSeason season = new CropSeason();
        season.setParcelId(id);
        season.setCrop(body.crop());
        season.setYear(body.year());
        season.setSowingDate(body.sowingDate());
        season.setExpectedHarvestDate(body.expectedHarvestDate());
        season.setHarvestDate(body.harvestDate());
        season.setYieldT(body.yieldT());
        season.setHarvestMoisture(body.harvestMoisture());
        season.setExpectedYieldTHa(body.expectedYieldTHa());
        season.setExpectedPricePerT(decimal(body.expectedPricePerT()));
        season.setActualPricePerT(decimal(body.actualPricePerT()));
        season.setNotes(body.notes());
        CropSeason saved = seasons.saveAndFlush(season);
        plans.regeneratePlanSafely(auth.farmId(), id, saved.getYear(), false);
        return ApiResponse.created(CropSeasonView.of(seasons.findById(saved.getId()).orElseThrow(Errors::recordNotFound)));
    }

    @PatchMapping("/seasons/{id}")
    @Access(AccessGroup.PLANNERS)
    @Operation(summary = "Update a crop record")
    public ResponseEntity<ApiResponse<CropSeasonView>> updateSeason(AuthContext auth, @PathVariable UUID id,
                                                                   @Valid @RequestBody SeasonUpdate body) {
        CropSeason before = requireSeason(auth.farmId(), id);
        CropCode beforeCrop = before.getCrop();
        int beforeYear = before.getYear();
        Instant beforeSowing = before.getSowingDate();

        CropSeason season = tx.execute(status -> {
            CropSeason current = seasons.findById(id).orElseThrow(Errors::recordNotFound);
            body.crop().ifPresent(current::setCrop);
            body.year().ifPresent(current::setYear);
            body.sowingDate().ifPresent(current::setSowingDate);
            body.expectedHarvestDate().ifPresent(current::setExpectedHarvestDate);
            body.harvestDate().ifPresent(current::setHarvestDate);
            body.yieldT().ifPresent(current::setYieldT);
            body.harvestMoisture().ifPresent(current::setHarvestMoisture);
            body.expectedYieldTHa().ifPresent(current::setExpectedYieldTHa);
            body.expectedPricePerT().ifPresent(value -> current.setExpectedPricePerT(decimal(value)));
            body.actualPricePerT().ifPresent(value -> current.setActualPricePerT(decimal(value)));
            body.notes().ifPresent(current::setNotes);
            return seasons.saveAndFlush(current);
        });

        boolean replanned = season.getCrop() != beforeCrop || season.getYear() != beforeYear
                || !Objects.equals(season.getSowingDate(), beforeSowing);
        if (!replanned) {
            return ApiResponse.ok(CropSeasonView.of(season));
        }
        if (season.getYear() != beforeYear) {
            plans.removePlan(auth.farmId(), season.getParcelId(), beforeYear);
        }
        plans.regeneratePlanSafely(auth.farmId(), season.getParcelId(), season.getYear(), season.getCrop() != beforeCrop);
        return ApiResponse.ok(CropSeasonView.of(seasons.findById(id).orElseThrow(Errors::recordNotFound)));
    }

    @DeleteMapping("/seasons/{id}")
    @Access(AccessGroup.PLANNERS)
    @Operation(summary = "Delete a crop record")
    public ResponseEntity<Void> deleteSeason(AuthContext auth, @PathVariable UUID id) {
        CropSeason season = requireSeason(auth.farmId(), id);
        seasons.deleteById(id);
        plans.removePlan(auth.farmId(), season.getParcelId(), season.getYear());
        return ApiResponse.noContent();
    }

    @PostMapping("/parcels/{id}/rotation/evaluate")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Evaluate the proposed crop")
    public ResponseEntity<ApiResponse<RotationResult>> evaluate(AuthContext auth, @PathVariable UUID id,
                                                               @Valid @RequestBody EvaluateRequest body) {
        access.requireParcel(auth.farmId(), id);
        return ApiResponse.ok(Rotation.evaluateRotation(body.crop(), body.year(), parcelHistory(id)));
    }

    @GetMapping("/parcels/{id}/rotation/options")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Rank all crops by rotation score")
    public ResponseEntity<ApiResponse<List<RotationResult>>> options(AuthContext auth, @PathVariable UUID id,
                                                                    @RequestParam(required = false) @Min(1990) @Max(2100) Integer year) {
        access.requireParcel(auth.farmId(), id);
        int target = year != null ? year : Times.currentLocalYear() + 1;
        return ApiResponse.ok(Rotation.rankRotationOptions(target, parcelHistory(id), Crops.CROP_CODES));
    }

    private static SoilTexture texture(String value) {
        for (SoilTexture texture : SoilTexture.values()) {
            if (texture.name().equals(value)) {
                return texture;
            }
        }
        return null;
    }

    @GetMapping("/parcels/{id}/recommendations")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Recommend crops by soil, climate and rotation")
    public ResponseEntity<ApiResponse<RecommendationsResponse>> recommendations(AuthContext auth, @PathVariable UUID id,
                                                                               @RequestParam(required = false) @Min(1990) @Max(2100) Integer year) {
        Parcel parcel = access.requireParcel(auth.farmId(), id);
        Concurrency.Pair<SoilProfile, ClimateNormals> data = Concurrency.both(
                () -> soilProfiles.findByParcelId(parcel.getId()).orElse(null),
                () -> {
                    try {
                        return nasa.getClimateNormals(parcel.getCenterLat(), parcel.getCenterLng());
                    } catch (RuntimeException error) {
                        log.warn("Climate normals unavailable: {}", error.getMessage());
                        return null;
                    }
                });
        List<CropHistoryEntry> history = parcelHistory(parcel.getId());
        SoilProfile soil = data.first();
        ClimateNormals climate = data.second();
        SoilFit soilInput = soil != null && soil.getStatus() == SoilStatus.READY && soil.getPh() != null
                && soil.getTexture() != null && texture(soil.getTexture()) != null
                ? new SoilFit(soil.getPh(), texture(soil.getTexture())) : null;
        int targetYear = year != null ? year : Times.currentLocalYear() + 1;
        List<CropRecommendation> result = CropRules.recommendCrops(targetYear, history, soilInput,
                climate == null ? null : new ClimateFit(climate.seasonTempC(), climate.annualPrecipitationMm(), climate.gddBase10()),
                null);

        // The latest result is kept for history, without blocking the response on an error.
        try {
            tx.executeWithoutResult(status -> {
                recommendations.deleteForYear(parcel.getId(), targetYear);
                List<CropRecommendationRecord> records = new ArrayList<>();
                for (CropRecommendation item : result) {
                    CropRecommendationRecord record = new CropRecommendationRecord();
                    record.setParcelId(parcel.getId());
                    record.setYear(targetYear);
                    record.setCrop(item.crop());
                    record.setScore(item.score());
                    record.setReasons(mapper.valueToTree(item.reasons()));
                    records.add(record);
                }
                recommendations.saveAll(records);
            });
        } catch (RuntimeException error) {
            log.warn("Saving recommendations failed: {}", error.getMessage());
        }
        return ApiResponse.ok(new RecommendationsResponse(targetYear, soil == null ? null : soil.getLabel(), climate, result));
    }

    @GetMapping("/parcels/{id}/climate")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Compare agricultural years based on NASA POWER data")
    public ResponseEntity<ApiResponse<ClimateResponse>> climate(AuthContext auth, @PathVariable UUID id,
                                                               @RequestParam(defaultValue = "5") @Min(1) @Max(MAX_CLIMATE_YEARS) int years) {
        Parcel parcel = access.requireParcel(auth.farmId(), id);
        int lastYear = Times.utcYear(Instant.now()) - 1;
        List<PowerDay> days = nasa.getPowerDaily(parcel.getCenterLat(), parcel.getCenterLng(),
                (lastYear - years + 1) + "-01-01", lastYear + "-12-31");
        return ApiResponse.ok(new ClimateResponse("NASA POWER", NasaPowerClient.summarizeClimateYears(days)));
    }

    @GetMapping("/parcels/{id}/development")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Estimate the development of the current crop using growing degree-days (GDD)")
    public ResponseEntity<ApiResponse<DevelopmentResponse>> development(AuthContext auth, @PathVariable UUID id,
                                                                       @RequestParam(required = false) @Min(1990) @Max(2100) Integer year) {
        Parcel parcel = access.requireParcel(auth.farmId(), id);
        int target = year != null ? year : Times.currentLocalYear();
        CropSeason season = seasons.findByParcelIdAndYear(parcel.getId(), target)
                .orElseThrow(() -> Errors.notFound("The crop for " + target));
        if (season.getSowingDate() == null) {
            throw Errors.badRequest("Fill in the sowing date to estimate crop development.");
        }
        String start = Times.isoDay(season.getSowingDate());
        // NASA POWER data appears with a delay of a few days.
        Instant available = Instant.now().minusMillis(5 * Times.DAY_MS);
        Instant stop = season.getHarvestDate() != null && season.getHarvestDate().isBefore(available) ? season.getHarvestDate() : available;
        String end = Times.isoDay(stop);
        if (start.compareTo(end) > 0) {
            throw Errors.badRequest("There is no weather data after the sowing date yet.");
        }
        List<PowerDay> days = nasa.getPowerDaily(parcel.getCenterLat(), parcel.getCenterLng(), start, end);
        CropDevelopment development = CropRules.cropDevelopment(season.getCrop(),
                days.stream().map(day -> new DailyTemperature(day.date(), day.tMaxC(), day.tMinC())).toList());
        return ApiResponse.ok(new DevelopmentResponse(start, end, development));
    }
}
