import type { CropCode } from './crops';

/**
 * Soil tillage technologies. Each one is a plain profile in `TILLAGE_SYSTEMS`:
 * a new technology (for example vertical tillage or ridge till) is added there,
 * and every calculation, warning and chart below picks it up.
 */

export const TILLAGE_CODES = ['CONVENTIONAL', 'MINIMUM_TILL', 'STRIP_TILL', 'NO_TILL'] as const;
export type TillageSystem = (typeof TILLAGE_CODES)[number];

/** Work types known to the work log (same values as the server enum). */
export type TillageWorkType = 'PLOWING' | 'DISCING' | 'SUBSOILING' | 'SOWING' | 'FERTILIZING' | 'SPRAYING' | 'HARVESTING';

/** Machine types known to the fleet (same values as the server enum). */
export type TillageMachineType = 'TRACTOR' | 'COMBINE' | 'SPRAYER' | 'SEEDER' | 'DISC' | 'CULTIVATOR' | 'SUBSOILER' | 'TRAILER';

export interface TillageOperation {
  key: string;
  label: string;
  /** The work log type used when the operation is started. */
  workType: TillageWorkType;
  /** The task type used when the operation is planned. */
  taskType: string;
  /** Days after the first operation, used to space the generated tasks. */
  offsetDays: number;
}

export interface TillageProfile {
  code: TillageSystem;
  name: string;
  /** Short map badge. */
  badge: string;
  /** Categorical colour, validated for colour-blind separation on the app's dark surfaces. */
  color: string;
  description: string;
  /** Default economics, editable by the user. */
  costPerHa: number;
  fuelLPerHa: number;
  /** Soil health score, 0-100. */
  soilHealth: number;
  /** Water retention, 1-5 stars. */
  waterRetention: number;
  /** Extra water held compared with conventional tillage, in percent. */
  waterGainPct: number;
  /** Machine passes over the field in a season, up to sowing. */
  passes: number;
  operations: TillageOperation[];
  /** Work types that contradict the technology, with the reason shown to the user. */
  discouragedWork: Partial<Record<TillageWorkType, string>>;
  /** Task types that contradict the technology. */
  discouragedTasks: Partial<Record<string, string>>;
}

const op = (key: string, label: string, workType: TillageWorkType, taskType: string, offsetDays: number): TillageOperation => ({
  key,
  label,
  workType,
  taskType,
  offsetDays,
});

