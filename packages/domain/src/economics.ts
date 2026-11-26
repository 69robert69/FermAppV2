import { DEFAULT_FUEL_PRICE_EUR, IMPLEMENT_CATALOG } from './catalog';
import type { CostCategory } from './costs';
import type { CropCode } from './crops';
import { formatEuro } from './money';
import { TILLAGE_SYSTEMS, tillageCostPerHa, type TillageSettings, type TillageSystem } from './tillage';

/**
 * Real production cost per hectare, in euro.
 *
 * Inputs (seed, fertiliser, crop protection, other costs and revenue) come
 * from the parcel's economic sheet for the season. Field operations come from
 * the work log: each one is costed for diesel, machine ownership and labour.
 * Operations the crop still needs are projected, so the cost per hectare is
 * realistic from the first day of the season, not only at harvest.
 */

export const ECONOMIC_CATEGORIES = ['SEEDS', 'FERTILIZERS', 'CHEMICALS', 'FUEL', 'MACHINERY', 'LABOR', 'OTHER'] as const;
export type EconomicCategory = (typeof ECONOMIC_CATEGORIES)[number];

export const ECONOMIC_LABELS: Record<EconomicCategory, string> = {
  SEEDS: 'Seeds',
  FERTILIZERS: 'Fertilisers',
  CHEMICALS: 'Crop protection',
  FUEL: 'Diesel',
  MACHINERY: 'Machinery',
  LABOR: 'Labour',
  OTHER: 'Other costs',
};

/** Expenses recorded by category land in the matching economic category. */
export const RECORDED_TO_ECONOMIC: Record<CostCategory, EconomicCategory> = {
  SEED: 'SEEDS',
  FERTILIZER: 'FERTILIZERS',
  PESTICIDE: 'CHEMICALS',
  FUEL: 'FUEL',
  LABOR: 'LABOR',
  IRRIGATION: 'OTHER',
  OTHER: 'OTHER',
};

export const DEFAULT_LABOR_RATE_EUR_H = 10;
/** Working hours in a month, used to turn a monthly salary into an hourly rate. */
export const HOURS_PER_MONTH = 168;

/** Typical ownership and upkeep per hour when the machine's own figures are missing. */
export const DEFAULT_MACHINE_COST_EUR_H: Record<string, number> = {
  TRACTOR: 35,
  COMBINE: 120,
  SPRAYER: 45,
  SEEDER: 20,
  DISC: 12,
  CULTIVATOR: 12,
  SUBSOILER: 14,
  TRAILER: 6,
};
const FALLBACK_MACHINE_COST_EUR_H = 35;

/** Typical diesel per hectare when neither the log nor the machine says more. */
export const DEFAULT_FUEL_L_PER_HA: Record<string, number> = {
  PLOWING: 20,
  DISCING: 9,
  SUBSOILING: 15,
  SOWING: 6,
  FERTILIZING: 2,
  SPRAYING: 1.5,
  HARVESTING: 16,
};

export const WORK_OPERATION_LABELS: Record<string, string> = {
  PLOWING: 'Ploughing',
  DISCING: 'Discing',
  SUBSOILING: 'Subsoiling',
  SOWING: 'Sowing',
  FERTILIZING: 'Fertilising',
  SPRAYING: 'Spraying',
  HARVESTING: 'Harvesting',
};

// ------------------------------------------------------------------ sheet

export type SeedUnit = 'SEEDS' | 'KG';

export interface SeedLine {
  id: string;
  supplier: string;
  hybrid: string;
  /** Seed category for cereals, for example Basic, C1 or C2. */
  category?: string | null;
  /** Corn and sunflower are sold by seeds per bag, cereals by kilograms. */
  unit: SeedUnit;
  /** Seeds or kilograms in one bag. */
  bagSize: number;
  /** Seeds or kilograms sown per hectare. */
  ratePerHa: number;
  pricePerBag: number;
  /** Bags actually used; when empty the need is computed from the rate. */
  bagsUsed?: number | null;
}

export interface FertilizerLine {
  id: string;
  product: string;
  ratePerHaKg: number;
  pricePerT: number;
  applications?: number;
  /** Nutrient content, in percent. */
  n?: number | null;
  p?: number | null;
  k?: number | null;
}

export const CHEMICAL_KINDS = ['HERBICIDE', 'FUNGICIDE', 'INSECTICIDE', 'GROWTH_REGULATOR'] as const;
export type ChemicalKind = (typeof CHEMICAL_KINDS)[number];
export const CHEMICAL_LABELS: Record<ChemicalKind, string> = {
  HERBICIDE: 'Herbicide',
  FUNGICIDE: 'Fungicide',
  INSECTICIDE: 'Insecticide',
  GROWTH_REGULATOR: 'Growth regulator',
};

export interface ChemicalLine {
  id: string;
  product: string;
  kind: ChemicalKind;
  dosePerHa: number;
  unit: 'L' | 'KG';
  pricePerUnit: number;
  applications?: number;
}

export interface MiscLine {
  id: string;
  label: string;
  amount: number;
  /** PER_HA multiplies by the area; TOTAL is the amount for the whole parcel. */
  basis: 'PER_HA' | 'TOTAL';
}

export interface RevenueInputs {
  expectedYieldTHa?: number | null;
  actualYieldTHa?: number | null;
  pricePerT?: number | null;
  /** The price actually obtained, when it differs from the planned one. */
  actualPricePerT?: number | null;
}

