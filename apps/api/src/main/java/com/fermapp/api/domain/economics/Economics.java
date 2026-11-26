package com.fermapp.api.domain.economics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fermapp.api.common.util.Js;
import com.fermapp.api.domain.economics.EconomicModels.ChemicalCost;
import com.fermapp.api.domain.economics.EconomicModels.ChemicalKind;
import com.fermapp.api.domain.economics.EconomicModels.ChemicalLine;
import com.fermapp.api.domain.economics.EconomicModels.CostTotals;
import com.fermapp.api.domain.economics.EconomicModels.EconomicCategory;
import com.fermapp.api.domain.economics.EconomicModels.EconomicGroup;
import com.fermapp.api.domain.economics.EconomicModels.EconomicInsight;
import com.fermapp.api.domain.economics.EconomicModels.EconomicSettings;
import com.fermapp.api.domain.economics.EconomicModels.EconomicSheet;
import com.fermapp.api.domain.economics.EconomicModels.FarmEconomics;
import com.fermapp.api.domain.economics.EconomicModels.FarmParcelEconomics;
import com.fermapp.api.domain.economics.EconomicModels.FertilizerCost;
import com.fermapp.api.domain.economics.EconomicModels.FertilizerLine;
import com.fermapp.api.domain.economics.EconomicModels.LaborProfile;
import com.fermapp.api.domain.economics.EconomicModels.MachineCostProfile;
import com.fermapp.api.domain.economics.EconomicModels.MachineHourlyCost;
import com.fermapp.api.domain.economics.EconomicModels.MiscCost;
import com.fermapp.api.domain.economics.EconomicModels.MiscLine;
import com.fermapp.api.domain.economics.EconomicModels.Nutrients;
import com.fermapp.api.domain.economics.EconomicModels.OperationCost;
import com.fermapp.api.domain.economics.EconomicModels.OperationInput;
import com.fermapp.api.domain.economics.EconomicModels.ParcelEconomicsResult;
import com.fermapp.api.domain.economics.EconomicModels.ProfitResult;
import com.fermapp.api.domain.economics.EconomicModels.RecordedCost;
import com.fermapp.api.domain.economics.EconomicModels.RevenueInputs;
import com.fermapp.api.domain.economics.EconomicModels.SeedCost;
import com.fermapp.api.domain.economics.EconomicModels.SeedLine;
import com.fermapp.api.domain.model.CostCategory;
import com.fermapp.api.domain.model.CropCode;
import com.fermapp.api.domain.tillage.Tillage;
import com.fermapp.api.domain.tillage.Tillage.TillageProfile;
import com.fermapp.api.domain.tillage.Tillage.TillageSystem;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Real production cost per hectare, in euro (economics.ts).
 *
 * <p>Inputs come from the parcel's economic sheet. Field operations come from the work log:
 * each one is costed for diesel, machine ownership and labour. Operations the crop still needs
 * are projected, so the cost per hectare is realistic from the first day of the season.</p>
 */
public final class Economics {

    public static final Map<EconomicCategory, String> LABELS = new EnumMap<>(Map.of(
            EconomicCategory.SEEDS, "Seeds",
            EconomicCategory.FERTILIZERS, "Fertilisers",
            EconomicCategory.CHEMICALS, "Crop protection",
            EconomicCategory.FUEL, "Diesel",
            EconomicCategory.MACHINERY, "Machinery",
            EconomicCategory.LABOR, "Labour",
            EconomicCategory.OTHER, "Other costs"));

    /** Expenses recorded by category land in the matching economic category. */
    public static final Map<CostCategory, EconomicCategory> RECORDED_TO_ECONOMIC = new EnumMap<>(Map.of(
            CostCategory.SEED, EconomicCategory.SEEDS,
            CostCategory.FERTILIZER, EconomicCategory.FERTILIZERS,
            CostCategory.PESTICIDE, EconomicCategory.CHEMICALS,
            CostCategory.FUEL, EconomicCategory.FUEL,
            CostCategory.LABOR, EconomicCategory.LABOR,
            CostCategory.IRRIGATION, EconomicCategory.OTHER,
            CostCategory.OTHER, EconomicCategory.OTHER));

    public static final double DEFAULT_LABOR_RATE_EUR_H = 10;
    /** Working hours in a month, used to turn a monthly salary into an hourly rate. */
    public static final double HOURS_PER_MONTH = 168;

