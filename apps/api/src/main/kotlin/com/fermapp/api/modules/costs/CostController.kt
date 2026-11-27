package com.fermapp.api.modules.costs

import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.fasterxml.jackson.annotation.JsonUnwrapped
import com.fermapp.api.common.error.Errors
import com.fermapp.api.common.util.Text
import com.fermapp.api.common.util.Times
import com.fermapp.api.common.web.ApiResponse
import com.fermapp.api.domain.intelligence.Intelligence
import com.fermapp.api.domain.intelligence.Intelligence.CostItem
import com.fermapp.api.domain.intelligence.Intelligence.CostSummary
import com.fermapp.api.domain.intelligence.Intelligence.CostSummaryInput
import com.fermapp.api.domain.model.CostCategory
import com.fermapp.api.domain.model.CropCode
import com.fermapp.api.modules.common.AccessGuard
import com.fermapp.api.modules.common.Views.CostView
import com.fermapp.api.persistence.entity.CostEntry
import com.fermapp.api.persistence.repository.CostEntryRepository
import com.fermapp.api.persistence.repository.CropSeasonRepository
import com.fermapp.api.security.Access
import com.fermapp.api.security.AccessGroup
import com.fermapp.api.security.AuthContext
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.persistence.criteria.Predicate
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.util.Locale
import java.util.UUID
import org.openapitools.jackson.nullable.JsonNullable
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Recorded expenses and the parcel cost summary (costs.routes.ts). */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Costs")
class CostController(
    private val costs: CostEntryRepository,
    private val seasons: CropSeasonRepository,
    private val access: AccessGuard,
) {

    // The order of the Java record, which the OpenAPI document keeps.
    @JsonPropertyOrder("parcelId", "year", "category", "amount", "currency", "date", "notes")
    class CostCreate(
        @field:NotNull val parcelId: UUID?,
        @field:NotNull @field:Min(2000) @field:Max(2100) val year: Int?,
        @field:NotNull val category: CostCategory?,
        @field:NotNull @field:Positive @field:DecimalMax("100000000") val amount: Double?,
        currency: String?,
        val date: Instant?,
        notes: String?,
    ) {
        @field:Pattern(regexp = "EUR")
        val currency: String = currency ?: "EUR"

        @field:Size(max = 500)
        val notes: String? = Text.trim(notes)
    }

    /** A partial update: absent fields stay as they are, null clears the nullable ones. */
    class CostUpdate(
        year: JsonNullable<Int>?,
        category: JsonNullable<CostCategory>?,
        amount: JsonNullable<Double>?,
        currency: JsonNullable<String>?,
        date: JsonNullable<Instant>?,
        notes: JsonNullable<String>?,
    ) {
        val year: JsonNullable<@NotNull @Min(2000) @Max(2100) Int> = year ?: JsonNullable.undefined()
        val category: JsonNullable<@NotNull CostCategory> = category ?: JsonNullable.undefined()
        val amount: JsonNullable<@NotNull @Positive @DecimalMax("100000000") Double> = amount ?: JsonNullable.undefined()
        val currency: JsonNullable<@NotNull @Size(min = 3, max = 3) String> =
            if (currency == null || !currency.isPresent) {
                JsonNullable.undefined()
            } else {
                JsonNullable.of(currency.get()?.trim { it <= ' ' }?.uppercase(Locale.getDefault()))
            }
        val date: JsonNullable<@NotNull Instant> = date ?: JsonNullable.undefined()
        val notes: JsonNullable<@Size(max = 500) String> =
            if (notes == null || !notes.isPresent) JsonNullable.undefined() else JsonNullable.of(Text.trim(notes.get()))
    }

    @JvmRecord
    data class CostSummaryResponse(
        val year: Int,
        val areaHa: Double,
        val crop: CropCode?,
        @get:JsonUnwrapped val summary: CostSummary,
    )

    private fun requireCost(farmId: UUID, id: UUID): CostEntry =
        costs.findByIdAndFarmId(id, farmId).orElseThrow { Errors.notFound("Cost") }

    @GetMapping("/costs")
    @Access(AccessGroup.PLANNERS)
    @Operation(summary = "List costs")
    fun list(
        auth: AuthContext,
        @RequestParam(required = false) parcelId: UUID?,
        @RequestParam(required = false) @Min(2000) @Max(2100) year: Int?,
    ): ResponseEntity<ApiResponse<List<CostView>>> {
        val spec = Specification<CostEntry> { root, _, cb ->
            val predicates = mutableListOf<Predicate>(cb.equal(root.get<UUID>("farmId"), auth.farmId()))
            if (parcelId != null) {
                predicates += cb.equal(root.get<UUID>("parcelId"), parcelId)
            }
            if (year != null) {
                predicates += cb.equal(root.get<Int>("year"), year)
            }
            cb.and(*predicates.toTypedArray())
        }
        return ApiResponse.ok(
            costs.findAll(spec, PageRequest.of(0, 500, Sort.by(Sort.Direction.DESC, "date"))).content.map { CostView.of(it) },
        )
    }

    @PostMapping("/costs")
    @Access(AccessGroup.MANAGERS)
    @Operation(summary = "Add a cost")
    fun create(auth: AuthContext, @Valid @RequestBody body: CostCreate): ResponseEntity<ApiResponse<CostView>> {
        access.requireParcel(auth.farmId(), body.parcelId!!)
        val cost = CostEntry()
        cost.farmId = auth.farmId()
        cost.parcelId = body.parcelId
        cost.year = body.year!!
        cost.category = body.category!!
        cost.amount = BigDecimal.valueOf(body.amount!!)
        cost.currency = body.currency
        cost.date = body.date
        cost.notes = body.notes
        val saved = costs.saveAndFlush(cost)
        return ApiResponse.created(CostView.of(costs.findById(saved.id).orElse(saved)))
    }

    @PatchMapping("/costs/{id}")
    @Access(AccessGroup.MANAGERS)
    @Operation(summary = "Update a cost")
    fun update(auth: AuthContext, @PathVariable id: UUID, @Valid @RequestBody body: CostUpdate): ResponseEntity<ApiResponse<CostView>> {
        val cost = requireCost(auth.farmId(), id)
        body.year.ifPresent { cost.year = it }
        body.category.ifPresent { cost.category = it }
        body.amount.ifPresent { cost.amount = BigDecimal.valueOf(it) }
        body.currency.ifPresent { cost.currency = it }
        body.date.ifPresent { cost.date = it }
        body.notes.ifPresent { cost.notes = it }
        return ApiResponse.ok(CostView.of(costs.saveAndFlush(cost)))
    }

    @DeleteMapping("/costs/{id}")
    @Access(AccessGroup.MANAGERS)
    @Operation(summary = "Delete a cost")
    fun delete(auth: AuthContext, @PathVariable id: UUID): ResponseEntity<Void> {
        requireCost(auth.farmId(), id)
        costs.deleteById(id)
        return ApiResponse.noContent()
    }

    @GetMapping("/parcels/{id}/costs/summary")
    @Access(AccessGroup.PLANNERS)
    @Operation(summary = "Cost per hectare, total cost and estimated or actual profit")
    fun summary(
        auth: AuthContext,
        @PathVariable id: UUID,
        @RequestParam(required = false) @Min(2000) @Max(2100) year: Int?,
    ): ResponseEntity<ApiResponse<CostSummaryResponse>> {
        val parcel = access.requireParcel(auth.farmId(), id)
        val target = year ?: Times.currentLocalYear()
        val entries = costs.findByParcelIdAndYear(parcel.id, target)
        val season = seasons.findByParcelIdAndYear(parcel.id, target).orElse(null)
        val summary = Intelligence.summarizeCosts(
            CostSummaryInput(
                parcel.areaHa,
                entries.map { CostItem(it.category, it.amount.toDouble()) },
                season?.expectedYieldTHa,
                season?.expectedPricePerT?.toDouble(),
                season?.yieldT,
                season?.actualPricePerT?.toDouble(),
            ),
        )
        return ApiResponse.ok(CostSummaryResponse(target, parcel.areaHa, season?.crop, summary))
    }
}