export interface EconomicSheet {
  seeds: SeedLine[];
  fertilizers: FertilizerLine[];
  chemicals: ChemicalLine[];
  misc: MiscLine[];
  revenue: RevenueInputs;
}

export const emptySheet = (): EconomicSheet => ({ seeds: [], fertilizers: [], chemicals: [], misc: [], revenue: {} });

// ------------------------------------------------------------------ settings

export interface MachineCostProfile {
  purchasePrice: number;
  /** Value left at the end of the depreciation period, in percent of the purchase price. */
  residualPct?: number;
  depreciationYears: number;
  annualHours: number;
  serviceCostPerYear?: number;
  maintenanceCostPerYear?: number;
}

export interface LaborProfile {
  monthlySalary?: number | null;
  hourlyRate?: number | null;
}

export interface EconomicSettings {
  fuelPricePerL: number;
  defaultLaborRate: number;
  labor: Record<string, LaborProfile>;
  machines: Record<string, MachineCostProfile>;
}

export const defaultSettings = (): EconomicSettings => ({
  fuelPricePerL: DEFAULT_FUEL_PRICE_EUR,
  defaultLaborRate: DEFAULT_LABOR_RATE_EUR_H,
  labor: {},
  machines: {},
});

// ------------------------------------------------------------------ line costs

const round2 = (value: number) => Math.round(value * 100) / 100;
const positive = (value: unknown, fallback = 0) => (typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : fallback);

export interface SeedCost {
  line: SeedLine;
  costPerHa: number;
  /** For seed sold by weight. */
  costPerKg: number | null;
  /** For seed sold by count. */
  costPer1000Seeds: number | null;
  bagsNeeded: number;
  total: number;
}

export function seedCost(line: SeedLine, areaHa: number): SeedCost {
  const bag = positive(line.bagSize, 1) || 1;
  const perUnit = positive(line.pricePerBag) / bag;
  const costPerHa = perUnit * positive(line.ratePerHa);
  const bagsNeeded = (positive(line.ratePerHa) * areaHa) / bag;
  const total = line.bagsUsed != null && line.bagsUsed >= 0 ? line.bagsUsed * positive(line.pricePerBag) : costPerHa * areaHa;
  return {
    line,
    costPerHa: round2(costPerHa),
    costPerKg: line.unit === 'KG' ? round2(perUnit) : null,
    costPer1000Seeds: line.unit === 'SEEDS' ? round2(perUnit * 1000) : null,
    bagsNeeded: Math.round(bagsNeeded * 10) / 10,
    total: round2(total),
  };
}

export interface FertilizerCost {
  line: FertilizerLine;
  pricePerKg: number;
  costPerHa: number;
  total: number;
  /** Nutrients applied, kg per hectare. */
  nutrients: { n: number; p: number; k: number };
}

export function fertilizerCost(line: FertilizerLine, areaHa: number): FertilizerCost {
  const applications = Math.max(1, positive(line.applications, 1));
  const kgPerHa = positive(line.ratePerHaKg) * applications;
  const pricePerKg = positive(line.pricePerT) / 1000;
  const costPerHa = kgPerHa * pricePerKg;
  return {
    line,
    pricePerKg: round2(pricePerKg * 1000) / 1000,
    costPerHa: round2(costPerHa),
    total: round2(costPerHa * areaHa),
    nutrients: {
      n: Math.round((kgPerHa * positive(line.n)) / 100),
      p: Math.round((kgPerHa * positive(line.p)) / 100),
      k: Math.round((kgPerHa * positive(line.k)) / 100),
    },
  };
}

export interface ChemicalCost {
  line: ChemicalLine;
  costPerHa: number;
  total: number;
  quantity: number;
}

export function chemicalCost(line: ChemicalLine, areaHa: number): ChemicalCost {
  const applications = Math.max(1, positive(line.applications, 1));
  const perHa = positive(line.dosePerHa) * positive(line.pricePerUnit) * applications;
  return {
    line,
    costPerHa: round2(perHa),
    total: round2(perHa * areaHa),
    quantity: Math.round(positive(line.dosePerHa) * applications * areaHa * 10) / 10,
  };
}

export const miscCost = (line: MiscLine, areaHa: number) => round2(line.basis === 'PER_HA' ? positive(line.amount) * areaHa : positive(line.amount));

// ------------------------------------------------------------------ machines and labour

export interface MachineHourlyCost {
  perHour: number;
  depreciation: number;
  service: number;
  maintenance: number;
  /** True when the typical figure for the machine type was used. */
  estimated: boolean;
}

export function machineHourlyCost(profile: MachineCostProfile | null | undefined, machineType?: string | null): MachineHourlyCost {
  if (!profile || !(profile.annualHours > 0) || !(profile.depreciationYears > 0)) {
    const perHour = DEFAULT_MACHINE_COST_EUR_H[machineType ?? ''] ?? FALLBACK_MACHINE_COST_EUR_H;
    return { perHour, depreciation: perHour, service: 0, maintenance: 0, estimated: true };
  }
  const residual = positive(profile.residualPct, 20) / 100;
  const depreciation = (positive(profile.purchasePrice) * (1 - residual)) / profile.depreciationYears / profile.annualHours;
  const service = positive(profile.serviceCostPerYear) / profile.annualHours;
  const maintenance = positive(profile.maintenanceCostPerYear) / profile.annualHours;
  return {
    perHour: round2(depreciation + service + maintenance),
    depreciation: round2(depreciation),
    service: round2(service),
    maintenance: round2(maintenance),
    estimated: false,
  };
}