    /** Typical ownership and upkeep per hour when the machine's own figures are missing. */
    public static final Map<String, Double> DEFAULT_MACHINE_COST_EUR_H = Map.of(
            "TRACTOR", 35.0, "COMBINE", 120.0, "SPRAYER", 45.0, "SEEDER", 20.0,
            "DISC", 12.0, "CULTIVATOR", 12.0, "SUBSOILER", 14.0, "TRAILER", 6.0);
    private static final double FALLBACK_MACHINE_COST_EUR_H = 35;

    /** Typical diesel per hectare when neither the log nor the machine says more. */
    public static final Map<String, Double> DEFAULT_FUEL_L_PER_HA = Map.of(
            "PLOWING", 20.0, "DISCING", 9.0, "SUBSOILING", 15.0, "SOWING", 6.0,
            "FERTILIZING", 2.0, "SPRAYING", 1.5, "HARVESTING", 16.0);

    public static final Map<String, String> WORK_OPERATION_LABELS = Map.of(
            "PLOWING", "Ploughing", "DISCING", "Discing", "SUBSOILING", "Subsoiling", "SOWING", "Sowing",
            "FERTILIZING", "Fertilising", "SPRAYING", "Spraying", "HARVESTING", "Harvesting");

    private Economics() {
    }

    private static double positive(Double value, double fallback) {
        return value != null && Double.isFinite(value) && value >= 0 ? value : fallback;
    }

    private static double positive(double value) {
        return positive(value, 0);
    }

    // ------------------------------------------------------------------ line costs

    public static SeedCost seedCost(SeedLine line, double areaHa) {
        double bag = positive(line.bagSize(), 1);
        if (bag == 0) {
            bag = 1;
        }
        double perUnit = positive(line.pricePerBag()) / bag;
        double costPerHa = perUnit * positive(line.ratePerHa());
        double bagsNeeded = positive(line.ratePerHa()) * areaHa / bag;
        double total = line.bagsUsed() != null && line.bagsUsed() >= 0
                ? line.bagsUsed() * positive(line.pricePerBag())
                : costPerHa * areaHa;
        return new SeedCost(line, Js.round2(costPerHa),
                "KG".equals(line.unit()) ? Js.round2(perUnit) : null,
                "SEEDS".equals(line.unit()) ? Js.round2(perUnit * 1000) : null,
                Js.round1(bagsNeeded), Js.round2(total));
    }

    public static FertilizerCost fertilizerCost(FertilizerLine line, double areaHa) {
        double applications = Math.max(1, positive(line.applications(), 1));
        double kgPerHa = positive(line.ratePerHaKg()) * applications;
        double pricePerKg = positive(line.pricePerT()) / 1000;
        double costPerHa = kgPerHa * pricePerKg;
        return new FertilizerCost(line, Js.round2(pricePerKg * 1000) / 1000, Js.round2(costPerHa), Js.round2(costPerHa * areaHa),
                new Nutrients(Js.round(kgPerHa * positive(line.n(), 0) / 100), Js.round(kgPerHa * positive(line.p(), 0) / 100),
                        Js.round(kgPerHa * positive(line.k(), 0) / 100)));
    }

    public static ChemicalCost chemicalCost(ChemicalLine line, double areaHa) {
        double applications = Math.max(1, positive(line.applications(), 1));
        double perHa = positive(line.dosePerHa()) * positive(line.pricePerUnit()) * applications;
        return new ChemicalCost(line, Js.round2(perHa), Js.round2(perHa * areaHa),
                Js.round1(positive(line.dosePerHa()) * applications * areaHa));
    }

    public static double miscCost(MiscLine line, double areaHa) {
        return Js.round2("PER_HA".equals(line.basis()) ? positive(line.amount()) * areaHa : positive(line.amount()));
    }

    // ------------------------------------------------------------------ machines and labour

    public static MachineHourlyCost machineHourlyCost(MachineCostProfile profile, String machineType) {
        if (profile == null || !(profile.annualHours() > 0) || !(profile.depreciationYears() > 0)) {
            double perHour = DEFAULT_MACHINE_COST_EUR_H.getOrDefault(machineType == null ? "" : machineType, FALLBACK_MACHINE_COST_EUR_H);
            return new MachineHourlyCost(perHour, perHour, 0, 0, true);
        }
        double residual = positive(profile.residualPct(), 20) / 100;
        double depreciation = positive(profile.purchasePrice()) * (1 - residual) / profile.depreciationYears() / profile.annualHours();
        double service = positive(profile.serviceCostPerYear()) / profile.annualHours();
        double maintenance = positive(profile.maintenanceCostPerYear()) / profile.annualHours();
        return new MachineHourlyCost(Js.round2(depreciation + service + maintenance), Js.round2(depreciation),
                Js.round2(service), Js.round2(maintenance), false);
    }

