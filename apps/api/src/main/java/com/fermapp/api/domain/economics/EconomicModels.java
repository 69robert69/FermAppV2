package com.fermapp.api.domain.economics;

import com.fermapp.api.domain.model.CropCode;
import com.fermapp.api.domain.tillage.Tillage.TillageSystem;
import java.util.List;
import java.util.Map;

/** Data types of the economics module (economics.ts). They are also the JSON shapes. */
public final class EconomicModels {

    private EconomicModels() {
    }

    /** The economic categories of the production cost, in display order. */
    public enum EconomicCategory { SEEDS, FERTILIZERS, CHEMICALS, FUEL, MACHINERY, LABOR, OTHER }

    public enum ChemicalKind { HERBICIDE, FUNGICIDE, INSECTICIDE, GROWTH_REGULATOR }

    /**
     * A seed line of the sheet.
     *
     * @param category seed category for cereals, for example Basic, C1 or C2
     * @param unit corn and sunflower are sold by seeds per bag (SEEDS), cereals by kilograms (KG)
     * @param bagSize seeds or kilograms in one bag
     * @param bagsUsed bags actually used; when empty the need is computed from the rate
     */
    public record SeedLine(String id, String supplier, String hybrid, String category, String unit, double bagSize,
                           double ratePerHa, double pricePerBag, Double bagsUsed) {
    }

    /** A fertiliser line; n, p and k are the nutrient content in percent. */
    public record FertilizerLine(String id, String product, double ratePerHaKg, double pricePerT, double applications,
                                 Double n, Double p, Double k) {
    }

    public record ChemicalLine(String id, String product, ChemicalKind kind, double dosePerHa, String unit,
                               double pricePerUnit, double applications) {
    }

    /** Another cost; PER_HA multiplies by the area, TOTAL is the amount for the whole parcel. */
    public record MiscLine(String id, String label, double amount, String basis) {
    }

    /** Revenue inputs; {@code actualPricePerT} is the price obtained, when it differs from the planned one. */
    public record RevenueInputs(Double expectedYieldTHa, Double actualYieldTHa, Double pricePerT, Double actualPricePerT) {
    }

    public record EconomicSheet(List<SeedLine> seeds, List<FertilizerLine> fertilizers, List<ChemicalLine> chemicals,
                                List<MiscLine> misc, RevenueInputs revenue) {
    }

    /** Ownership costs of a machine; {@code residualPct} is the value left at the end, in percent. */
    public record MachineCostProfile(double purchasePrice, double residualPct, double depreciationYears, double annualHours,
                                     double serviceCostPerYear, double maintenanceCostPerYear) {
    }

    public record LaborProfile(Double monthlySalary, Double hourlyRate) {
    }

    public record EconomicSettings(double fuelPricePerL, double defaultLaborRate, Map<String, LaborProfile> labor,
                                   Map<String, MachineCostProfile> machines) {
    }

    public record SeedCost(SeedLine line, double costPerHa, Double costPerKg, Double costPer1000Seeds, double bagsNeeded, double total) {
    }

    public record Nutrients(double n, double p, double k) {
    }

    public record FertilizerCost(FertilizerLine line, double pricePerKg, double costPerHa, double total, Nutrients nutrients) {
    }

    public record ChemicalCost(ChemicalLine line, double costPerHa, double total, double quantity) {
    }

    public record MiscCost(MiscLine line, double total) {
    }

    public record MachineHourlyCost(double perHour, double depreciation, double service, double maintenance, boolean estimated) {
    }

    public record OperationMachine(String id, String name, String type, Double fuelLPerH) {
    }

    public record OperationOperator(String id, String name) {
    }

    /**
     * A logged field operation.
     *
     * @param areaHa planned area of the operation
     * @param hours hours actually worked so far
     */
    public record OperationInput(String id, String type, String status, double areaHa, double progressPct, double hours,
                                 Double fuelLiters, OperationMachine machine, OperationOperator operator, String startedAt) {
    }

    /** A priced operation. {@code projected}: not in the work log yet; {@code estimated}: typical values were used. */
    public record OperationCost(String id, String type, String label, String machineName, String operatorName, double areaHa,
                                double hours, double fuelL, double fuelCost, double machineCost, double laborCost,
                                double total, double costPerHa, boolean projected, boolean estimated, String startedAt) {
    }

    public record CostTotals(Map<EconomicCategory, Double> byCategory, double total, double perHa) {
    }

    public record ProfitResult(double yieldTHa, double pricePerT, double revenue, double revenuePerHa, double profit,
                               double profitPerHa, Double marginPct) {
    }

    public record RecordedCost(com.fermapp.api.domain.model.CostCategory category, double amount) {
    }

    /** Economics of one parcel; {@code toDate} is what was spent, {@code season} adds the operations still to come. */
    public record ParcelEconomicsResult(double areaHa, List<SeedCost> seeds, List<FertilizerCost> fertilizers,
                                        List<ChemicalCost> chemicals, List<MiscCost> misc, List<OperationCost> operations,
                                        List<OperationCost> projected, double recordedTotal, CostTotals toDate,
                                        CostTotals season, ProfitResult expected, ProfitResult actual) {
    }

    public record FarmParcelEconomics(String parcelId, String name, CropCode crop, String cropName, TillageSystem tillage,
                                      ParcelEconomicsResult result) {
    }

    public record EconomicGroup(String key, String label, double areaHa, double cost, double costPerHa, double fuelL,
                                double fuelLPerHa, double passes, Double expectedProfit, Double expectedProfitPerHa,
                                Double actualProfit, Double actualProfitPerHa) {
    }

    /** {@code profitPerHa}: the best available profit per hectare, actual where known, otherwise expected. */
    public record FarmEconomics(double areaHa, double cost, double costPerHa, Map<EconomicCategory, Double> byCategory,
                                Double expectedProfit, Double actualProfit, Double profitPerHa, List<EconomicGroup> byParcel,
                                List<EconomicGroup> byCrop, List<EconomicGroup> byTillage, EconomicGroup mostProfitableParcel,
                                EconomicGroup mostProfitableCrop, EconomicGroup mostEfficientTillage) {
    }

    /** A plain-language finding; {@code savings} is the estimated saving in euro, when the advice is followed. */
    public record EconomicInsight(String tone, String message, Double savings) {
    }
}