export function laborHourlyRate(profile: LaborProfile | null | undefined, fallback = DEFAULT_LABOR_RATE_EUR_H): number {
  if (profile?.hourlyRate != null && profile.hourlyRate > 0) return profile.hourlyRate;
  if (profile?.monthlySalary != null && profile.monthlySalary > 0) return round2(profile.monthlySalary / HOURS_PER_MONTH);
  return fallback;
}

// ------------------------------------------------------------------ operations

export interface OperationInput {
  id: string;
  type: string;
  status: 'RUNNING' | 'PAUSED' | 'COMPLETED' | 'CANCELLED';
  /** Planned area of the operation. */
  areaHa: number;
  progressPct: number;
  /** Hours actually worked so far. */
  hours: number;
  fuelLiters?: number | null;
  machine?: { id: string; name?: string; type: string; fuelLPerH?: number | null } | null;
  operator?: { id: string; name?: string } | null;
  startedAt: string;
}

export interface OperationCost {
  id: string;
  type: string;
  label: string;
  machineName: string | null;
  operatorName: string | null;
  areaHa: number;
  hours: number;
  fuelL: number;
  fuelCost: number;
  machineCost: number;
  laborCost: number;
  total: number;
  costPerHa: number;
  /** Projected operations are not in the work log yet. */
  projected: boolean;
  /** Diesel or machine figures came from typical values. */
  estimated: boolean;
  startedAt: string | null;
}

/** Diesel per hectare for an operation, following the tillage technology when one is set. */
export function fuelPerHaFor(type: string, tillage: TillageSystem | null | undefined): number {
  const typical = DEFAULT_FUEL_L_PER_HA[type] ?? 5;
  if (!tillage) return typical;
  const profile = TILLAGE_SYSTEMS[tillage];
  const tillageTypes = profile.operations.map((operation) => operation.workType as string);
  if (!tillageTypes.includes(type)) return typical;
  // The technology's diesel per hectare is shared between its operations by their typical weight.
  const weight = (name: string) => DEFAULT_FUEL_L_PER_HA[name] ?? 5;
  const total = tillageTypes.reduce((sum, name) => sum + weight(name), 0);
  return Math.round(((profile.fuelLPerHa * weight(type)) / total) * 10) / 10;
}

function priceOperation(
  base: { id: string; type: string; areaHa: number; hours: number; fuelL: number; machineType: string | null; machineId: string | null; operatorId: string | null },
  settings: EconomicSettings,
  extra: Pick<OperationCost, 'machineName' | 'operatorName' | 'projected' | 'estimated' | 'startedAt'>,
): OperationCost {
  const machine = machineHourlyCost(base.machineId ? settings.machines[base.machineId] : null, base.machineType ?? 'TRACTOR');
  const labor = laborHourlyRate(base.operatorId ? settings.labor[base.operatorId] : null, settings.defaultLaborRate);
  const fuelCost = base.fuelL * settings.fuelPricePerL;
  const machineCost = machine.perHour * base.hours;
  const laborCost = labor * base.hours;
  const total = fuelCost + machineCost + laborCost;
  return {
    id: base.id,
    type: base.type,
    label: WORK_OPERATION_LABELS[base.type] ?? base.type,
    areaHa: round2(base.areaHa),
    hours: Math.round(base.hours * 10) / 10,
    fuelL: Math.round(base.fuelL),
    fuelCost: round2(fuelCost),
    machineCost: round2(machineCost),
    laborCost: round2(laborCost),
    total: round2(total),
    costPerHa: base.areaHa > 0 ? round2(total / base.areaHa) : 0,
    ...extra,
    estimated: extra.estimated || machine.estimated,
  };
}

/** Prices one logged operation: diesel, machine hours and operator hours. */
export function costOperation(op: OperationInput, settings: EconomicSettings, tillage?: TillageSystem | null): OperationCost {
  const areaHa = (op.areaHa * Math.min(100, Math.max(0, op.progressPct))) / 100;
  const hours = Math.max(0, op.hours);
  const loggedFuel = op.fuelLiters != null && op.fuelLiters > 0 ? op.fuelLiters : null;
  const machineFuel = op.machine?.fuelLPerH ? op.machine.fuelLPerH * hours : null;
  const fuelL = loggedFuel ?? machineFuel ?? fuelPerHaFor(op.type, tillage) * areaHa;
  return priceOperation(
    { id: op.id, type: op.type, areaHa, hours, fuelL, machineType: op.machine?.type ?? null, machineId: op.machine?.id ?? null, operatorId: op.operator?.id ?? null },
    settings,
    {
      machineName: op.machine?.name ?? null,
      operatorName: op.operator?.name ?? null,
      projected: false,
      estimated: loggedFuel == null && machineFuel == null,
      startedAt: op.startedAt,
    },
  );
}