    public static double laborHourlyRate(LaborProfile profile, double fallback) {
        if (profile != null && profile.hourlyRate() != null && profile.hourlyRate() > 0) {
            return profile.hourlyRate();
        }
        if (profile != null && profile.monthlySalary() != null && profile.monthlySalary() > 0) {
            return Js.round2(profile.monthlySalary() / HOURS_PER_MONTH);
        }
        return fallback;
    }

    // ------------------------------------------------------------------ operations

    private static double typicalFuel(String type) {
        return DEFAULT_FUEL_L_PER_HA.getOrDefault(type, 5.0);
    }

    /** Diesel per hectare for an operation, following the tillage technology when one is set. */
    public static double fuelPerHaFor(String type, TillageSystem tillage) {
        double typical = typicalFuel(type);
        if (tillage == null) {
            return typical;
        }
        TillageProfile profile = Tillage.profile(tillage);
        List<String> tillageTypes = profile.operations().stream().map(Tillage.TillageOperation::workType).toList();
        if (!tillageTypes.contains(type)) {
            return typical;
        }
        // The technology's diesel per hectare is shared between its operations by their typical weight.
        double total = tillageTypes.stream().mapToDouble(Economics::typicalFuel).sum();
        return Js.round1(profile.fuelLPerHa() * typicalFuel(type) / total);
    }

    private record PricingBase(String id, String type, double areaHa, double hours, double fuelL, String machineType,
                               String machineId, String operatorId) {
    }

    private record PricingExtra(String machineName, String operatorName, boolean projected, boolean estimated, String startedAt) {
    }

    private static OperationCost priceOperation(PricingBase base, EconomicSettings settings, PricingExtra extra) {
        MachineHourlyCost machine = machineHourlyCost(base.machineId() != null ? settings.machines().get(base.machineId()) : null,
                base.machineType() != null ? base.machineType() : "TRACTOR");
        double labor = laborHourlyRate(base.operatorId() != null ? settings.labor().get(base.operatorId()) : null,
                settings.defaultLaborRate());
        double fuelCost = base.fuelL() * settings.fuelPricePerL();
        double machineCost = machine.perHour() * base.hours();
        double laborCost = labor * base.hours();
        double total = fuelCost + machineCost + laborCost;
        return new OperationCost(base.id(), base.type(), WORK_OPERATION_LABELS.getOrDefault(base.type(), base.type()),
                extra.machineName(), extra.operatorName(), Js.round2(base.areaHa()), Js.round1(base.hours()),
                Js.round(base.fuelL()), Js.round2(fuelCost), Js.round2(machineCost), Js.round2(laborCost), Js.round2(total),
                base.areaHa() > 0 ? Js.round2(total / base.areaHa()) : 0, extra.projected(),
                extra.estimated() || machine.estimated(), extra.startedAt());
    }

    /** Prices one logged operation: diesel, machine hours and operator hours. */
    public static OperationCost costOperation(OperationInput op, EconomicSettings settings, TillageSystem tillage) {
        double areaHa = op.areaHa() * Math.min(100, Math.max(0, op.progressPct())) / 100;
        double hours = Math.max(0, op.hours());
        Double loggedFuel = op.fuelLiters() != null && op.fuelLiters() > 0 ? op.fuelLiters() : null;
        Double machineFuel = op.machine() != null && op.machine().fuelLPerH() != null && op.machine().fuelLPerH() != 0
                ? op.machine().fuelLPerH() * hours : null;
        double fuelL = loggedFuel != null ? loggedFuel : machineFuel != null ? machineFuel : fuelPerHaFor(op.type(), tillage) * areaHa;
        return priceOperation(
                new PricingBase(op.id(), op.type(), areaHa, hours, fuelL,
                        op.machine() != null ? op.machine().type() : null,
                        op.machine() != null ? op.machine().id() : null,
                        op.operator() != null ? op.operator().id() : null),
                settings,
                new PricingExtra(op.machine() != null ? op.machine().name() : null,
                        op.operator() != null ? op.operator().name() : null, false,
                        loggedFuel == null && machineFuel == null, op.startedAt()));
    }

    /** Field capacity of an operation from the implement catalog, in hectares per hour. */
    private static double typicalCapacityHaH(String type) {
        if ("HARVESTING".equals(type)) {
            return 6 * 6 * 0.7 / 10;
        }
        return ImplementCatalog.forOperation(type)
                .map(implement -> {
                    double width = implement.widthsM().get((implement.widthsM().size() - 1) / 2);
                    return width * implement.speedKmh() * (implement.efficiencyPct() / 100) / 10;
                })
                .orElse(2.0);
    }