export const TILLAGE_SYSTEMS: Record<TillageSystem, TillageProfile> = {
  CONVENTIONAL: {
    code: 'CONVENTIONAL',
    name: 'Conventional Tillage',
    badge: 'CONV',
    color: '#DE4467',
    description: 'Full inversion with the plough, followed by secondary tillage.',
    costPerHa: 520,
    fuelLPerHa: 60,
    soilHealth: 60,
    waterRetention: 2,
    waterGainPct: 0,
    passes: 4,
    operations: [
      op('plowing', 'Ploughing', 'PLOWING', 'SOIL_PREP', 0),
      op('discing', 'Discing', 'DISCING', 'SOIL_PREP', 10),
      op('seedbed', 'Seedbed preparation', 'DISCING', 'SOIL_PREP', 18),
      op('sowing', 'Sowing', 'SOWING', 'SOWING', 21),
    ],
    discouragedWork: {},
    discouragedTasks: {},
  },
  MINIMUM_TILL: {
    code: 'MINIMUM_TILL',
    name: 'Minimum Till',
    badge: 'MIN',
    color: '#B48A10',
    description: 'Loosening without inversion; residues stay near the surface.',
    costPerHa: 420,
    fuelLPerHa: 35,
    soilHealth: 75,
    waterRetention: 4,
    waterGainPct: 15,
    passes: 3,
    operations: [
      op('subsoiling', 'Subsoiling', 'SUBSOILING', 'SOIL_PREP', 0),
      op('chiselling', 'Chiselling', 'SUBSOILING', 'SOIL_PREP', 7),
      op('shallow-discing', 'Shallow discing', 'DISCING', 'SOIL_PREP', 14),
      op('sowing', 'Sowing', 'SOWING', 'SOWING', 18),
    ],
    discouragedWork: { PLOWING: 'Ploughing inverts the soil and cancels the benefits of Minimum Till.' },
    discouragedTasks: {},
  },
  STRIP_TILL: {
    code: 'STRIP_TILL',
    name: 'Strip Till',
    badge: 'STRIP',
    color: '#3A8FD0',
    description: 'Only the seed row strips are tilled and fertilised.',
    costPerHa: 350,
    fuelLPerHa: 25,
    soilHealth: 82,
    waterRetention: 4,
    waterGainPct: 18,
    passes: 2,
    operations: [
      op('strip-prep', 'Strip preparation', 'SUBSOILING', 'SOIL_PREP', 0),
      op('band-fertilizing', 'Band fertilisation', 'FERTILIZING', 'FERTILIZATION', 0),
      op('strip-sowing', 'Strip sowing', 'SOWING', 'SOWING', 10),
    ],
    discouragedWork: {
      PLOWING: 'Ploughing destroys the untilled strips between rows.',
      DISCING: 'Full-width discing works the strips that Strip Till keeps covered.',
    },
    discouragedTasks: {},
  },
  NO_TILL: {
    code: 'NO_TILL',
    name: 'No Till',
    badge: 'NO-TILL',
    color: '#58A23A',
    description: 'Direct sowing into the residue of the previous crop.',
    costPerHa: 250,
    fuelLPerHa: 12,
    soilHealth: 90,
    waterRetention: 5,
    waterGainPct: 20,
    passes: 1,
    operations: [op('direct-sowing', 'Direct sowing', 'SOWING', 'SOWING', 0)],
    discouragedWork: {
      PLOWING: 'No Till does not plough. Ploughing breaks the soil structure built up over years.',
      DISCING: 'No Till does not disc. Discing buries the residue that protects the soil.',
      SUBSOILING: 'No Till does not loosen the soil before sowing.',
    },
    discouragedTasks: { SOIL_PREP: 'No Till skips seedbed preparation; sow directly into the residue.' },
  },
};

export const TILLAGE_LIST: TillageProfile[] = TILLAGE_CODES.map((code) => TILLAGE_SYSTEMS[code]);
/** Savings are measured against this technology. */
export const TILLAGE_BASELINE: TillageSystem = 'CONVENTIONAL';

export const isTillageSystem = (value: unknown): value is TillageSystem =>
  typeof value === 'string' && (TILLAGE_CODES as readonly string[]).includes(value);

export const tillageProfile = (system: TillageSystem | null | undefined) => (system ? TILLAGE_SYSTEMS[system] : null);

// ------------------------------------------------------------------ settings

/** The user's own cost and fuel figures per technology. Missing values use the defaults. */
export type TillageSettings = Partial<Record<TillageSystem, { costPerHa?: number; fuelLPerHa?: number }>>;

export const tillageCostPerHa = (system: TillageSystem, settings: TillageSettings = {}) =>
  settings[system]?.costPerHa ?? TILLAGE_SYSTEMS[system].costPerHa;

export const tillageFuelPerHa = (system: TillageSystem, settings: TillageSettings = {}) =>
  settings[system]?.fuelLPerHa ?? TILLAGE_SYSTEMS[system].fuelLPerHa;

// ------------------------------------------------------------------ one parcel

export interface ParcelTillageEstimate {
  system: TillageSystem;
  areaHa: number;
  costPerHa: number;
  totalCost: number;
  /** Savings compared with conventional tillage, for the whole parcel. Negative when it costs more. */
  costSavings: number;
  fuelPerHa: number;
  /** Diesel for one season on the parcel. */
  seasonFuelL: number;
  /** Diesel for the year, for every season grown on the parcel. */
  annualFuelL: number;
  annualFuelSavingsL: number;
  passes: number;
  passesSaved: number;
  soilHealth: number;
  waterRetention: number;
  waterGainPct: number;
}

const round = (value: number, digits = 0) => {
  const factor = 10 ** digits;
  return Math.round(value * factor) / factor;
};