/** Field capacity of an operation from the implement catalog, in hectares per hour. */
function typicalCapacityHaH(type: string): number {
  if (type === 'HARVESTING') return (6 * 6 * 0.7) / 10;
  const implement = IMPLEMENT_CATALOG.find((item) => item.operation === type);
  if (!implement) return 2;
  const width = implement.widthsM[Math.floor((implement.widthsM.length - 1) / 2)]!;
  return (width * implement.speedKmh * (implement.efficiencyPct / 100)) / 10;
}

/** The operations a season needs: the technology's tillage and sowing, fertilising, spraying and harvest. */
export function seasonOperations(sheet: EconomicSheet, tillage: TillageSystem | null | undefined, crop: CropCode | null | undefined): string[] {
  const types: string[] = tillage ? TILLAGE_SYSTEMS[tillage].operations.map((operation) => operation.workType) : ['DISCING', 'SOWING'];
  const passes = (lines: { applications?: number }[]) => lines.reduce((max, line) => Math.max(max, Math.max(1, line.applications ?? 1)), 0);
  const fertilizing = passes(sheet.fertilizers) - types.filter((type) => type === 'FERTILIZING').length;
  for (let i = 0; i < fertilizing; i += 1) types.push('FERTILIZING');
  const spraying = sheet.chemicals.reduce((sum, line) => sum + Math.max(1, line.applications ?? 1), 0);
  for (let i = 0; i < spraying; i += 1) types.push('SPRAYING');
  if (crop) types.push('HARVESTING');
  return types;
}

/** Estimates the operations still to come, with the typical machine, diesel and labour. */
export function projectOperations(
  needed: string[],
  logged: readonly OperationInput[],
  areaHa: number,
  settings: EconomicSettings,
  tillage?: TillageSystem | null,
): OperationCost[] {
  const done = new Map<string, number>();
  for (const op of logged) {
    if (op.status === 'CANCELLED') continue;
    done.set(op.type, (done.get(op.type) ?? 0) + Math.min(1, op.progressPct / 100));
  }
  const projected: OperationCost[] = [];
  needed.forEach((type, index) => {
    const covered = done.get(type) ?? 0;
    const share = Math.max(0, Math.min(1, 1 - covered));
    done.set(type, Math.max(0, covered - 1));
    if (share <= 0.01) return;
    const area = areaHa * share;
    const hours = area / typicalCapacityHaH(type);
    projected.push(
      priceOperation(
        { id: `projected-${index}-${type}`, type, areaHa: area, hours, fuelL: fuelPerHaFor(type, tillage) * area, machineType: type === 'HARVESTING' ? 'COMBINE' : 'TRACTOR', machineId: null, operatorId: null },
        settings,
        { machineName: null, operatorName: null, projected: true, estimated: true, startedAt: null },
      ),
    );
  });
  return projected;
}

// ------------------------------------------------------------------ parcel

export interface CostTotals {
  byCategory: Record<EconomicCategory, number>;
  total: number;
  perHa: number;
}

export interface ProfitResult {
  yieldTHa: number;
  pricePerT: number;
  revenue: number;
  revenuePerHa: number;
  profit: number;
  profitPerHa: number;
  marginPct: number | null;
}

export interface ParcelEconomicsInput {
  areaHa: number;
  sheet: EconomicSheet;
  operations: readonly OperationInput[];
  settings: EconomicSettings;
  tillage?: TillageSystem | null;
  crop?: CropCode | null;
  /** Expenses recorded by category, already in euro. */
  recorded?: readonly { category: CostCategory; amount: number }[];
}

export interface ParcelEconomicsResult {
  areaHa: number;
  seeds: SeedCost[];
  fertilizers: FertilizerCost[];
  chemicals: ChemicalCost[];
  misc: { line: MiscLine; total: number }[];
  operations: OperationCost[];
  projected: OperationCost[];
  recordedTotal: number;
  /** Inputs, finished work and recorded expenses: what has been spent so far. */
  toDate: CostTotals;
  /** Plus the operations still to come: the realistic cost of the season. */
  season: CostTotals;
  expected: ProfitResult | null;
  actual: ProfitResult | null;
}

const emptyCategories = () => Object.fromEntries(ECONOMIC_CATEGORIES.map((category) => [category, 0])) as Record<EconomicCategory, number>;

function totals(byCategory: Record<EconomicCategory, number>, areaHa: number): CostTotals {
  const rounded = Object.fromEntries(ECONOMIC_CATEGORIES.map((category) => [category, round2(byCategory[category])])) as Record<EconomicCategory, number>;
  const total = round2(ECONOMIC_CATEGORIES.reduce((sum, category) => sum + byCategory[category], 0));
  return { byCategory: rounded, total, perHa: areaHa > 0 ? round2(total / areaHa) : 0 };
}

function profit(areaHa: number, yieldTHa: number | null | undefined, pricePerT: number | null | undefined, cost: number): ProfitResult | null {
  if (yieldTHa == null || pricePerT == null || !(yieldTHa > 0) || !(pricePerT > 0)) return null;
  const revenue = yieldTHa * pricePerT * areaHa;
  const result = revenue - cost;
  return {
    yieldTHa,
    pricePerT,
    revenue: round2(revenue),
    revenuePerHa: round2(yieldTHa * pricePerT),
    profit: round2(result),
    profitPerHa: areaHa > 0 ? round2(result / areaHa) : 0,
    marginPct: revenue > 0 ? Math.round((result / revenue) * 1000) / 10 : null,
  };
}