    /** The operations a season needs: the technology's tillage and sowing, fertilising, spraying and harvest. */
    public static List<String> seasonOperations(EconomicSheet sheet, TillageSystem tillage, CropCode crop) {
        List<String> types = new ArrayList<>(tillage != null
                ? Tillage.profile(tillage).operations().stream().map(Tillage.TillageOperation::workType).toList()
                : List.of("DISCING", "SOWING"));
        double passes = 0;
        for (FertilizerLine line : sheet.fertilizers()) {
            passes = Math.max(passes, Math.max(1, line.applications()));
        }
        long existing = types.stream().filter("FERTILIZING"::equals).count();
        for (int i = 0; i < passes - existing; i++) {
            types.add("FERTILIZING");
        }
        double spraying = sheet.chemicals().stream().mapToDouble(line -> Math.max(1, line.applications())).sum();
        for (int i = 0; i < spraying; i++) {
            types.add("SPRAYING");
        }
        if (crop != null) {
            types.add("HARVESTING");
        }
        return types;
    }

    /** Estimates the operations still to come, with the typical machine, diesel and labour. */
    public static List<OperationCost> projectOperations(List<String> needed, List<OperationInput> logged, double areaHa,
                                                        EconomicSettings settings, TillageSystem tillage) {
        Map<String, Double> done = new LinkedHashMap<>();
        for (OperationInput op : logged) {
            if ("CANCELLED".equals(op.status())) {
                continue;
            }
            done.merge(op.type(), Math.min(1, op.progressPct() / 100), Double::sum);
        }
        List<OperationCost> projected = new ArrayList<>();
        for (int index = 0; index < needed.size(); index++) {
            String type = needed.get(index);
            double covered = done.getOrDefault(type, 0.0);
            double share = Math.max(0, Math.min(1, 1 - covered));
            done.put(type, Math.max(0, covered - 1));
            if (share <= 0.01) {
                continue;
            }
            double area = areaHa * share;
            double hours = area / typicalCapacityHaH(type);
            projected.add(priceOperation(
                    new PricingBase("projected-" + index + "-" + type, type, area, hours, fuelPerHaFor(type, tillage) * area,
                            "HARVESTING".equals(type) ? "COMBINE" : "TRACTOR", null, null),
                    settings, new PricingExtra(null, null, true, true, null)));
        }
        return projected;
    }

    // ------------------------------------------------------------------ parcel

    private static Map<EconomicCategory, Double> emptyCategories() {
        Map<EconomicCategory, Double> map = new EnumMap<>(EconomicCategory.class);
        for (EconomicCategory category : EconomicCategory.values()) {
            map.put(category, 0.0);
        }
        return map;
    }

    private static CostTotals totals(Map<EconomicCategory, Double> byCategory, double areaHa) {
        Map<EconomicCategory, Double> rounded = new EnumMap<>(EconomicCategory.class);
        double sum = 0;
        for (EconomicCategory category : EconomicCategory.values()) {
            rounded.put(category, Js.round2(byCategory.get(category)));
            sum += byCategory.get(category);
        }
        double total = Js.round2(sum);
        return new CostTotals(rounded, total, areaHa > 0 ? Js.round2(total / areaHa) : 0);
    }

    private static ProfitResult profit(double areaHa, Double yieldTHa, Double pricePerT, double cost) {
        if (yieldTHa == null || pricePerT == null || !(yieldTHa > 0) || !(pricePerT > 0)) {
            return null;
        }
        double revenue = yieldTHa * pricePerT * areaHa;
        double result = revenue - cost;
        return new ProfitResult(yieldTHa, pricePerT, Js.round2(revenue), Js.round2(yieldTHa * pricePerT), Js.round2(result),
                areaHa > 0 ? Js.round2(result / areaHa) : 0,
                revenue > 0 ? Math.round(result / revenue * 1000) / 10.0 : null);
    }