export function estimateParcelTillage(
  system: TillageSystem,
  areaHa: number,
  settings: TillageSettings = {},
  seasonsPerYear = 1,
): ParcelTillageEstimate {
  const profile = TILLAGE_SYSTEMS[system];
  const baseline = TILLAGE_SYSTEMS[TILLAGE_BASELINE];
  const seasons = Math.max(1, seasonsPerYear);
  const costPerHa = tillageCostPerHa(system, settings);
  const fuelPerHa = tillageFuelPerHa(system, settings);
  const seasonFuelL = fuelPerHa * areaHa;
  const baselineFuel = tillageFuelPerHa(TILLAGE_BASELINE, settings) * areaHa * seasons;
  return {
    system,
    areaHa,
    costPerHa,
    totalCost: round(costPerHa * areaHa * seasons),
    costSavings: round((tillageCostPerHa(TILLAGE_BASELINE, settings) - costPerHa) * areaHa * seasons),
    fuelPerHa,
    seasonFuelL: round(seasonFuelL),
    annualFuelL: round(seasonFuelL * seasons),
    annualFuelSavingsL: round(baselineFuel - seasonFuelL * seasons),
    passes: profile.passes,
    passesSaved: baseline.passes - profile.passes,
    soilHealth: profile.soilHealth,
    waterRetention: profile.waterRetention,
    waterGainPct: profile.waterGainPct,
  };
}

/** Plain-language reading of a soil health score. */
export function soilHealthLabel(score: number): { label: string; level: 'poor' | 'fair' | 'good' | 'excellent' } {
  if (score >= 85) return { label: 'Excellent: active soil life, stable structure', level: 'excellent' };
  if (score >= 70) return { label: 'Good: structure and organic matter are protected', level: 'good' };
  if (score >= 55) return { label: 'Fair: regular tillage wears the structure down', level: 'fair' };
  return { label: 'Poor: high risk of erosion and compaction', level: 'poor' };
}

export function waterRetentionMessage(system: TillageSystem): string {
  const gain = TILLAGE_SYSTEMS[system].waterGainPct;
  return gain > 0
    ? `The parcel can hold about ${gain}% more water than under conventional tillage.`
    : 'Reference level. Bare, inverted soil loses water to evaporation faster.';
}

// ------------------------------------------------------------------ compatibility

export interface CompatibilityCheck {
  compatible: boolean;
  message: string | null;
}

const OK: CompatibilityCheck = { compatible: true, message: null };

export function checkWorkCompatibility(system: TillageSystem | null | undefined, workType: string | null | undefined): CompatibilityCheck {
  if (!system || !workType) return OK;
  const reason = TILLAGE_SYSTEMS[system].discouragedWork[workType as TillageWorkType];
  return reason ? { compatible: false, message: `Not recommended for ${TILLAGE_SYSTEMS[system].name}. ${reason}` } : OK;
}

export function checkTaskCompatibility(system: TillageSystem | null | undefined, taskType: string | null | undefined): CompatibilityCheck {
  if (!system || !taskType) return OK;
  const reason = TILLAGE_SYSTEMS[system].discouragedTasks[taskType];
  return reason ? { compatible: false, message: `Not recommended for ${TILLAGE_SYSTEMS[system].name}. ${reason}` } : OK;
}

/**
 * Technologies a machine type suits when the user has not set them. A
 * conventional seed drill cannot cut through residue, so it is not for No Till.
 */
export const DEFAULT_MACHINE_TILLAGE: Record<TillageMachineType, TillageSystem[]> = {
  TRACTOR: [...TILLAGE_CODES],
  COMBINE: [...TILLAGE_CODES],
  SPRAYER: [...TILLAGE_CODES],
  TRAILER: [...TILLAGE_CODES],
  SEEDER: ['CONVENTIONAL', 'MINIMUM_TILL'],
  DISC: ['CONVENTIONAL', 'MINIMUM_TILL'],
  CULTIVATOR: ['CONVENTIONAL', 'MINIMUM_TILL'],
  SUBSOILER: ['CONVENTIONAL', 'MINIMUM_TILL', 'STRIP_TILL'],
};

export function machineTillageSystems(machine: { type: string; tillageSystems?: readonly string[] | null }): TillageSystem[] {
  const own = (machine.tillageSystems ?? []).filter(isTillageSystem);
  if (own.length) return own;
  return DEFAULT_MACHINE_TILLAGE[machine.type as TillageMachineType] ?? [...TILLAGE_CODES];
}