export function computeParcelEconomics(input: ParcelEconomicsInput): ParcelEconomicsResult {
  const { areaHa, sheet, settings } = input;
  const seeds = sheet.seeds.map((line) => seedCost(line, areaHa));
  const fertilizers = sheet.fertilizers.map((line) => fertilizerCost(line, areaHa));
  const chemicals = sheet.chemicals.map((line) => chemicalCost(line, areaHa));
  const misc = sheet.misc.map((line) => ({ line, total: miscCost(line, areaHa) }));
  const logged = input.operations.filter((op) => op.status !== 'CANCELLED' || op.progressPct > 0);
  const operations = logged.map((op) => costOperation(op, settings, input.tillage));
  const projected = projectOperations(seasonOperations(sheet, input.tillage, input.crop), logged, areaHa, settings, input.tillage);

  const spent = emptyCategories();
  spent.SEEDS += seeds.reduce((sum, item) => sum + item.total, 0);
  spent.FERTILIZERS += fertilizers.reduce((sum, item) => sum + item.total, 0);
  spent.CHEMICALS += chemicals.reduce((sum, item) => sum + item.total, 0);
  spent.OTHER += misc.reduce((sum, item) => sum + item.total, 0);
  for (const op of operations) {
    spent.FUEL += op.fuelCost;
    spent.MACHINERY += op.machineCost;
    spent.LABOR += op.laborCost;
  }
  let recordedTotal = 0;
  for (const entry of input.recorded ?? []) {
    spent[RECORDED_TO_ECONOMIC[entry.category]] += entry.amount;
    recordedTotal += entry.amount;
  }
  const season = { ...spent };
  for (const op of projected) {
    season.FUEL += op.fuelCost;
    season.MACHINERY += op.machineCost;
    season.LABOR += op.laborCost;
  }
  const toDate = totals(spent, areaHa);
  const seasonTotals = totals(season, areaHa);
  const revenue = sheet.revenue;
  return {
    areaHa,
    seeds,
    fertilizers,
    chemicals,
    misc,
    operations,
    projected,
    recordedTotal: round2(recordedTotal),
    toDate,
    season: seasonTotals,
    expected: profit(areaHa, revenue.expectedYieldTHa, revenue.pricePerT, seasonTotals.total),
    actual: profit(areaHa, revenue.actualYieldTHa, revenue.actualPricePerT ?? revenue.pricePerT, seasonTotals.total),
  };
}

// ------------------------------------------------------------------ farm

export interface FarmParcelEconomics {
  parcelId: string;
  name: string;
  crop: CropCode | null;
  cropName: string | null;
  tillage: TillageSystem | null;
  result: ParcelEconomicsResult;
}

export interface EconomicGroup {
  key: string;
  label: string;
  areaHa: number;
  cost: number;
  costPerHa: number;
  fuelL: number;
  fuelLPerHa: number;
  passes: number;
  expectedProfit: number | null;
  expectedProfitPerHa: number | null;
  actualProfit: number | null;
  actualProfitPerHa: number | null;
}

export interface FarmEconomics {
  areaHa: number;
  cost: number;
  costPerHa: number;
  byCategory: Record<EconomicCategory, number>;
  expectedProfit: number | null;
  actualProfit: number | null;
  /** Best available profit per hectare: actual where known, otherwise expected. */
  profitPerHa: number | null;
  byParcel: EconomicGroup[];
  byCrop: EconomicGroup[];
  byTillage: EconomicGroup[];
  mostProfitableParcel: EconomicGroup | null;
  mostProfitableCrop: EconomicGroup | null;
  mostEfficientTillage: EconomicGroup | null;
}

function group(key: string, label: string, items: readonly FarmParcelEconomics[]): EconomicGroup {
  const areaHa = items.reduce((sum, item) => sum + item.result.areaHa, 0);
  const cost = items.reduce((sum, item) => sum + item.result.season.total, 0);
  const fuelL = items.reduce((sum, item) => sum + [...item.result.operations, ...item.result.projected].reduce((total, op) => total + op.fuelL, 0), 0);
  const passes = areaHa > 0 ? items.reduce((sum, item) => sum + (item.result.operations.length + item.result.projected.length) * item.result.areaHa, 0) / areaHa : 0;
  const sumOf = (pick: (item: FarmParcelEconomics) => ProfitResult | null) => {
    const known = items.map(pick).filter((value): value is ProfitResult => value !== null);
    return known.length ? known.reduce((sum, value) => sum + value.profit, 0) : null;
  };
  const areaOf = (pick: (item: FarmParcelEconomics) => ProfitResult | null) => items.filter((item) => pick(item) !== null).reduce((sum, item) => sum + item.result.areaHa, 0);
  const expectedProfit = sumOf((item) => item.result.expected);
  const actualProfit = sumOf((item) => item.result.actual);
  const expectedArea = areaOf((item) => item.result.expected);
  const actualArea = areaOf((item) => item.result.actual);
  return {
    key,
    label,
    areaHa: round2(areaHa),
    cost: round2(cost),
    costPerHa: areaHa > 0 ? round2(cost / areaHa) : 0,
    fuelL: Math.round(fuelL),
    fuelLPerHa: areaHa > 0 ? Math.round((fuelL / areaHa) * 10) / 10 : 0,
    passes: Math.round(passes * 10) / 10,
    expectedProfit: expectedProfit === null ? null : round2(expectedProfit),
    expectedProfitPerHa: expectedProfit === null || expectedArea === 0 ? null : round2(expectedProfit / expectedArea),
    actualProfit: actualProfit === null ? null : round2(actualProfit),
    actualProfitPerHa: actualProfit === null || actualArea === 0 ? null : round2(actualProfit / actualArea),
  };
}