    public static ParcelEconomicsResult computeParcelEconomics(double areaHa, EconomicSheet sheet, List<OperationInput> operations,
                                                               EconomicSettings settings, TillageSystem tillage, CropCode crop,
                                                               List<RecordedCost> recorded) {
        List<SeedCost> seeds = sheet.seeds().stream().map(line -> seedCost(line, areaHa)).toList();
        List<FertilizerCost> fertilizers = sheet.fertilizers().stream().map(line -> fertilizerCost(line, areaHa)).toList();
        List<ChemicalCost> chemicals = sheet.chemicals().stream().map(line -> chemicalCost(line, areaHa)).toList();
        List<MiscCost> misc = sheet.misc().stream().map(line -> new MiscCost(line, miscCost(line, areaHa))).toList();
        List<OperationInput> logged = operations.stream()
                .filter(op -> !"CANCELLED".equals(op.status()) || op.progressPct() > 0).toList();
        List<OperationCost> priced = logged.stream().map(op -> costOperation(op, settings, tillage)).toList();
        List<OperationCost> projected = projectOperations(seasonOperations(sheet, tillage, crop), logged, areaHa, settings, tillage);

        Map<EconomicCategory, Double> spent = emptyCategories();
        spent.merge(EconomicCategory.SEEDS, seeds.stream().mapToDouble(SeedCost::total).sum(), Double::sum);
        spent.merge(EconomicCategory.FERTILIZERS, fertilizers.stream().mapToDouble(FertilizerCost::total).sum(), Double::sum);
        spent.merge(EconomicCategory.CHEMICALS, chemicals.stream().mapToDouble(ChemicalCost::total).sum(), Double::sum);
        spent.merge(EconomicCategory.OTHER, misc.stream().mapToDouble(MiscCost::total).sum(), Double::sum);
        for (OperationCost op : priced) {
            spent.merge(EconomicCategory.FUEL, op.fuelCost(), Double::sum);
            spent.merge(EconomicCategory.MACHINERY, op.machineCost(), Double::sum);
            spent.merge(EconomicCategory.LABOR, op.laborCost(), Double::sum);
        }
        double recordedTotal = 0;
        for (RecordedCost entry : recorded == null ? List.<RecordedCost>of() : recorded) {
            spent.merge(RECORDED_TO_ECONOMIC.get(entry.category()), entry.amount(), Double::sum);
            recordedTotal += entry.amount();
        }
        Map<EconomicCategory, Double> season = new EnumMap<>(spent);
        for (OperationCost op : projected) {
            season.merge(EconomicCategory.FUEL, op.fuelCost(), Double::sum);
            season.merge(EconomicCategory.MACHINERY, op.machineCost(), Double::sum);
            season.merge(EconomicCategory.LABOR, op.laborCost(), Double::sum);
        }
        CostTotals toDate = totals(spent, areaHa);
        CostTotals seasonTotals = totals(season, areaHa);
        RevenueInputs revenue = sheet.revenue();
        return new ParcelEconomicsResult(areaHa, seeds, fertilizers, chemicals, misc, priced, projected,
                Js.round2(recordedTotal), toDate, seasonTotals,
                profit(areaHa, revenue.expectedYieldTHa(), revenue.pricePerT(), seasonTotals.total()),
                profit(areaHa, revenue.actualYieldTHa(), revenue.actualPricePerT() != null ? revenue.actualPricePerT() : revenue.pricePerT(),
                        seasonTotals.total()));
    }

    // ------------------------------------------------------------------ farm

    private static EconomicGroup group(String key, String label, List<FarmParcelEconomics> items) {
        double areaHa = items.stream().mapToDouble(item -> item.result().areaHa()).sum();
        double cost = items.stream().mapToDouble(item -> item.result().season().total()).sum();
        double fuelL = items.stream().mapToDouble(item -> item.result().operations().stream().mapToDouble(OperationCost::fuelL).sum()
                + item.result().projected().stream().mapToDouble(OperationCost::fuelL).sum()).sum();
        double passes = areaHa > 0
                ? items.stream().mapToDouble(item -> (item.result().operations().size() + item.result().projected().size())
                        * item.result().areaHa()).sum() / areaHa
                : 0;
        Double expectedProfit = sumProfit(items, item -> item.result().expected());
        Double actualProfit = sumProfit(items, item -> item.result().actual());
        double expectedArea = areaOf(items, item -> item.result().expected());
        double actualArea = areaOf(items, item -> item.result().actual());
        return new EconomicGroup(key, label, Js.round2(areaHa), Js.round2(cost), areaHa > 0 ? Js.round2(cost / areaHa) : 0,
                Js.round(fuelL), areaHa > 0 ? Js.round1(fuelL / areaHa) : 0, Js.round1(passes),
                expectedProfit == null ? null : Js.round2(expectedProfit),
                expectedProfit == null || expectedArea == 0 ? null : Js.round2(expectedProfit / expectedArea),
                actualProfit == null ? null : Js.round2(actualProfit),
                actualProfit == null || actualArea == 0 ? null : Js.round2(actualProfit / actualArea));
    }

