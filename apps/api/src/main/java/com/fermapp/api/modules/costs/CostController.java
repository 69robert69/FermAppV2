package com.fermapp.api.modules.costs;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fermapp.api.common.error.Errors;
import com.fermapp.api.common.util.Text;
import com.fermapp.api.common.util.Times;
import com.fermapp.api.common.web.ApiResponse;
import com.fermapp.api.domain.intelligence.Intelligence;
import com.fermapp.api.domain.intelligence.Intelligence.CostItem;
import com.fermapp.api.domain.intelligence.Intelligence.CostSummary;
import com.fermapp.api.domain.intelligence.Intelligence.CostSummaryInput;
import com.fermapp.api.domain.model.CostCategory;
import com.fermapp.api.domain.model.CropCode;
import com.fermapp.api.modules.common.AccessGuard;
import com.fermapp.api.modules.common.Views.CostView;
import com.fermapp.api.persistence.entity.CostEntry;
import com.fermapp.api.persistence.entity.CropSeason;
import com.fermapp.api.persistence.entity.Parcel;
import com.fermapp.api.persistence.repository.CostEntryRepository;
import com.fermapp.api.persistence.repository.CropSeasonRepository;
import com.fermapp.api.security.Access;
import com.fermapp.api.security.AccessGroup;
import com.fermapp.api.security.AuthContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.criteria.Predicate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.openapitools.jackson.nullable.JsonNullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Recorded expenses and the parcel cost summary (costs.routes.ts). */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Costs")
public class CostController {

    public record CostCreate(@NotNull UUID parcelId, @NotNull @Min(2000) @Max(2100) Integer year, @NotNull CostCategory category,
                             @NotNull @Positive @DecimalMax("100000000") Double amount, @Pattern(regexp = "EUR") String currency,
                             Instant date, @Size(max = 500) String notes) {
        public CostCreate {
            currency = currency == null ? "EUR" : currency;
            notes = Text.trim(notes);
        }
    }

    public record CostUpdate(JsonNullable<@NotNull @Min(2000) @Max(2100) Integer> year,
                             JsonNullable<@NotNull CostCategory> category,
                             JsonNullable<@NotNull @Positive @DecimalMax("100000000") Double> amount,
                             JsonNullable<@NotNull @Size(min = 3, max = 3) String> currency,
                             JsonNullable<@NotNull Instant> date,
                             JsonNullable<@Size(max = 500) String> notes) {
        public CostUpdate {
            year = orUndefined(year);
            category = orUndefined(category);
            amount = orUndefined(amount);
            currency = currency == null || !currency.isPresent() ? JsonNullable.undefined()
                    : JsonNullable.of(currency.get() == null ? null : currency.get().trim().toUpperCase());
            date = orUndefined(date);
            notes = notes == null || !notes.isPresent() ? JsonNullable.undefined() : JsonNullable.of(Text.trim(notes.get()));
        }

        private static <T> JsonNullable<T> orUndefined(JsonNullable<T> value) {
            return value == null ? JsonNullable.undefined() : value;
        }
    }

    public record CostSummaryResponse(int year, double areaHa, CropCode crop, @JsonUnwrapped CostSummary summary) {
    }

    private final CostEntryRepository costs;
    private final CropSeasonRepository seasons;
    private final AccessGuard access;

    public CostController(CostEntryRepository costs, CropSeasonRepository seasons, AccessGuard access) {
        this.costs = costs;
        this.seasons = seasons;
        this.access = access;
    }

    private CostEntry requireCost(UUID farmId, UUID id) {
        return costs.findByIdAndFarmId(id, farmId).orElseThrow(() -> Errors.notFound("Cost"));
    }

