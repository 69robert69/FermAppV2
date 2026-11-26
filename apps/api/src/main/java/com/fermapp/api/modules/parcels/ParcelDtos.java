package com.fermapp.api.modules.parcels;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fermapp.api.common.util.Text;
import com.fermapp.api.domain.model.CropCode;
import com.fermapp.api.domain.model.SoilStatus;
import com.fermapp.api.domain.tillage.Tillage.TillageSystem;
import com.fermapp.api.modules.common.Views.CropSeasonView;
import com.fermapp.api.modules.common.Views.ParcelView;
import com.fermapp.api.modules.common.Views.SatelliteView;
import com.fermapp.api.modules.common.Views.SoilProfileView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.openapitools.jackson.nullable.JsonNullable;

/** Requests and responses of the parcel endpoints. */
public final class ParcelDtos {

    private ParcelDtos() {
    }

    /** The fields of a new parcel (parcelFields in TypeScript). */
    public record ParcelDraft(
            @NotNull @Size(min = 1, max = 120) String name,
            @Size(min = 1, max = 40) String code,
            @NotNull @Valid PolygonInput geometry,
            @Size(max = 120) String locality,
            @Size(max = 60) String county,
            @Size(max = 2000) String notes,
            @DecimalMin("0") @DecimalMax("100000") Double irrigatedAreaHa,
            TillageSystem tillageSystem) {
        public ParcelDraft {
            name = Text.trim(name);
            code = Text.trim(code);
            locality = Text.trim(locality);
            county = Text.trim(county);
            notes = Text.trim(notes);
        }
    }

    /**
     * A partial update. Absent fields stay unchanged; for locality, county, notes and the
     * irrigated area, an explicit null clears the value.
     */
    public record ParcelUpdate(
            JsonNullable<@NotNull @Size(min = 1, max = 120) String> name,
            JsonNullable<@NotNull @Size(min = 1, max = 40) String> code,
            JsonNullable<@NotNull @Valid PolygonInput> geometry,
            JsonNullable<@Size(max = 120) String> locality,
            JsonNullable<@Size(max = 60) String> county,
            JsonNullable<@Size(max = 2000) String> notes,
            JsonNullable<@DecimalMin("0") @DecimalMax("100000") Double> irrigatedAreaHa) {
        public ParcelUpdate {
            name = trimmed(name);
            code = trimmed(code);
            geometry = geometry == null ? JsonNullable.undefined() : geometry;
            locality = trimmed(locality);
            county = trimmed(county);
            notes = trimmed(notes);
            irrigatedAreaHa = irrigatedAreaHa == null ? JsonNullable.undefined() : irrigatedAreaHa;
        }

        private static JsonNullable<String> trimmed(JsonNullable<String> value) {
            if (value == null || !value.isPresent()) {
                return JsonNullable.undefined();
            }
            return JsonNullable.of(Text.trim(value.get()));
        }
    }

    public record TillageRequest(@NotNull TillageSystem system, @Min(1990) @Max(2100) Integer year) {
    }

    // ------------------------------------------------------------------ responses

    public record SeasonBrief(CropCode crop, int year) {
    }

    public record SoilBrief(SoilStatus status, String label) {
    }

    public record SatelliteBrief(Instant date, double ndvi, int healthIndex, String healthClass) {
    }

    /** A parcel in the list, with the current crop, the soil status and the latest satellite reading. */
    public record ParcelListItem(@JsonUnwrapped ParcelView parcel, List<SeasonBrief> seasons, SoilBrief soilProfile,
                                 List<SatelliteBrief> satellite) {
    }

    /** Parcel details: the last 10 seasons, the soil profile and the latest satellite observation. */
    public record ParcelDetail(@JsonUnwrapped ParcelView parcel, List<CropSeasonView> seasons, SoilProfileView soilProfile,
                               List<SatelliteView> satellite) {
    }

    public record ImportPreview(String name, @JsonInclude(JsonInclude.Include.NON_NULL) String code, double areaHa) {
    }

    public record ImportResponse(boolean dryRun, List<ImportPreview> parcels, List<GeoIo.ImportProblem> problems, int created) {
    }

    public record ParcelId(UUID parcelId) {
    }
}