    private static Double sumProfit(List<FarmParcelEconomics> items, Function<FarmParcelEconomics, ProfitResult> pick) {
        List<ProfitResult> known = items.stream().map(pick).filter(java.util.Objects::nonNull).toList();
        return known.isEmpty() ? null : known.stream().mapToDouble(ProfitResult::profit).sum();
    }

    private static double areaOf(List<FarmParcelEconomics> items, Function<FarmParcelEconomics, ProfitResult> pick) {
        return items.stream().filter(item -> pick.apply(item) != null).mapToDouble(item -> item.result().areaHa()).sum();
    }

    private static Double bestProfitPerHa(EconomicGroup item) {
        return item.actualProfitPerHa() != null ? item.actualProfitPerHa() : item.expectedProfitPerHa();
    }

    private record GroupKey(String key, String label) {
    }

    private static List<EconomicGroup> groupBy(List<FarmParcelEconomics> parcels, Function<FarmParcelEconomics, GroupKey> keyOf) {
        Map<String, String> labels = new LinkedHashMap<>();
        Map<String, List<FarmParcelEconomics>> groups = new LinkedHashMap<>();
        for (FarmParcelEconomics item : parcels) {
            GroupKey key = keyOf.apply(item);
            if (key == null) {
                continue;
            }
            labels.putIfAbsent(key.key(), key.label());
            groups.computeIfAbsent(key.key(), k -> new ArrayList<>()).add(item);
        }
        List<EconomicGroup> result = new ArrayList<>();
        for (Map.Entry<String, List<FarmParcelEconomics>> entry : groups.entrySet()) {
            result.add(group(entry.getKey(), labels.get(entry.getKey()), entry.getValue()));
        }
        result.sort(Comparator.comparingDouble(EconomicGroup::cost).reversed());
        return result;
    }

    private static EconomicGroup best(List<EconomicGroup> groups, Function<EconomicGroup, Double> score) {
        EconomicGroup winner = null;
        double top = Double.NEGATIVE_INFINITY;
        for (EconomicGroup item : groups) {
            Double value = score.apply(item);
            if (value != null && value > top) {
                top = value;
                winner = item;
            }
        }
        return winner;
    }

    public static FarmEconomics summarizeFarmEconomics(List<FarmParcelEconomics> parcels) {
        EconomicGroup all = group("farm", "Farm", parcels);
        Map<EconomicCategory, Double> byCategory = emptyCategories();
        for (FarmParcelEconomics item : parcels) {
            for (EconomicCategory category : EconomicCategory.values()) {
                byCategory.merge(category, item.result().season().byCategory().get(category), Double::sum);
            }
        }
        byCategory.replaceAll((category, value) -> Js.round2(value));
        List<EconomicGroup> byParcel = new ArrayList<>(parcels.stream().map(item -> group(item.parcelId(), item.name(), List.of(item))).toList());
        byParcel.sort(Comparator.comparingDouble(EconomicGroup::cost).reversed());
        List<EconomicGroup> byCrop = groupBy(parcels, item -> item.crop() != null
                ? new GroupKey(item.crop().name(), item.cropName() != null ? item.cropName() : item.crop().name())
                : new GroupKey("NONE", "No crop"));
        List<EconomicGroup> byTillage = groupBy(parcels, item -> item.tillage() != null
                ? new GroupKey(item.tillage().name(), Tillage.profile(item.tillage()).name()) : null);
        // The most efficient technology earns the most per hectare; without revenue data, the one that costs the least.
        EconomicGroup efficient = best(byTillage, Economics::bestProfitPerHa);
        if (efficient == null) {
            efficient = best(byTillage, item -> item.areaHa() > 0 ? -item.costPerHa() : null);
        }
        return new FarmEconomics(all.areaHa(), all.cost(), all.costPerHa(), byCategory, all.expectedProfit(), all.actualProfit(),
                bestProfitPerHa(all), byParcel,
                byCrop.stream().filter(item -> !"NONE".equals(item.key()) || item.areaHa() > 0).toList(),
                byTillage, best(byParcel, Economics::bestProfitPerHa),
                best(byCrop.stream().filter(item -> !"NONE".equals(item.key())).toList(), Economics::bestProfitPerHa),
                efficient);
    }

    // ------------------------------------------------------------------ advice