export function checkMachineCompatibility(
  machine: { name?: string; type: string; tillageSystems?: readonly string[] | null } | null | undefined,
  system: TillageSystem | null | undefined,
): CompatibilityCheck {
  if (!machine || !system) return OK;
  return machineTillageSystems(machine).includes(system)
    ? OK
    : { compatible: false, message: `${machine.name ? `${machine.name} is` : 'The selected machine is'} not recommended for ${TILLAGE_SYSTEMS[system].name}.` };
}

// ------------------------------------------------------------------ operations and advice

export interface PlannedOperation extends TillageOperation {
  dueDate: Date;
}

/** The recommended operations, dated from the first one. */
export function planTillageOperations(system: TillageSystem, start: Date): PlannedOperation[] {
  return TILLAGE_SYSTEMS[system].operations.map((operation) => ({
    ...operation,
    dueDate: new Date(start.getTime() + operation.offsetDays * 86_400_000),
  }));
}

const ROW_CROPS: CropCode[] = ['CORN', 'SUNFLOWER', 'SOY', 'BEET'];

export interface TillageAdvice {
  tone: 'info' | 'warning' | 'success';
  message: string;
}

/** Advice for the technology, adjusted to the crop and to how dry the season is. */
export function tillageRecommendations(
  system: TillageSystem,
  context: { crop?: CropCode | null; drought?: boolean } = {},
): TillageAdvice[] {
  const rowCrop = context.crop ? ROW_CROPS.includes(context.crop) : false;
  const advice: TillageAdvice[] = [];
  switch (system) {
    case 'CONVENTIONAL':
      advice.push({ tone: 'warning', message: 'High compaction risk from repeated passes. Consider Minimum Till.' });
      if (context.drought) advice.push({ tone: 'warning', message: 'In a dry season, ploughing dries the topsoil. Conservation tillage keeps more moisture.' });
      break;
    case 'MINIMUM_TILL':
      advice.push({ tone: 'success', message: 'A good fit for corn and sunflower.' });
      if (rowCrop) advice.push({ tone: 'info', message: 'For row crops, Strip Till can cut costs further with banded fertiliser.' });
      break;
    case 'STRIP_TILL':
      advice.push({ tone: 'success', message: 'Efficient for row crops and placed fertilisation.' });
      if (context.crop && !rowCrop) advice.push({ tone: 'warning', message: 'Strip Till pays off mostly in wide-row crops; check the row spacing of this crop.' });
      break;
    case 'NO_TILL':
      advice.push({ tone: 'success', message: 'Excellent moisture conservation in dry conditions.' });
      advice.push({ tone: 'info', message: 'Use a No Till drill and manage residue and weeds closely in the first years.' });
      break;
  }
  return advice;
}

// ------------------------------------------------------------------ farm analysis

export interface TillageHistoryEntry {
  year: number;
  system: TillageSystem;
}

export interface ParcelTillageInput {
  areaHa: number;
  system: TillageSystem | null;
  history?: readonly TillageHistoryEntry[] | null;
}

export interface TillageBreakdown {
  system: TillageSystem;
  parcels: number;
  areaHa: number;
  totalCost: number;
  fuelL: number;
}

export interface TillageAnalysis {
  totalAreaHa: number;
  assignedAreaHa: number;
  unassignedAreaHa: number;
  bySystem: TillageBreakdown[];
  totalCost: number;
  totalFuelL: number;
  /** Compared with the same area under conventional tillage. */
  costSavings: number;
  fuelSavingsL: number;
  passes: number;
  passesSaved: number;
  /** Fewer passes, as a share of the conventional passes. */
  passReductionPct: number;
  /** Area-weighted averages over the parcels that have a technology. */
  avgSoilHealth: number | null;
  avgWaterRetention: number | null;
}

