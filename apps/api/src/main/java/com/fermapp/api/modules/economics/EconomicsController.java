package com.fermapp.api.modules.economics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fermapp.api.common.util.Text;
import com.fermapp.api.common.web.ApiResponse;
import com.fermapp.api.domain.economics.EconomicModels.ChemicalKind;
import com.fermapp.api.domain.economics.EconomicModels.EconomicSettings;
import com.fermapp.api.domain.economics.EconomicModels.EconomicSheet;
import com.fermapp.api.domain.economics.Economics;
import com.fermapp.api.modules.common.AccessGuard;
import com.fermapp.api.modules.economics.EconomicsService.FarmEconomicsResult;
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
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Economic settings, parcel sheets and the farm's real cost per hectare (economics.routes.ts). */
@RestController
@RequestMapping("/api/v1/economics")
@Tag(name = "Economics")
public class EconomicsController {

    private static final String MONEY_MAX = "100000000";

    public record LaborInput(@DecimalMin("0") @DecimalMax(MONEY_MAX) Double monthlySalary,
                             @DecimalMin("0") @DecimalMax("500") Double hourlyRate) {
    }

    public record MachineInput(@NotNull @DecimalMin("0") @DecimalMax(MONEY_MAX) Double purchasePrice,
                               @DecimalMin("0") @DecimalMax("100") Double residualPct,
                               @NotNull @DecimalMin("0.5") @DecimalMax("50") Double depreciationYears,
                               @NotNull @DecimalMin("1") @DecimalMax("8760") Double annualHours,
                               @DecimalMin("0") @DecimalMax(MONEY_MAX) Double serviceCostPerYear,
                               @DecimalMin("0") @DecimalMax(MONEY_MAX) Double maintenanceCostPerYear) {
    }

    public record SettingsInput(@NotNull @DecimalMin("0") @DecimalMax("20") Double fuelPricePerL,
                                @NotNull @DecimalMin("0") @DecimalMax("500") Double defaultLaborRate,
                                @NotNull Map<@Size(max = 64) String, @NotNull @Valid LaborInput> labor,
                                @NotNull Map<@Size(max = 64) String, @NotNull @Valid MachineInput> machines) {
    }

    public record SeedInput(@NotNull @Size(min = 1, max = 64) String id, @NotNull @Size(max = 120) String supplier,
                            @NotNull @Size(max = 120) String hybrid, @Size(max = 40) String category,
                            @NotNull @Pattern(regexp = "SEEDS|KG") String unit,
                            @NotNull @Positive @DecimalMax("10000000") Double bagSize,
                            @NotNull @DecimalMin("0") @DecimalMax("10000000") Double ratePerHa,
                            @NotNull @DecimalMin("0") @DecimalMax(MONEY_MAX) Double pricePerBag,
                            @DecimalMin("0") @DecimalMax("1000000") Double bagsUsed) {
        public SeedInput {
            id = Text.trim(id);
            supplier = Text.trim(supplier);
            hybrid = Text.trim(hybrid);
            category = Text.trim(category);
        }
    }

    public record FertilizerInput(@NotNull @Size(min = 1, max = 64) String id, @NotNull @Size(max = 120) String product,
                                  @NotNull @DecimalMin("0") @DecimalMax("10000") Double ratePerHaKg,
                                  @NotNull @DecimalMin("0") @DecimalMax(MONEY_MAX) Double pricePerT,
                                  @Min(1) @Max(20) Integer applications,
                                  @DecimalMin("0") @DecimalMax("100") Double n,
                                  @DecimalMin("0") @DecimalMax("100") Double p,
                                  @DecimalMin("0") @DecimalMax("100") Double k) {
        public FertilizerInput {
            id = Text.trim(id);
            product = Text.trim(product);
        }
    }

    public record ChemicalInput(@NotNull @Size(min = 1, max = 64) String id, @NotNull @Size(max = 120) String product,
                                @NotNull ChemicalKind kind, @NotNull @DecimalMin("0") @DecimalMax("1000") Double dosePerHa,
                                @NotNull @Pattern(regexp = "L|KG") String unit,
                                @NotNull @DecimalMin("0") @DecimalMax(MONEY_MAX) Double pricePerUnit,
                                @Min(1) @Max(20) Integer applications) {
        public ChemicalInput {
            id = Text.trim(id);
            product = Text.trim(product);
        }
    }