    /** Plain-language findings: where the money goes and what could be cheaper. */
    public static List<EconomicInsight> economicInsights(ParcelEconomicsResult result, TillageSystem tillage) {
        List<EconomicInsight> insights = new ArrayList<>();
        CostTotals season = result.season();
        if (season.total() <= 0) {
            return List.of(new EconomicInsight("info", "Add seed, fertiliser and crop protection to see the real cost per hectare.", null));
        }

        EconomicCategory topCategory = null;
        double topValue = Double.NEGATIVE_INFINITY;
        for (EconomicCategory category : EconomicCategory.values()) {
            double value = season.byCategory().get(category);
            if (value > topValue) {
                topValue = value;
                topCategory = category;
            }
        }
        long share = Math.round(topValue / season.total() * 100);
        if (share >= 25) {
            insights.add(new EconomicInsight(share >= 40 ? "warning" : "info",
                    LABELS.get(topCategory) + " is " + share + "% of the total production cost.", null));
        }

        List<OperationCost> operations = new ArrayList<>(result.operations());
        operations.addAll(result.projected());
        operations.sort(Comparator.comparingDouble(OperationCost::costPerHa).reversed());
        if (!operations.isEmpty() && operations.get(0).costPerHa() > 0) {
            OperationCost priciest = operations.get(0);
            insights.add(new EconomicInsight("info", priciest.label() + " is the most expensive operation: "
                    + Money.formatEuro(priciest.costPerHa(), 2) + "/ha" + (priciest.projected() ? " (estimated)" : "") + ".", null));
        }

        if (tillage != null) {
            TillageSystem[] order = TillageSystem.values();
            int index = tillage.ordinal() + 1;
            if (index < order.length) {
                TillageSystem next = order[index];
                double current = Tillage.costPerHa(tillage);
                double alternative = Tillage.costPerHa(next);
                long pct = current > 0 ? Math.round((current - alternative) / current * 100) : 0;
                if (pct > 0) {
                    insights.add(new EconomicInsight("success", "Applying " + Tillage.profile(next).name()
                            + " on this parcel can reduce costs by about " + pct + "%.", Js.round2(season.total() * pct / 100)));
                }
            }
        } else {
            insights.add(new EconomicInsight("info",
                    "Set the tillage technology to compare the cost of Minimum Till, Strip Till and No Till.", null));
        }

        double fuelPerHa = result.areaHa() > 0 ? season.byCategory().get(EconomicCategory.FUEL) / result.areaHa() : 0;
        if (fuelPerHa > 90) {
            insights.add(new EconomicInsight("warning", "Diesel costs " + Money.formatEuro(fuelPerHa, 0)
                    + "/ha. Fewer passes or combined operations would lower it.", null));
        }
        double fertPerHa = result.areaHa() > 0 ? season.byCategory().get(EconomicCategory.FERTILIZERS) / result.areaHa() : 0;
        if (fertPerHa > 300) {
            insights.add(new EconomicInsight("warning", "Fertiliser costs " + Money.formatEuro(fertPerHa, 0)
                    + "/ha. A soil analysis can show where the dose can be reduced.", null));
        }

        ProfitResult outlook = result.actual() != null ? result.actual() : result.expected();
        if (outlook != null) {
            if (outlook.profit() < 0) {
                Double breakEven = outlook.yieldTHa() > 0 ? season.perHa() / outlook.yieldTHa() : null;
                insights.add(new EconomicInsight("warning", "The season runs at a loss of " + Money.formatEuro(-outlook.profitPerHa(), 0)
                        + "/ha." + (breakEven != null && breakEven != 0 ? " It breaks even at " + Money.formatEuro(breakEven, 0) + "/t." : ""),
                        null));
            } else {
                insights.add(new EconomicInsight("success", "Profit of " + Money.formatEuro(outlook.profitPerHa(), 0) + "/ha, a "
                        + Js.num(outlook.marginPct() == null ? 0 : outlook.marginPct()) + "% margin.", null));
            }
        } else {
            insights.add(new EconomicInsight("info", "Enter the expected yield and the sale price to see the profit.", null));
        }
        return insights;
    }

    // ------------------------------------------------------------------ stored documents

    private static Double num(JsonNode value) {
        return value != null && value.isNumber() && Double.isFinite(value.asDouble()) ? value.asDouble() : null;
    }

    private static double num(JsonNode value, double fallback) {
        Double number = num(value);
        return number == null ? fallback : number;
    }

    private static String str(JsonNode value, int max) {
        if (value == null || !value.isTextual()) {
            return "";
        }
        String text = value.asText();
        return text.length() > max ? text.substring(0, max) : text;
    }

    private static String id(JsonNode value, int index) {
        if (value != null && value.isTextual() && !value.asText().isEmpty()) {
            String text = value.asText();
            return text.length() > 64 ? text.substring(0, 64) : text;
        }
        return "line-" + index;
    }

    private interface LineMapper<T> {
        T map(JsonNode item, int index);
    }