const bestProfitPerHa = (item: EconomicGroup) => item.actualProfitPerHa ?? item.expectedProfitPerHa;

function groupBy(parcels: readonly FarmParcelEconomics[], keyOf: (item: FarmParcelEconomics) => [string, string] | null): EconomicGroup[] {
  const groups = new Map<string, { label: string; items: FarmParcelEconomics[] }>();
  for (const item of parcels) {
    const key = keyOf(item);
    if (!key) continue;
    const entry = groups.get(key[0]) ?? { label: key[1], items: [] };
    entry.items.push(item);
    groups.set(key[0], entry);
  }
  return [...groups.entries()].map(([key, entry]) => group(key, entry.label, entry.items)).sort((a, b) => b.cost - a.cost);
}

function best(groups: EconomicGroup[], score: (item: EconomicGroup) => number | null): EconomicGroup | null {
  let winner: EconomicGroup | null = null;
  let top = -Infinity;
  for (const item of groups) {
    const value = score(item);
    if (value !== null && value > top) {
      top = value;
      winner = item;
    }
  }
  return winner;
}

export function summarizeFarmEconomics(parcels: readonly FarmParcelEconomics[]): FarmEconomics {
  const all = group('farm', 'Farm', parcels);
  const byCategory = emptyCategories();
  for (const item of parcels) for (const category of ECONOMIC_CATEGORIES) byCategory[category] += item.result.season.byCategory[category];
  const byParcel = parcels.map((item) => group(item.parcelId, item.name, [item])).sort((a, b) => b.cost - a.cost);
  const byCrop = groupBy(parcels, (item) => (item.crop ? [item.crop, item.cropName ?? item.crop] : ['NONE', 'No crop']));
  const byTillage = groupBy(parcels, (item) => (item.tillage ? [item.tillage, TILLAGE_SYSTEMS[item.tillage].name] : null));
  // The most efficient technology earns the most per hectare; without revenue data, the one that costs the least.
  const efficient = best(byTillage, (item) => bestProfitPerHa(item)) ?? best(byTillage, (item) => (item.areaHa > 0 ? -item.costPerHa : null));
  return {
    areaHa: all.areaHa,
    cost: all.cost,
    costPerHa: all.costPerHa,
    byCategory: Object.fromEntries(ECONOMIC_CATEGORIES.map((category) => [category, round2(byCategory[category])])) as Record<EconomicCategory, number>,
    expectedProfit: all.expectedProfit,
    actualProfit: all.actualProfit,
    profitPerHa: bestProfitPerHa(all),
    byParcel,
    byCrop: byCrop.filter((item) => item.key !== 'NONE' || item.areaHa > 0),
    byTillage,
    mostProfitableParcel: best(byParcel, bestProfitPerHa),
    mostProfitableCrop: best(byCrop.filter((item) => item.key !== 'NONE'), bestProfitPerHa),
    mostEfficientTillage: efficient,
  };
}

// ------------------------------------------------------------------ advice

export interface EconomicInsight {
  tone: 'info' | 'warning' | 'success';
  message: string;
  /** Estimated saving for the parcel, in euro, when the advice is followed. */
  savings?: number;
}