    public record MiscInput(@NotNull @Size(min = 1, max = 64) String id, @NotNull @Size(max = 120) String label,
                            @NotNull @DecimalMin("0") @DecimalMax(MONEY_MAX) Double amount,
                            @NotNull @Pattern(regexp = "PER_HA|TOTAL") String basis) {
        public MiscInput {
            id = Text.trim(id);
            label = Text.trim(label);
        }
    }

    public record RevenueInput(@DecimalMin("0") @DecimalMax("200") Double expectedYieldTHa,
                               @DecimalMin("0") @DecimalMax("200") Double actualYieldTHa,
                               @DecimalMin("0") @DecimalMax(MONEY_MAX) Double pricePerT,
                               @DecimalMin("0") @DecimalMax(MONEY_MAX) Double actualPricePerT) {
    }

    public record SheetInput(@NotNull @Size(max = 50) List<@NotNull @Valid SeedInput> seeds,
                             @NotNull @Size(max = 50) List<@NotNull @Valid FertilizerInput> fertilizers,
                             @NotNull @Size(max = 50) List<@NotNull @Valid ChemicalInput> chemicals,
                             @NotNull @Size(max = 50) List<@NotNull @Valid MiscInput> misc,
                             @NotNull @Valid RevenueInput revenue) {
    }

    public record SheetResponse(UUID parcelId, int year, EconomicSheet sheet) {
    }

    private final EconomicsService economics;
    private final AccessGuard access;
    private final ObjectMapper mapper;

    public EconomicsController(EconomicsService economics, AccessGuard access, ObjectMapper mapper) {
        this.economics = economics;
        this.access = access;
        this.mapper = mapper;
    }

    @GetMapping("/settings")
    @Access(AccessGroup.PLANNERS)
    @Operation(summary = "Fuel price, labour rates and machine ownership costs")
    public ResponseEntity<ApiResponse<EconomicSettings>> settings(AuthContext auth) {
        return ApiResponse.ok(economics.loadSettings(auth.farmId()));
    }

    @PostMapping("/settings")
    @Access(AccessGroup.MANAGERS)
    @Operation(summary = "Save the economic settings of the farm")
    public ResponseEntity<ApiResponse<EconomicSettings>> saveSettings(AuthContext auth, @Valid @RequestBody SettingsInput body) {
        return ApiResponse.ok(economics.saveSettings(auth.farmId(), Economics.parseSettings(mapper.valueToTree(body))));
    }

    @GetMapping("/sheets")
    @Access(AccessGroup.PLANNERS)
    @Operation(summary = "The economic sheets of every parcel in a season")
    public ResponseEntity<ApiResponse<List<SheetResponse>>> sheets(AuthContext auth, @RequestParam @Min(2000) @Max(2100) int year) {
        return ApiResponse.ok(economics.loadSheets(auth.farmId(), year).entrySet().stream()
                .map(entry -> new SheetResponse(entry.getKey(), year, entry.getValue())).toList());
    }

    @GetMapping("/sheets/{parcelId}/{year}")
    @Access(AccessGroup.PLANNERS)
    @Operation(summary = "The economic sheet of a parcel in a season")
    public ResponseEntity<ApiResponse<SheetResponse>> sheet(AuthContext auth, @PathVariable UUID parcelId,
                                                           @PathVariable @Min(2000) @Max(2100) int year) {
        access.requireParcel(auth.farmId(), parcelId);
        return ApiResponse.ok(new SheetResponse(parcelId, year, economics.loadSheet(auth.farmId(), parcelId, year)));
    }

    @PostMapping("/sheets/{parcelId}/{year}")
    @Access(AccessGroup.PLANNERS)
    @Operation(summary = "Save the seed, fertiliser, crop protection, other costs and revenue of a parcel")
    public ResponseEntity<ApiResponse<SheetResponse>> saveSheet(AuthContext auth, @PathVariable UUID parcelId,
                                                               @PathVariable @Min(2000) @Max(2100) int year,
                                                               @Valid @RequestBody SheetInput body) {
        access.requireParcel(auth.farmId(), parcelId);
        EconomicSheet sheet = economics.saveSheet(auth.farmId(), parcelId, year, Economics.parseSheet(mapper.valueToTree(body)));
        return ApiResponse.ok(new SheetResponse(parcelId, year, sheet));
    }

    @GetMapping("/farm")
    @Access(AccessGroup.PLANNERS)
    @Operation(summary = "Real cost per hectare and profit of every parcel, by crop and by technology")
    public ResponseEntity<ApiResponse<FarmEconomicsResult>> farm(AuthContext auth, @RequestParam @Min(2000) @Max(2100) int year) {
        return ApiResponse.ok(economics.farmEconomics(auth.farmId(), year, null));
    }
}