    private static <T> List<T> list(JsonNode value, LineMapper<T> mapper) {
        List<T> result = new ArrayList<>();
        if (value == null || !value.isArray()) {
            return result;
        }
        int limit = Math.min(50, value.size());
        for (int index = 0; index < limit; index++) {
            JsonNode item = value.get(index);
            if (item != null && item.isObject()) {
                result.add(mapper.map(item, index));
            }
        }
        return result;
    }

    /** Reads a stored sheet, keeping only well-formed lines. */
    public static EconomicSheet parseSheet(JsonNode raw) {
        JsonNode data = raw != null && raw.isObject() ? raw : com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        JsonNode revenue = data.path("revenue").isObject() ? data.path("revenue") : com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        List<SeedLine> seeds = list(data.get("seeds"), (item, index) -> new SeedLine(
                id(item.get("id"), index), str(item.get("supplier"), 120), str(item.get("hybrid"), 120),
                item.path("category").isTextual() ? str(item.get("category"), 40) : null,
                "KG".equals(item.path("unit").asText(null)) ? "KG" : "SEEDS",
                num(item.get("bagSize"), 1), num(item.get("ratePerHa"), 0), num(item.get("pricePerBag"), 0),
                num(item.get("bagsUsed"))));
        List<FertilizerLine> fertilizers = list(data.get("fertilizers"), (item, index) -> new FertilizerLine(
                id(item.get("id"), index), str(item.get("product"), 120), num(item.get("ratePerHaKg"), 0),
                num(item.get("pricePerT"), 0), num(item.get("applications"), 1),
                num(item.get("n")), num(item.get("p")), num(item.get("k"))));
        List<ChemicalLine> chemicals = list(data.get("chemicals"), (item, index) -> new ChemicalLine(
                id(item.get("id"), index), str(item.get("product"), 120), chemicalKind(item.path("kind").asText(null)),
                num(item.get("dosePerHa"), 0), "KG".equals(item.path("unit").asText(null)) ? "KG" : "L",
                num(item.get("pricePerUnit"), 0), num(item.get("applications"), 1)));
        List<MiscLine> misc = list(data.get("misc"), (item, index) -> new MiscLine(
                id(item.get("id"), index), str(item.get("label"), 120), num(item.get("amount"), 0),
                "PER_HA".equals(item.path("basis").asText(null)) ? "PER_HA" : "TOTAL"));
        return new EconomicSheet(seeds, fertilizers, chemicals, misc, new RevenueInputs(
                num(revenue.get("expectedYieldTHa")), num(revenue.get("actualYieldTHa")),
                num(revenue.get("pricePerT")), num(revenue.get("actualPricePerT"))));
    }

    private static ChemicalKind chemicalKind(String value) {
        if (value != null) {
            for (ChemicalKind kind : ChemicalKind.values()) {
                if (kind.name().equals(value)) {
                    return kind;
                }
            }
        }
        return ChemicalKind.HERBICIDE;
    }

    /** Reads stored settings, filling in the defaults. */
    public static EconomicSettings parseSettings(JsonNode raw) {
        JsonNode data = raw != null && raw.isObject() ? raw : com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        Map<String, LaborProfile> labor = new LinkedHashMap<>();
        forEachObject(data.get("labor"), (key, item) -> labor.put(key, new LaborProfile(num(item.get("monthlySalary")), num(item.get("hourlyRate")))));
        Map<String, MachineCostProfile> machines = new LinkedHashMap<>();
        forEachObject(data.get("machines"), (key, item) -> machines.put(key, new MachineCostProfile(
                num(item.get("purchasePrice"), 0), num(item.get("residualPct"), 20), num(item.get("depreciationYears"), 10),
                num(item.get("annualHours"), 500), num(item.get("serviceCostPerYear"), 0), num(item.get("maintenanceCostPerYear"), 0))));
        return new EconomicSettings(num(data.get("fuelPricePerL"), ImplementCatalog.DEFAULT_FUEL_PRICE_EUR),
                num(data.get("defaultLaborRate"), DEFAULT_LABOR_RATE_EUR_H), labor, machines);
    }

    private static void forEachObject(JsonNode value, java.util.function.BiConsumer<String, JsonNode> consumer) {
        if (value == null || !value.isObject()) {
            return;
        }
        int count = 0;
        Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
        while (fields.hasNext() && count < 500) {
            Map.Entry<String, JsonNode> entry = fields.next();
            count++;
            if (entry.getValue() != null && entry.getValue().isObject()) {
                consumer.accept(entry.getKey(), entry.getValue());
            }
        }
    }
}
