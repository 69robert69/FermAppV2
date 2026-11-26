package com.fermapp.api.modules.satellite;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fermapp.api.common.error.Errors;
import com.fermapp.api.common.util.Concurrency;
import com.fermapp.api.common.util.Times;
import com.fermapp.api.common.web.ApiResponse;
import com.fermapp.api.domain.crops.CropRules;
import com.fermapp.api.domain.crops.CropRules.VegetationHealth;
import com.fermapp.api.integrations.SentinelClient;
import com.fermapp.api.integrations.SentinelClient.SentinelObservation;
import com.fermapp.api.modules.common.AccessGuard;
import com.fermapp.api.modules.common.Views.SatelliteView;
import com.fermapp.api.persistence.entity.Parcel;
import com.fermapp.api.persistence.entity.SatelliteObservation;
import com.fermapp.api.persistence.repository.SatelliteObservationRepository;
import com.fermapp.api.security.Access;
import com.fermapp.api.security.AccessGroup;
import com.fermapp.api.security.AuthContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Sentinel-2 history, refresh, comparison and index images (satellite.routes.ts). */
@RestController
@RequestMapping("/api/v1/parcels/{id}/satellite")
@Tag(name = "Satellite")
public class SatelliteController {

    private static final String ISO_DATE = "^\\d{4}-\\d{2}-\\d{2}$";
    private static final String ISO_DATE_MESSAGE = "The date must have the format YYYY-MM-DD.";

    public record ObservationWithHealth(@JsonUnwrapped SatelliteView observation, VegetationHealth health) {
        static ObservationWithHealth of(SatelliteObservation item) {
            return new ObservationWithHealth(SatelliteView.of(item), CropRules.vegetationHealth(item.getNdvi(), item.getNdre()));
        }
    }

    public record HistoryResponse(boolean configured, List<ObservationWithHealth> observations) {
    }

    public record RefreshRequest(@Min(1) @Max(365) Integer days) {
        public RefreshRequest {
            days = days == null ? 60 : days;
        }
    }

    public record RefreshResponse(int imported) {
    }

    public record Change(double ndvi, Double ndre, int healthIndex) {
    }

    public record CompareResponse(ObservationWithHealth before, ObservationWithHealth after, Change change) {
    }

    private final AccessGuard access;
    private final SatelliteObservationRepository observations;
    private final SentinelClient sentinel;

    public SatelliteController(AccessGuard access, SatelliteObservationRepository observations, SentinelClient sentinel) {
        this.access = access;
        this.observations = observations;
        this.sentinel = sentinel;
    }

    @GetMapping
    @Access(AccessGroup.ALL)
    @Operation(summary = "NDVI, NDRE and Health Index history")
    public ResponseEntity<ApiResponse<HistoryResponse>> history(AuthContext auth, @PathVariable UUID id,
                                                               @RequestParam(defaultValue = "60") @Min(1) @Max(200) int limit) {
        access.requireParcel(auth.farmId(), id);
        return ApiResponse.ok(new HistoryResponse(sentinel.isConfigured(),
                observations.findByParcelIdOrderByDateDesc(id, PageRequest.of(0, limit)).stream().map(ObservationWithHealth::of).toList()));
    }

    @PostMapping("/refresh")
    @Access(AccessGroup.PLANNERS)
    @Operation(summary = "Download Sentinel-2 observations for the given period")
    public ResponseEntity<ApiResponse<RefreshResponse>> refresh(AuthContext auth, @PathVariable UUID id,
                                                               @Valid @RequestBody(required = false) RefreshRequest body) {
        Parcel parcel = access.requireParcel(auth.farmId(), id);
        if (!sentinel.isConfigured()) {
            throw Errors.notConfigured("The satellite imagery service");
        }
        int days = body == null ? 60 : body.days();
        Instant to = Instant.now();
        Instant from = to.minusMillis(days * Times.DAY_MS);
        List<SentinelObservation> found = sentinel.fetchObservations(parcel.getGeometry(), Times.isoDay(from), Times.isoDay(to));
        Concurrency.mapLimit(found, found.size(), item -> {
            VegetationHealth health = CropRules.vegetationHealth(item.ndvi(), item.ndre());
            LocalDate date = LocalDate.parse(item.date());
            SatelliteObservation row = observations.findByParcelIdAndDate(parcel.getId(), date).orElseGet(() -> {
                SatelliteObservation created = new SatelliteObservation();
                created.setParcelId(parcel.getId());
                created.setDate(date);
                return created;
            });
            row.setNdvi(item.ndvi());
            row.setNdre(item.ndre());
            row.setCloudPct(item.cloudPct());
            row.setHealthIndex(health.healthIndex());
            row.setHealthClass(health.healthClass().name());
            observations.save(row);
        });
        return ApiResponse.ok(new RefreshResponse(found.size()));
    }

    @GetMapping("/compare")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Compare two observation dates")
    public ResponseEntity<ApiResponse<CompareResponse>> compare(AuthContext auth, @PathVariable UUID id,
                                                               @RequestParam @Pattern(regexp = ISO_DATE, message = ISO_DATE_MESSAGE) String from,
                                                               @RequestParam @Pattern(regexp = ISO_DATE, message = ISO_DATE_MESSAGE) String to) {
        access.requireParcel(auth.farmId(), id);
        SatelliteObservation before = observations.findByParcelIdAndDate(id, LocalDate.parse(from)).orElse(null);
        SatelliteObservation after = observations.findByParcelIdAndDate(id, LocalDate.parse(to)).orElse(null);
        if (before == null || after == null) {
            throw Errors.notFound("The observation for one of the dates");
        }
        return ApiResponse.ok(new CompareResponse(ObservationWithHealth.of(before), ObservationWithHealth.of(after),
                new Change(Math.round((after.getNdvi() - before.getNdvi()) * 1000) / 1000.0,
                        before.getNdre() != null && after.getNdre() != null
                                ? Math.round((after.getNdre() - before.getNdre()) * 1000) / 1000.0 : null,
                        after.getHealthIndex() - before.getHealthIndex())));
    }

    @GetMapping("/image")
    @Access(AccessGroup.ALL)
    @Operation(summary = "Coloured index image for a date")
    public ResponseEntity<byte[]> image(AuthContext auth, @PathVariable UUID id,
                                        @RequestParam @Pattern(regexp = ISO_DATE, message = ISO_DATE_MESSAGE) String date,
                                        @RequestParam(defaultValue = "NDVI") @Pattern(regexp = "NDVI|NDRE") String index) {
        Parcel parcel = access.requireParcel(auth.farmId(), id);
        byte[] image = sentinel.fetchIndexImage(parcel.getGeometry(), date, index);
        return ApiResponse.file(image, "image/png", index + "-" + date + ".png", true);
    }
}