export function analyzeTillage(parcels: readonly ParcelTillageInput[], settings: TillageSettings = {}): TillageAnalysis {
  const bySystem = new Map<TillageSystem, TillageBreakdown>(
    TILLAGE_CODES.map((system) => [system, { system, parcels: 0, areaHa: 0, totalCost: 0, fuelL: 0 }]),
  );
  let totalAreaHa = 0;
  let costSavings = 0;
  let fuelSavingsL = 0;
  let passes = 0;
  let baselinePasses = 0;
  let soil = 0;
  let water = 0;
  for (const parcel of parcels) {
    totalAreaHa += parcel.areaHa;
    if (!parcel.system) continue;
    const estimate = estimateParcelTillage(parcel.system, parcel.areaHa, settings);
    const row = bySystem.get(parcel.system)!;
    row.parcels += 1;
    row.areaHa += parcel.areaHa;
    row.totalCost += estimate.totalCost;
    row.fuelL += estimate.seasonFuelL;
    costSavings += estimate.costSavings;
    fuelSavingsL += estimate.annualFuelSavingsL;
    passes += estimate.passes * parcel.areaHa;
    baselinePasses += TILLAGE_SYSTEMS[TILLAGE_BASELINE].passes * parcel.areaHa;
    soil += estimate.soilHealth * parcel.areaHa;
    water += estimate.waterRetention * parcel.areaHa;
  }
  const rows = [...bySystem.values()].map((row) => ({ ...row, areaHa: round(row.areaHa, 2), totalCost: round(row.totalCost), fuelL: round(row.fuelL) }));
  const assignedAreaHa = rows.reduce((sum, row) => sum + row.areaHa, 0);
  return {
    totalAreaHa: round(totalAreaHa, 2),
    assignedAreaHa: round(assignedAreaHa, 2),
    unassignedAreaHa: round(Math.max(0, totalAreaHa - assignedAreaHa), 2),
    bySystem: rows,
    totalCost: round(rows.reduce((sum, row) => sum + row.totalCost, 0)),
    totalFuelL: round(rows.reduce((sum, row) => sum + row.fuelL, 0)),
    costSavings: round(costSavings),
    fuelSavingsL: round(fuelSavingsL),
    // Passes are counted per hectare-season, so large parcels weigh more.
    passes: round(passes, 1),
    passesSaved: round(baselinePasses - passes, 1),
    passReductionPct: baselinePasses > 0 ? round(((baselinePasses - passes) / baselinePasses) * 100) : 0,
    avgSoilHealth: assignedAreaHa > 0 ? round(soil / assignedAreaHa) : null,
    avgWaterRetention: assignedAreaHa > 0 ? round(water / assignedAreaHa, 1) : null,
  };
}

/** The technology a parcel used in a year: the latest history entry up to that year. */
export function tillageInYear(parcel: ParcelTillageInput, year: number): TillageSystem | null {
  const history = [...(parcel.history ?? [])].filter((entry) => entry.year <= year).sort((a, b) => b.year - a.year);
  return history[0]?.system ?? null;
}

export interface TillageYearPoint {
  year: number;
  areaBySystem: Record<TillageSystem, number>;
  avgSoilHealth: number | null;
  avgWaterRetention: number | null;
  fuelL: number;
  totalCost: number;
}

/** Year-by-year evolution from the parcel histories, for the executive charts. */
export function tillageTimeline(parcels: readonly ParcelTillageInput[], years: readonly number[], settings: TillageSettings = {}): TillageYearPoint[] {
  return years.map((year) => {
    const inYear = parcels.map((parcel) => ({ areaHa: parcel.areaHa, system: tillageInYear(parcel, year) }));
    const analysis = analyzeTillage(inYear, settings);
    const areaBySystem = Object.fromEntries(analysis.bySystem.map((row) => [row.system, row.areaHa])) as Record<TillageSystem, number>;
    return {
      year,
      areaBySystem,
      avgSoilHealth: analysis.avgSoilHealth,
      avgWaterRetention: analysis.avgWaterRetention,
      fuelL: analysis.totalFuelL,
      totalCost: analysis.totalCost,
    };
  });
}

/** Sets the technology of one year in a history, keeping one entry per year, oldest first. */
export function withTillageYear(history: readonly TillageHistoryEntry[] | null | undefined, entry: TillageHistoryEntry): TillageHistoryEntry[] {
  return [...(history ?? []).filter((item) => item.year !== entry.year), entry].sort((a, b) => a.year - b.year);
}

/** Reads a stored history, dropping entries for unknown technologies. */
export function parseTillageHistory(value: unknown): TillageHistoryEntry[] {
  if (!Array.isArray(value)) return [];
  return value
    .filter((item): item is TillageHistoryEntry => typeof item?.year === 'number' && isTillageSystem(item?.system))
    .map(({ year, system }) => ({ year, system }))
    .sort((a, b) => a.year - b.year);
}