/** Plain-language findings: where the money goes and what could be cheaper. */
export function economicInsights(
  result: ParcelEconomicsResult,
  context: { tillage?: TillageSystem | null; tillageSettings?: TillageSettings } = {},
): EconomicInsight[] {
  const insights: EconomicInsight[] = [];
  const { season } = result;
  if (season.total <= 0) return [{ tone: 'info', message: 'Add seed, fertiliser and crop protection to see the real cost per hectare.' }];

  // The largest category, when it dominates.
  const [topCategory, topValue] = ECONOMIC_CATEGORIES.map((category) => [category, season.byCategory[category]] as const).sort((a, b) => b[1] - a[1])[0]!;
  const share = Math.round((topValue / season.total) * 100);
  if (share >= 25) insights.push({ tone: share >= 40 ? 'warning' : 'info', message: `${ECONOMIC_LABELS[topCategory]} is ${share}% of the total production cost.` });

  // The most expensive field operation.
  const operations = [...result.operations, ...result.projected];
  const priciest = operations.slice().sort((a, b) => b.costPerHa - a.costPerHa)[0];
  if (priciest && priciest.costPerHa > 0) {
    insights.push({ tone: 'info', message: `${priciest.label} is the most expensive operation: ${formatEuro(priciest.costPerHa, 2)}/ha${priciest.projected ? ' (estimated)' : ''}.` });
  }

  // A cheaper tillage technology.
  if (context.tillage) {
    const order: TillageSystem[] = ['CONVENTIONAL', 'MINIMUM_TILL', 'STRIP_TILL', 'NO_TILL'];
    const next = order[order.indexOf(context.tillage) + 1];
    if (next) {
      const current = tillageCostPerHa(context.tillage, context.tillageSettings);
      const alternative = tillageCostPerHa(next, context.tillageSettings);
      const pct = current > 0 ? Math.round(((current - alternative) / current) * 100) : 0;
      if (pct > 0) {
        insights.push({
          tone: 'success',
          message: `Applying ${TILLAGE_SYSTEMS[next].name} on this parcel can reduce costs by about ${pct}%.`,
          savings: round2((season.total * pct) / 100),
        });
      }
    }
  } else {
    insights.push({ tone: 'info', message: 'Set the tillage technology to compare the cost of Minimum Till, Strip Till and No Till.' });
  }

  const fuelPerHa = result.areaHa > 0 ? season.byCategory.FUEL / result.areaHa : 0;
  if (fuelPerHa > 90) insights.push({ tone: 'warning', message: `Diesel costs ${formatEuro(fuelPerHa, 0)}/ha. Fewer passes or combined operations would lower it.` });

  const fertPerHa = result.areaHa > 0 ? season.byCategory.FERTILIZERS / result.areaHa : 0;
  if (fertPerHa > 300) insights.push({ tone: 'warning', message: `Fertiliser costs ${formatEuro(fertPerHa, 0)}/ha. A soil analysis can show where the dose can be reduced.` });

  const outlook = result.actual ?? result.expected;
  if (outlook) {
    if (outlook.profit < 0) {
      const breakEven = outlook.yieldTHa > 0 ? season.perHa / outlook.yieldTHa : null;
      insights.push({
        tone: 'warning',
        message: `The season runs at a loss of ${formatEuro(-outlook.profitPerHa, 0)}/ha.${breakEven ? ` It breaks even at ${formatEuro(breakEven, 0)}/t.` : ''}`,
      });
    } else {
      insights.push({ tone: 'success', message: `Profit of ${formatEuro(outlook.profitPerHa, 0)}/ha, a ${outlook.marginPct ?? 0}% margin.` });
    }
  } else {
    insights.push({ tone: 'info', message: 'Enter the expected yield and the sale price to see the profit.' });
  }
  return insights;
}

// ------------------------------------------------------------------ stored documents

const num = (value: unknown): number | undefined => (typeof value === 'number' && Number.isFinite(value) ? value : undefined);
const str = (value: unknown, max = 120) => (typeof value === 'string' ? value.slice(0, max) : '');
const id = (value: unknown, index: number) => (typeof value === 'string' && value ? value.slice(0, 64) : `line-${index}`);
const list = <T>(value: unknown, map: (item: Record<string, unknown>, index: number) => T | null): T[] =>
  Array.isArray(value) ? value.slice(0, 50).map((item, index) => (item && typeof item === 'object' ? map(item as Record<string, unknown>, index) : null)).filter((item): item is T => item !== null) : [];

/** Reads a stored sheet, keeping only well-formed lines. */
export function parseSheet(raw: unknown): EconomicSheet {
  const data = (raw && typeof raw === 'object' ? raw : {}) as Record<string, unknown>;
  const revenue = (data.revenue && typeof data.revenue === 'object' ? data.revenue : {}) as Record<string, unknown>;
  return {
    seeds: list(data.seeds, (item, index) => ({
      id: id(item.id, index),
      supplier: str(item.supplier),
      hybrid: str(item.hybrid),
      category: typeof item.category === 'string' ? str(item.category, 40) : null,
      unit: item.unit === 'KG' ? 'KG' : 'SEEDS',
      bagSize: num(item.bagSize) ?? 1,
      ratePerHa: num(item.ratePerHa) ?? 0,
      pricePerBag: num(item.pricePerBag) ?? 0,
      bagsUsed: num(item.bagsUsed) ?? null,
    })),
    fertilizers: list(data.fertilizers, (item, index) => ({
      id: id(item.id, index),
      product: str(item.product),
      ratePerHaKg: num(item.ratePerHaKg) ?? 0,
      pricePerT: num(item.pricePerT) ?? 0,
      applications: num(item.applications) ?? 1,
      n: num(item.n) ?? null,
      p: num(item.p) ?? null,
      k: num(item.k) ?? null,
    })),
    chemicals: list(data.chemicals, (item, index) => ({
      id: id(item.id, index),
      product: str(item.product),
      kind: (CHEMICAL_KINDS as readonly string[]).includes(item.kind as string) ? (item.kind as ChemicalKind) : 'HERBICIDE',
      dosePerHa: num(item.dosePerHa) ?? 0,
      unit: item.unit === 'KG' ? 'KG' : 'L',
      pricePerUnit: num(item.pricePerUnit) ?? 0,
      applications: num(item.applications) ?? 1,
    })),
    misc: list(data.misc, (item, index) => ({
      id: id(item.id, index),
      label: str(item.label),
      amount: num(item.amount) ?? 0,
      basis: item.basis === 'PER_HA' ? 'PER_HA' : 'TOTAL',
    })),
    revenue: {
      expectedYieldTHa: num(revenue.expectedYieldTHa) ?? null,
      actualYieldTHa: num(revenue.actualYieldTHa) ?? null,
      pricePerT: num(revenue.pricePerT) ?? null,
      actualPricePerT: num(revenue.actualPricePerT) ?? null,
    },
  };
}