    @GetMapping("/costs")
    @Access(AccessGroup.PLANNERS)
    @Operation(summary = "List costs")
    public ResponseEntity<ApiResponse<List<CostView>>> list(AuthContext auth, @RequestParam(required = false) UUID parcelId,
                                                           @RequestParam(required = false) @Min(2000) @Max(2100) Integer year) {
        Specification<CostEntry> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("farmId"), auth.farmId()));
            if (parcelId != null) {
                predicates.add(cb.equal(root.get("parcelId"), parcelId));
            }
            if (year != null) {
                predicates.add(cb.equal(root.get("year"), year));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return ApiResponse.ok(costs.findAll(spec, PageRequest.of(0, 500, Sort.by(Sort.Direction.DESC, "date")))
                .stream().map(CostView::of).toList());
    }

    @PostMapping("/costs")
    @Access(AccessGroup.MANAGERS)
    @Operation(summary = "Add a cost")
    public ResponseEntity<ApiResponse<CostView>> create(AuthContext auth, @Valid @RequestBody CostCreate body) {
        access.requireParcel(auth.farmId(), body.parcelId());
        CostEntry cost = new CostEntry();
        cost.setFarmId(auth.farmId());
        cost.setParcelId(body.parcelId());
        cost.setYear(body.year());
        cost.setCategory(body.category());
        cost.setAmount(BigDecimal.valueOf(body.amount()));
        cost.setCurrency(body.currency());
        cost.setDate(body.date());
        cost.setNotes(body.notes());
        CostEntry saved = costs.saveAndFlush(cost);
        return ApiResponse.created(CostView.of(costs.findById(saved.getId()).orElse(saved)));
    }

    @PatchMapping("/costs/{id}")
    @Access(AccessGroup.MANAGERS)
    @Operation(summary = "Update a cost")
    public ResponseEntity<ApiResponse<CostView>> update(AuthContext auth, @PathVariable UUID id, @Valid @RequestBody CostUpdate body) {
        CostEntry cost = requireCost(auth.farmId(), id);
        body.year().ifPresent(cost::setYear);
        body.category().ifPresent(cost::setCategory);
        body.amount().ifPresent(value -> cost.setAmount(BigDecimal.valueOf(value)));
        body.currency().ifPresent(cost::setCurrency);
        body.date().ifPresent(cost::setDate);
        body.notes().ifPresent(cost::setNotes);
        return ApiResponse.ok(CostView.of(costs.saveAndFlush(cost)));
    }

    @DeleteMapping("/costs/{id}")
    @Access(AccessGroup.MANAGERS)
    @Operation(summary = "Delete a cost")
    public ResponseEntity<Void> delete(AuthContext auth, @PathVariable UUID id) {
        requireCost(auth.farmId(), id);
        costs.deleteById(id);
        return ApiResponse.noContent();
    }

    @GetMapping("/parcels/{id}/costs/summary")
    @Access(AccessGroup.PLANNERS)
    @Operation(summary = "Cost per hectare, total cost and estimated or actual profit")
    public ResponseEntity<ApiResponse<CostSummaryResponse>> summary(AuthContext auth, @PathVariable UUID id,
                                                                   @RequestParam(required = false) @Min(2000) @Max(2100) Integer year) {
        Parcel parcel = access.requireParcel(auth.farmId(), id);
        int target = year != null ? year : Times.currentLocalYear();
        List<CostEntry> entries = costs.findByParcelIdAndYear(parcel.getId(), target);
        CropSeason season = seasons.findByParcelIdAndYear(parcel.getId(), target).orElse(null);
        CostSummary summary = Intelligence.summarizeCosts(new CostSummaryInput(parcel.getAreaHa(),
                entries.stream().map(cost -> new CostItem(cost.getCategory(), cost.getAmount().doubleValue())).toList(),
                season == null ? null : season.getExpectedYieldTHa(),
                season == null || season.getExpectedPricePerT() == null ? null : season.getExpectedPricePerT().doubleValue(),
                season == null ? null : season.getYieldT(),
                season == null || season.getActualPricePerT() == null ? null : season.getActualPricePerT().doubleValue()));
        return ApiResponse.ok(new CostSummaryResponse(target, parcel.getAreaHa(), season == null ? null : season.getCrop(), summary));
    }
}
