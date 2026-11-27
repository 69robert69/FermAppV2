package com.fermapp.api.modules.parcels

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.fasterxml.jackson.annotation.JsonUnwrapped
import com.fermapp.api.common.util.Text
import com.fermapp.api.domain.model.CropCode
import com.fermapp.api.domain.model.SoilStatus
import com.fermapp.api.domain.tillage.Tillage.TillageSystem
import com.fermapp.api.modules.common.Views.CropSeasonView
import com.fermapp.api.modules.common.Views.ParcelView
import com.fermapp.api.modules.common.Views.SatelliteView
import com.fermapp.api.modules.common.Views.SoilProfileView
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID
import org.openapitools.jackson.nullable.JsonNullable

/** Requests and responses of the parcel endpoints. */
object ParcelDtos {

    /** The fields of a new parcel (parcelFields in TypeScript). */
    // The order of the Java record, which the OpenAPI document keeps.
    @JsonPropertyOrder("name", "code", "geometry", "locality", "county", "notes", "irrigatedAreaHa", "tillageSystem")
    class ParcelDraft(
        name: String?,
        code: String?,
        @field:NotNull @field:Valid val geometry: PolygonInput?,
        locality: String?,
        county: String?,
        notes: String?,
        @field:DecimalMin("0") @field:DecimalMax("100000") val irrigatedAreaHa: Double?,
        val tillageSystem: TillageSystem?,
    ) {
        @field:NotNull @field:Size(min = 1, max = 120)
        val name: String? = Text.trim(name)

        @field:Size(min = 1, max = 40)
        val code: String? = Text.trim(code)

        @field:Size(max = 120)
        val locality: String? = Text.trim(locality)

        @field:Size(max = 60)
        val county: String? = Text.trim(county)

        @field:Size(max = 2000)
        val notes: String? = Text.trim(notes)
    }

    /**
     * A partial update. Absent fields stay unchanged; for locality, county, notes and the
     * irrigated area, an explicit null clears the value.
     */
    class ParcelUpdate(
        name: JsonNullable<String>?,
        code: JsonNullable<String>?,
        geometry: JsonNullable<PolygonInput>?,
        locality: JsonNullable<String>?,
        county: JsonNullable<String>?,
        notes: JsonNullable<String>?,
        irrigatedAreaHa: JsonNullable<Double>?,
    ) {
        val name: JsonNullable<@NotNull @Size(min = 1, max = 120) String> = trimmed(name)
        val code: JsonNullable<@NotNull @Size(min = 1, max = 40) String> = trimmed(code)
        val geometry: JsonNullable<@NotNull @Valid PolygonInput> = geometry ?: JsonNullable.undefined()
        val locality: JsonNullable<@Size(max = 120) String> = trimmed(locality)
        val county: JsonNullable<@Size(max = 60) String> = trimmed(county)
        val notes: JsonNullable<@Size(max = 2000) String> = trimmed(notes)
        val irrigatedAreaHa: JsonNullable<@DecimalMin("0") @DecimalMax("100000") Double> = irrigatedAreaHa ?: JsonNullable.undefined()

        private companion object {
            fun trimmed(value: JsonNullable<String>?): JsonNullable<String> =
                if (value == null || !value.isPresent) JsonNullable.undefined() else JsonNullable.of(Text.trim(value.get()))
        }
    }

    class TillageRequest(
        @field:NotNull val system: TillageSystem?,
        @field:Min(1990) @field:Max(2100) val year: Int?,
    )

    // ------------------------------------------------------------------ responses

    @JvmRecord
    data class SeasonBrief(val crop: CropCode?, val year: Int)

    @JvmRecord
    data class SoilBrief(val status: SoilStatus?, val label: String?)

    @JvmRecord
    data class SatelliteBrief(val date: Instant?, val ndvi: Double, val healthIndex: Int, val healthClass: String?)

    /** A parcel in the list, with the current crop, the soil status and the latest satellite reading. */
    @JvmRecord
    data class ParcelListItem(
        @get:JsonUnwrapped val parcel: ParcelView,
        val seasons: List<SeasonBrief>,
        val soilProfile: SoilBrief?,
        val satellite: List<SatelliteBrief>,
    )

    /** Parcel details: the last 10 seasons, the soil profile and the latest satellite observation. */
    @JvmRecord
    data class ParcelDetail(
        @get:JsonUnwrapped val parcel: ParcelView,
        val seasons: List<CropSeasonView>,
        val soilProfile: SoilProfileView?,
        val satellite: List<SatelliteView>,
    )

    @JvmRecord
    data class ImportPreview(
        val name: String?,
        @get:JsonInclude(JsonInclude.Include.NON_NULL) val code: String?,
        val areaHa: Double,
    )

    @JvmRecord
    data class ImportResponse(
        val dryRun: Boolean,
        val parcels: List<ImportPreview>,
        val problems: List<GeoIo.ImportProblem>,
        val created: Int,
    )

    @JvmRecord
    data class ParcelId(val parcelId: UUID)
}
