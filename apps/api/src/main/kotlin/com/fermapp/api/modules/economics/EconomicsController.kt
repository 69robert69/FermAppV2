package com.fermapp.api.modules.economics

import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.fasterxml.jackson.databind.ObjectMapper
import com.fermapp.api.common.util.Text
import com.fermapp.api.common.web.ApiResponse
import com.fermapp.api.domain.economics.EconomicModels.ChemicalKind
import com.fermapp.api.domain.economics.EconomicModels.EconomicSettings
import com.fermapp.api.domain.economics.EconomicModels.EconomicSheet
import com.fermapp.api.domain.economics.Economics
import com.fermapp.api.modules.common.AccessGuard
import com.fermapp.api.modules.economics.EconomicsService.FarmEconomicsResult
import com.fermapp.api.security.Access
import com.fermapp.api.security.AccessGroup
import com.fermapp.api.security.AuthContext
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.util.UUID
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Economic settings, parcel sheets and the farm's real cost per hectare (economics.routes.ts).
 *
 * The request bodies are validated, then written back to JSON and read by the domain parsers,
 * so their properties carry the normalised values.
 */
@RestController
@RequestMapping("/api/v1/economics")
@Tag(name = "Economics")
class EconomicsController(
    private val economics: EconomicsService,
    private val access: AccessGuard,
    private val mapper: ObjectMapper,
) {

    class LaborInput(
        @field:DecimalMin("0") @field:DecimalMax(MONEY_MAX) val monthlySalary: Double?,
        @field:DecimalMin("0") @field:DecimalMax("500") val hourlyRate: Double?,
    )

    class MachineInput(
        @field:NotNull @field:DecimalMin("0") @field:DecimalMax(MONEY_MAX) val purchasePrice: Double?,
        @field:DecimalMin("0") @field:DecimalMax("100") val residualPct: Double?,
        @field:NotNull @field:DecimalMin("0.5") @field:DecimalMax("50") val depreciationYears: Double?,
        @field:NotNull @field:DecimalMin("1") @field:DecimalMax("8760") val annualHours: Double?,
        @field:DecimalMin("0") @field:DecimalMax(MONEY_MAX) val serviceCostPerYear: Double?,
        @field:DecimalMin("0") @field:DecimalMax(MONEY_MAX) val maintenanceCostPerYear: Double?,
    )

    class SettingsInput(
        @field:NotNull @field:DecimalMin("0") @field:DecimalMax("20") val fuelPricePerL: Double?,
        @field:NotNull @field:DecimalMin("0") @field:DecimalMax("500") val defaultLaborRate: Double?,
        @field:NotNull val labor: Map<@Size(max = 64) String, @NotNull @Valid LaborInput>?,
        @field:NotNull val machines: Map<@Size(max = 64) String, @NotNull @Valid MachineInput>?,
    )

    // The order of the Java record, which the OpenAPI document keeps.
    @JsonPropertyOrder("id", "supplier", "hybrid", "category", "unit", "bagSize", "ratePerHa", "pricePerBag", "bagsUsed")
    class SeedInput(
        id: String?,
        supplier: String?,
        hybrid: String?,
        category: String?,
        @field:NotNull @field:Pattern(regexp = "SEEDS|KG") val unit: String?,
        @field:NotNull @field:Positive @field:DecimalMax("10000000") val bagSize: Double?,
        @field:NotNull @field:DecimalMin("0") @field:DecimalMax("10000000") val ratePerHa: Double?,
        @field:NotNull @field:DecimalMin("0") @field:DecimalMax(MONEY_MAX) val pricePerBag: Double?,
        @field:DecimalMin("0") @field:DecimalMax("1000000") val bagsUsed: Double?,
    ) {
        @field:NotNull @field:Size(min = 1, max = 64)
        val id: String? = Text.trim(id)

        @field:NotNull @field:Size(max = 120)
        val supplier: String? = Text.trim(supplier)

        @field:NotNull @field:Size(max = 120)
        val hybrid: String? = Text.trim(hybrid)

        @field:Size(max = 40)
        val category: String? = Text.trim(category)
    }

    // The order of the Java record, which the OpenAPI document keeps.
    @JsonPropertyOrder("id", "product", "ratePerHaKg", "pricePerT", "applications", "n", "p", "k")
    class FertilizerInput(
        id: String?,
        product: String?,
        @field:NotNull @field:DecimalMin("0") @field:DecimalMax("10000") val ratePerHaKg: Double?,
        @field:NotNull @field:DecimalMin("0") @field:DecimalMax(MONEY_MAX) val pricePerT: Double?,
        @field:Min(1) @field:Max(20) val applications: Int?,
        @field:DecimalMin("0") @field:DecimalMax("100") val n: Double?,
        @field:DecimalMin("0") @field:DecimalMax("100") val p: Double?,
        @field:DecimalMin("0") @field:DecimalMax("100") val k: Double?,
    ) {
        @field:NotNull @field:Size(min = 1, max = 64)
        val id: String? = Text.trim(id)

        @field:NotNull @field:Size(max = 120)
        val product: String? = Text.trim(product)
    }

    // The order of the Java record, which the OpenAPI document keeps.
    @JsonPropertyOrder("id", "product", "kind", "dosePerHa", "unit", "pricePerUnit", "applications")
    class ChemicalInput(
        id: String?,
        product: String?,
        @field:NotNull val kind: ChemicalKind?,
        @field:NotNull @field:DecimalMin("0") @field:DecimalMax("1000") val dosePerHa: Double?,
        @field:NotNull @field:Pattern(regexp = "L|KG") val unit: String?,
        @field:NotNull @field:DecimalMin("0") @field:DecimalMax(MONEY_MAX) val pricePerUnit: Double?,
        @field:Min(1) @field:Max(20) val applications: Int?,
    ) {
        @field:NotNull @field:Size(min = 1, max = 64)
        val id: String? = Text.trim(id)

        @field:NotNull @field:Size(max = 120)
        val product: String? = Text.trim(product)
    }

    // The order of the Java record, which the OpenAPI document keeps.
    @JsonPropertyOrder("id", "label", "amount", "basis")
    class MiscInput(
        id: String?,
        label: String?,
        @field:NotNull @field:DecimalMin("0") @field:DecimalMax(MONEY_MAX) val amount: Double?,
        @field:NotNull @field:Pattern(regexp = "PER_HA|TOTAL") val basis: String?,
    ) {
        @field:NotNull @field:Size(min = 1, max = 64)
        val id: String? = Text.trim(id)

        @field:NotNull @field:Size(max = 120)
        val label: String? = Text.trim(label)
    }

    class RevenueInput(
        @field:DecimalMin("0") @field:DecimalMax("200") val expectedYieldTHa: Double?,
        @field:DecimalMin("0") @field:DecimalMax("200") val actualYieldTHa: Double?,
        @field:DecimalMin("0") @field:DecimalMax(MONEY_MAX) val pricePerT: Double?,
        @field:DecimalMin("0") @field:DecimalMax(MONEY_MAX) val actualPricePerT: Double?,
    )

    class SheetInput(
        @field:NotNull @field:Size(max = 50) val seeds: List<@NotNull @Valid SeedInput>?,
        @field:NotNull @field:Size(max = 50) val fertilizers: List<@NotNull @Valid FertilizerInput>?,
        @field:NotNull @field:Size(max = 50) val chemicals: List<@NotNull @Valid ChemicalInput>?,
        @field:NotNull @field:Size(max = 50) val misc: List<@NotNull @Valid MiscInput>?,
        @field:NotNull @field:Valid val revenue: RevenueInput?,
    )

    @JvmRecord
    data class SheetResponse(val parcelId: UUID, val year: Int, val sheet: EconomicSheet)

    @GetMapping("/settings")
    @Access(AccessGroup.PLANNERS)
    @Operation(summary = "Fuel price, labour rates and machine ownership costs")
    fun settings(auth: AuthContext): ResponseEntity<ApiResponse<EconomicSettings>> = ApiResponse.ok(economics.loadSettings(auth.farmId()))

    @PostMapping("/settings")
    @Access(AccessGroup.MANAGERS)
    @Operation(summary = "Save the economic settings of the farm")
    fun saveSettings(auth: AuthContext, @Valid @RequestBody body: SettingsInput): ResponseEntity<ApiResponse<EconomicSettings>> =
        ApiResponse.ok(economics.saveSettings(auth.farmId(), Economics.parseSettings(mapper.valueToTree(body))))

    @GetMapping("/sheets")
    @Access(AccessGroup.PLANNERS)
    @Operation(summary = "The economic sheets of every parcel in a season")
    fun sheets(auth: AuthContext, @RequestParam @Min(2000) @Max(2100) year: Int): ResponseEntity<ApiResponse<List<SheetResponse>>> =
        ApiResponse.ok(economics.loadSheets(auth.farmId(), year).map { (parcelId, sheet) -> SheetResponse(parcelId, year, sheet) })

    @GetMapping("/sheets/{parcelId}/{year}")
    @Access(AccessGroup.PLANNERS)
    @Operation(summary = "The economic sheet of a parcel in a season")
    fun sheet(
        auth: AuthContext,
        @PathVariable parcelId: UUID,
        @PathVariable @Min(2000) @Max(2100) year: Int,
    ): ResponseEntity<ApiResponse<SheetResponse>> {
        access.requireParcel(auth.farmId(), parcelId)
        return ApiResponse.ok(SheetResponse(parcelId, year, economics.loadSheet(auth.farmId(), parcelId, year)))
    }

    @PostMapping("/sheets/{parcelId}/{year}")
    @Access(AccessGroup.PLANNERS)
    @Operation(summary = "Save the seed, fertiliser, crop protection, other costs and revenue of a parcel")
    fun saveSheet(
        auth: AuthContext,
        @PathVariable parcelId: UUID,
        @PathVariable @Min(2000) @Max(2100) year: Int,
        @Valid @RequestBody body: SheetInput,
    ): ResponseEntity<ApiResponse<SheetResponse>> {
        access.requireParcel(auth.farmId(), parcelId)
        val sheet = economics.saveSheet(auth.farmId(), parcelId, year, Economics.parseSheet(mapper.valueToTree(body)))
        return ApiResponse.ok(SheetResponse(parcelId, year, sheet))
    }

    @GetMapping("/farm")
    @Access(AccessGroup.PLANNERS)
    @Operation(summary = "Real cost per hectare and profit of every parcel, by crop and by technology")
    fun farm(auth: AuthContext, @RequestParam @Min(2000) @Max(2100) year: Int): ResponseEntity<ApiResponse<FarmEconomicsResult>> =
        ApiResponse.ok(economics.farmEconomics(auth.farmId(), year, null))

    private companion object {
        const val MONEY_MAX = "100000000"
    }
}