/** Reads stored settings, filling in the defaults. */
export function parseSettings(raw: unknown): EconomicSettings {
  const data = (raw && typeof raw === 'object' ? raw : {}) as Record<string, unknown>;
  const record = <T>(value: unknown, map: (item: Record<string, unknown>) => T): Record<string, T> =>
    value && typeof value === 'object'
      ? Object.fromEntries(Object.entries(value as Record<string, unknown>).slice(0, 500).filter(([, item]) => item && typeof item === 'object').map(([key, item]) => [key, map(item as Record<string, unknown>)]))
      : {};
  return {
    fuelPricePerL: num(data.fuelPricePerL) ?? DEFAULT_FUEL_PRICE_EUR,
    defaultLaborRate: num(data.defaultLaborRate) ?? DEFAULT_LABOR_RATE_EUR_H,
    labor: record(data.labor, (item) => ({ monthlySalary: num(item.monthlySalary) ?? null, hourlyRate: num(item.hourlyRate) ?? null })),
    machines: record(data.machines, (item) => ({
      purchasePrice: num(item.purchasePrice) ?? 0,
      residualPct: num(item.residualPct) ?? 20,
      depreciationYears: num(item.depreciationYears) ?? 10,
      annualHours: num(item.annualHours) ?? 500,
      serviceCostPerYear: num(item.serviceCostPerYear) ?? 0,
      maintenanceCostPerYear: num(item.maintenanceCostPerYear) ?? 0,
    })),
  };
}

// ------------------------------------------------------------------ presets

export const SEED_SUPPLIERS: Partial<Record<CropCode, string[]>> = {
  CORN: ['Pioneer', 'Dekalb', 'KWS', 'Syngenta', 'Limagrain'],
  SUNFLOWER: ['Corteva', 'Syngenta', 'LG', 'Euralis', 'Pioneer'],
  WHEAT: ['Own seed', 'INCDA Fundulea', 'Saaten Union', 'RAGT', 'Limagrain'],
  BARLEY: ['Own seed', 'Saaten Union', 'KWS', 'Limagrain'],
  RAPESEED: ['Dekalb', 'Pioneer', 'KWS', 'LG'],
  SOY: ['Pioneer', 'Euralis', 'Saatbau', 'Agroser'],
};

/** Sensible starting values when adding seed for a crop. */
export const SEED_DEFAULTS: Partial<Record<CropCode, Pick<SeedLine, 'unit' | 'bagSize' | 'ratePerHa' | 'pricePerBag'>>> = {
  CORN: { unit: 'SEEDS', bagSize: 80_000, ratePerHa: 70_000, pricePerBag: 180 },
  SUNFLOWER: { unit: 'SEEDS', bagSize: 150_000, ratePerHa: 60_000, pricePerBag: 210 },
  RAPESEED: { unit: 'SEEDS', bagSize: 1_500_000, ratePerHa: 500_000, pricePerBag: 160 },
  SOY: { unit: 'SEEDS', bagSize: 150_000, ratePerHa: 550_000, pricePerBag: 70 },
  WHEAT: { unit: 'KG', bagSize: 25, ratePerHa: 220, pricePerBag: 18 },
  BARLEY: { unit: 'KG', bagSize: 25, ratePerHa: 190, pricePerBag: 16 },
};

export const SEED_CATEGORIES = ['Basic', 'C1', 'C2', 'Certified', 'Own seed'];

export const FERTILIZER_PRESETS: Omit<FertilizerLine, 'id'>[] = [
  { product: 'Urea 46%', ratePerHaKg: 150, pricePerT: 430, n: 46, p: 0, k: 0 },
  { product: 'Ammonium nitrate 34%', ratePerHaKg: 200, pricePerT: 380, n: 34, p: 0, k: 0 },
  { product: 'DAP 18-46-0', ratePerHaKg: 150, pricePerT: 650, n: 18, p: 46, k: 0 },
  { product: 'NPK 15-15-15', ratePerHaKg: 250, pricePerT: 520, n: 15, p: 15, k: 15 },
  { product: 'Ammonium sulphate', ratePerHaKg: 150, pricePerT: 260, n: 21, p: 0, k: 0 },
  { product: 'Calcium ammonium nitrate 27%', ratePerHaKg: 200, pricePerT: 340, n: 27, p: 0, k: 0 },
];

export const CHEMICAL_PRESETS: Omit<ChemicalLine, 'id'>[] = [
  { product: 'Adengo', kind: 'HERBICIDE', dosePerHa: 0.44, unit: 'L', pricePerUnit: 95 },
  { product: 'Laudis', kind: 'HERBICIDE', dosePerHa: 2, unit: 'L', pricePerUnit: 26 },
  { product: 'Elumis', kind: 'HERBICIDE', dosePerHa: 1.5, unit: 'L', pricePerUnit: 32 },
  { product: 'Pictor', kind: 'FUNGICIDE', dosePerHa: 0.5, unit: 'L', pricePerUnit: 78 },
  { product: 'Amistar', kind: 'FUNGICIDE', dosePerHa: 1, unit: 'L', pricePerUnit: 48 },
  { product: 'Karate Zeon', kind: 'INSECTICIDE', dosePerHa: 0.15, unit: 'L', pricePerUnit: 60 },
  { product: 'Moddus', kind: 'GROWTH_REGULATOR', dosePerHa: 0.4, unit: 'L', pricePerUnit: 55 },
];
