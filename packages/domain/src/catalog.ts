import { CROPS, type CropCode, type SoilTexture } from './crops';
import { recommendCrops } from './recommendations';
import type { CropHistoryEntry } from './rotation';

export type CatalogLevel = 'LOW' | 'MEDIUM' | 'HIGH';
export type SeedType = 'HYBRID' | 'VARIETY';
export type WorkOperation = 'PLOWING' | 'DISCING' | 'SUBSOILING' | 'SOWING' | 'FERTILIZING' | 'SPRAYING' | 'HARVESTING';

/** Unknown values stay empty. They are filled in from the manufacturer's data sheet. */
export interface SeedEntry {
  id: string;
  brand: string | null;
  crop: CropCode;
  name: string;
  type: SeedType;
  fao: number | null;
  maturityGroup: string | null;
  vegetationDays: number | null;
  yieldPotential: CatalogLevel | null;
  expectedYieldMin: number | null;
  expectedYieldMax: number | null;
  waterRequirement: CatalogLevel | null;
  droughtTolerance: CatalogLevel | null;
  diseaseResistance: string[];
  soilPreference: SoilTexture[];
  recommendedRegions: string[];
  notes: string | null;
}

export interface TractorEntry {
  id: string;
  brand: string;
  series: string | null;
  model: string;
  horsePower: number;
  fuelConsumptionMin: number;
  fuelConsumptionMax: number;
  /** Consumption is estimated from power, because it was not entered from the data sheet. */
  fuelEstimated: boolean;
  tankCapacity: number | null;
  weight: number | null;
  driveSystem: string | null;
  category: string;
  recommendedUsage: string;
}

export interface ImplementEntry {
  id: string;
  name: string;
  operation: WorkOperation;
  widthsM: number[];
  speedKmh: number;
  efficiencyPct: number;
  /** Power required for each metre of working width. */
  hpPerMeter: number;
  /** Share of the tractor's consumption range used for this operation. */
  fuelLoad: number;
}

export const LEVEL_LABELS: Record<CatalogLevel, string> = { LOW: 'Low', MEDIUM: 'Medium', HIGH: 'High' };
export const SEED_TYPE_LABELS: Record<SeedType, string> = { HYBRID: 'Hybrid', VARIETY: 'Variety' };

export function maturityGroupFor(fao: number | null) {
  if (fao === null) return null;
  if (fao < 300) return 'Early';
  if (fao < 400) return 'Medium-early';
  if (fao < 500) return 'Medium-late';
  return 'Late';
}

/** Yield potential relative to the crop's reference yield. */
export function yieldLevelFor(crop: CropCode, expectedYieldMax: number | null): CatalogLevel | null {
  if (expectedYieldMax === null) return null;
  const ratio = expectedYieldMax / CROPS[crop].referenceYieldTHa;
  return ratio >= 1.5 ? 'HIGH' : ratio >= 1 ? 'MEDIUM' : 'LOW';
}

const slug = (value: string) =>
  value.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');

export function createSeedEntry(input: Partial<SeedEntry> & Pick<SeedEntry, 'name' | 'crop' | 'type'>): SeedEntry {
  const fao = input.fao ?? null;
  const expectedYieldMax = input.expectedYieldMax ?? null;
  return {
    id: input.id ?? `${input.crop.toLowerCase()}-${slug(input.name)}`,
    brand: input.brand ?? null,
    crop: input.crop,
    name: input.name,
    type: input.type,
    fao,
    maturityGroup: input.maturityGroup ?? maturityGroupFor(fao),
    vegetationDays: input.vegetationDays ?? null,
    yieldPotential: input.yieldPotential ?? yieldLevelFor(input.crop, expectedYieldMax),
    expectedYieldMin: input.expectedYieldMin ?? null,
    expectedYieldMax,
    waterRequirement: input.waterRequirement ?? null,
    droughtTolerance: input.droughtTolerance ?? null,
    diseaseResistance: input.diseaseResistance ?? [],
    soilPreference: input.soilPreference ?? [],
    recommendedRegions: input.recommendedRegions ?? [],
    notes: input.notes ?? null,
  };
}

const seedsOf = (crop: CropCode, type: SeedType, items: (Partial<SeedEntry> & { name: string })[]) =>
  items.map((item) => createSeedEntry({ ...item, crop, type }));

/** Starter catalog products. The farm administrator fills in the rest of the data. */
export const SEED_CATALOG: SeedEntry[] = [
  ...seedsOf('CORN', 'HYBRID', [
    { name: 'Pioneer P0710', brand: 'Pioneer', fao: 410, vegetationDays: 160, droughtTolerance: 'HIGH', expectedYieldMin: 10, expectedYieldMax: 16 },
    { name: 'Pioneer P9889', brand: 'Pioneer', fao: 380 },
    { name: 'Dekalb DKC4943', brand: 'Dekalb', fao: 390 },
    { name: 'KWS Kamparis', brand: 'KWS', fao: 420 },
  ]),
  ...seedsOf('SUNFLOWER', 'HYBRID', [
    { name: 'P64LE99', brand: 'Pioneer' },
    { name: 'P64LE137', brand: 'Pioneer' },
    { name: 'SY Onestar', brand: 'Syngenta' },
    { name: 'LG50500', brand: 'Limagrain' },
    { name: 'LG50635', brand: 'Limagrain' },
  ]),
  ...seedsOf('RAPESEED', 'HYBRID', [
    { name: 'PT303', brand: 'Pioneer' },
    { name: 'PT315', brand: 'Pioneer' },
    { name: 'Architect', brand: 'Limagrain' },
    { name: 'DK Exbury', brand: 'Dekalb' },
    { name: 'DK Excited', brand: 'Dekalb' },
  ]),
  ...seedsOf('WHEAT', 'VARIETY', [
    { name: 'Glosa', brand: 'INCDA Fundulea' },
    { name: 'Izvor', brand: 'INCDA Fundulea' },
    { name: 'PG102' },
    { name: 'Accroc' },
    { name: 'Sosthene' },
  ]),
  ...seedsOf('SOY', 'VARIETY', [
    { name: 'ES Mentor', brand: 'Lidea' },
    { name: 'ES Director', brand: 'Lidea' },
    { name: 'PR91M10', brand: 'Pioneer' },
    { name: 'Aurelina' },
  ]),
];

function tractorCategory(horsePower: number) {
  if (horsePower < 150) return { category: 'Utility', recommendedUsage: 'Light work, transport and spraying.' };
  if (horsePower < 250) return { category: 'Medium', recommendedUsage: 'Sowing, fertilizing and seedbed preparation.' };
  return { category: 'Large', recommendedUsage: 'Ploughing, subsoiling and wide implements on large areas.' };
}

export function createTractorEntry(
  input: Pick<TractorEntry, 'brand' | 'model' | 'horsePower'> & Partial<TractorEntry>,
): TractorEntry {
  const fuelEstimated = input.fuelConsumptionMin == null || input.fuelConsumptionMax == null;
  return {
    id: input.id ?? slug(`${input.brand}-${input.model}`),
    series: null,
    tankCapacity: null,
    weight: null,
    driveSystem: '4x4',
    ...tractorCategory(input.horsePower),
    ...input,
    fuelConsumptionMin: input.fuelConsumptionMin ?? Math.round(input.horsePower * 0.105),
    fuelConsumptionMax: input.fuelConsumptionMax ?? Math.round(input.horsePower * 0.16),
    fuelEstimated,
  };
}

const deere = (series: string, model: string, horsePower: number, fuelConsumptionMin: number, fuelConsumptionMax: number) =>
  createTractorEntry({ brand: 'John Deere', series, model, horsePower, fuelConsumptionMin, fuelConsumptionMax });

export const TRACTOR_CATALOG: TractorEntry[] = [
  deere('6R', '6R 110', 110, 12, 18),
  deere('6R', '6R 140', 140, 14, 22),
  deere('6R', '6R 175', 175, 18, 28),
  deere('6R', '6R 215', 215, 22, 35),
  deere('7R', '7R 290', 290, 30, 45),
  deere('7R', '7R 350', 350, 35, 55),
  deere('8R', '8R 280', 280, 30, 45),
  deere('8R', '8R 340', 340, 35, 55),
  deere('8R', '8R 410', 410, 45, 65),
  createTractorEntry({ brand: 'Fendt', series: '500 Vario', model: '516 Vario', horsePower: 163 }),
  createTractorEntry({ brand: 'Fendt', series: '700 Vario', model: '724 Vario', horsePower: 246 }),
  createTractorEntry({ brand: 'Fendt', series: '900 Vario', model: '942 Vario', horsePower: 420 }),
  createTractorEntry({ brand: 'Case IH', series: 'Puma', model: 'Puma 165', horsePower: 165 }),
  createTractorEntry({ brand: 'Case IH', series: 'Magnum', model: 'Magnum 340', horsePower: 340 }),
  createTractorEntry({ brand: 'New Holland', series: 'T6', model: 'T6.180', horsePower: 175 }),
  createTractorEntry({ brand: 'New Holland', series: 'T7', model: 'T7.315', horsePower: 313 }),
  createTractorEntry({ brand: 'Claas', series: 'Arion 600', model: 'Arion 660', horsePower: 205 }),
  createTractorEntry({ brand: 'Claas', series: 'Axion 900', model: 'Axion 960', horsePower: 445 }),
];

/** Indicative values for common trailed implements. */
export const IMPLEMENT_CATALOG: ImplementEntry[] = [
  { id: 'plug', name: 'Reversible plough', operation: 'PLOWING', widthsM: [1.6, 2.1, 2.8, 3.5], speedKmh: 7, efficiencyPct: 80, hpPerMeter: 90, fuelLoad: 0.8 },
  { id: 'disc', name: 'Disc harrow', operation: 'DISCING', widthsM: [3, 4, 6, 8], speedKmh: 11, efficiencyPct: 80, hpPerMeter: 30, fuelLoad: 0.6 },
  { id: 'scarificator', name: 'Subsoiler', operation: 'SUBSOILING', widthsM: [2.5, 3, 4, 5], speedKmh: 8, efficiencyPct: 80, hpPerMeter: 70, fuelLoad: 0.8 },
  { id: 'semanatoare', name: 'Seed drill', operation: 'SOWING', widthsM: [3, 4.5, 6, 8, 12], speedKmh: 8, efficiencyPct: 70, hpPerMeter: 22, fuelLoad: 0.35 },
  { id: 'fertilizator', name: 'Fertilizer spreader', operation: 'FERTILIZING', widthsM: [12, 18, 24], speedKmh: 12, efficiencyPct: 70, hpPerMeter: 3, fuelLoad: 0.25 },
  { id: 'erbicidator', name: 'Field sprayer', operation: 'SPRAYING', widthsM: [18, 24, 28], speedKmh: 10, efficiencyPct: 70, hpPerMeter: 4, fuelLoad: 0.25 },
];

/** Diesel price in euro per litre. */
export const DEFAULT_FUEL_PRICE_EUR = 1.45;
const DEFAULT_FUEL_LOAD = 0.35;
/** The operator's hourly cost separates small, slow tractors from large, fast ones. */
const OPERATOR_COST_EUR_H = 10;
const SLOPE_POWER_LOSS_PER_PCT = 0.02;

const round = (value: number, digits = 1) => Math.round(value * 10 ** digits) / 10 ** digits;

export const fuelLitersPerHour = (min: number, max: number, load = DEFAULT_FUEL_LOAD) => round(min + (max - min) * load);

export interface WorkCost {
  fuelLPerH: number;
  fuelLiters: number;
  fuelCost: number;
  costPerHa: number;
}

export function workCost(input: { fuelLPerH: number; durationHours: number; fuelPricePerL: number; areaHa: number }): WorkCost {
  const fuelLiters = round(input.fuelLPerH * input.durationHours);
  const fuelCost = Math.round(fuelLiters * input.fuelPricePerL);
  return { fuelLPerH: input.fuelLPerH, fuelLiters, fuelCost, costPerHa: input.areaHa > 0 ? Math.round(fuelCost / input.areaHa) : 0 };
}

export interface TractorOption {
  id: string;
  name: string;
  horsePower: number;
  fuelConsumptionMin?: number | null;
  fuelConsumptionMax?: number | null;
  /** The tractor exists in the farm's fleet or is only a catalog suggestion. */
  owned: boolean;
}

export interface MachineRecommendation extends WorkCost {
  tractorId: string;
  tractorName: string;
  owned: boolean;
  implementName: string;
  workingWidthM: number;
  speedKmh: number;
  efficiencyPct: number;
  capacityHaH: number;
  durationHours: number;
  reason: string;
}

/** Picks the tractor with the lowest total cost for the job, among those with enough power. */
export function recommendMachine(input: {
  areaHa: number;
  operation: WorkOperation;
  tractors: readonly TractorOption[];
  implements?: readonly ImplementEntry[];
  fuelPricePerL?: number;
  slopePct?: number | null;
}): MachineRecommendation | null {
  const { areaHa, operation, tractors, fuelPricePerL = DEFAULT_FUEL_PRICE_EUR, slopePct } = input;
  const implement = (input.implements ?? IMPLEMENT_CATALOG).find((item) => item.operation === operation);
  if (!implement || areaHa <= 0) return null;
  const powerShare = 1 - Math.min(0.5, Math.max(0, slopePct ?? 0) * SLOPE_POWER_LOSS_PER_PCT);

  const options = tractors.flatMap((tractor): (MachineRecommendation & { rank: number })[] => {
    const usable = tractor.horsePower * powerShare;
    const workingWidthM = Math.max(0, ...implement.widthsM.filter((width) => width * implement.hpPerMeter <= usable));
    if (workingWidthM === 0) return [];
    const capacityHaH = round((workingWidthM * implement.speedKmh * implement.efficiencyPct) / 1000, 2);
    const durationHours = round(areaHa / capacityHaH, 2);
    const min = tractor.fuelConsumptionMin ?? tractor.horsePower * 0.105;
    const max = tractor.fuelConsumptionMax ?? tractor.horsePower * 0.16;
    const cost = workCost({ fuelLPerH: fuelLitersPerHour(min, max, implement.fuelLoad), durationHours, fuelPricePerL, areaHa });
    return [{
      ...cost,
      tractorId: tractor.id,
      tractorName: tractor.name,
      owned: tractor.owned,
      implementName: implement.name,
      workingWidthM,
      speedKmh: implement.speedKmh,
      efficiencyPct: implement.efficiencyPct,
      capacityHaH,
      durationHours,
      reason: `${implement.name} of ${workingWidthM} m covers ${capacityHaH} ha/hour with ${tractor.horsePower} HP.`,
      rank: cost.fuelCost + durationHours * OPERATOR_COST_EUR_H,
    }];
  });
  // Tractors from the farm's fleet take priority over catalog suggestions.
  const owned = options.filter((option) => option.owned);
  const best = (owned.length ? owned : options).sort((a, b) => a.rank - b.rank)[0];
  if (!best) return null;
  const { rank: _rank, ...recommendation } = best;
  return recommendation;
}

export interface SeedCompatibility {
  seed: SeedEntry;
  score: number;
  reason: string;
  missingData: string[];
}

const NEUTRAL_FIT = 0.65;
/** Common approximation: one FAO point corresponds to about 3.5 degree-days above 10 °C. */
const GDD_PER_FAO = 3.5;
const DROUGHT_FIT: Record<CatalogLevel, number> = { LOW: 0.4, MEDIUM: 0.7, HIGH: 0.95 };

/** Ranks compatible products by soil, climate, rotation and irrigation. */
export function rankSeedsForParcel<T extends SeedEntry>(input: {
  seeds: readonly T[];
  crop: CropCode;
  year: number;
  history: readonly CropHistoryEntry[];
  soil?: { ph: number; texture: SoilTexture } | null;
  climate?: { seasonTempC: number; annualPrecipitationMm: number; gddBase10?: number } | null;
  irrigated?: boolean;
}): (SeedCompatibility & { seed: T })[] {
  const { seeds, crop, year, history, soil, climate, irrigated = false } = input;
  const base = recommendCrops({ year, history, soil, climate, crops: [crop] })[0]!;
  const profile = CROPS[crop];
  const requiredMm = (profile.waterMm[0] + profile.waterMm[1]) / 2;

  return seeds
    .filter((seed) => seed.crop === crop)
    .map((seed) => {
      const fits: number[] = [];
      const good: string[] = [];
      const weak: string[] = [];
      const missingData: string[] = [];

      if (seed.fao !== null && climate?.gddBase10) {
        const needed = seed.fao * GDD_PER_FAO;
        const fit = climate.gddBase10 >= needed ? 1 : Math.max(0, 1 - (needed - climate.gddBase10) / 400);
        fits.push(fit);
        if (fit === 1) good.push(`FAO ${seed.fao} reaches maturity in the parcel's climate`);
        else weak.push(`FAO ${seed.fao} needs a longer season than the local one`);
      } else {
        fits.push(NEUTRAL_FIT);
        if (seed.fao === null) missingData.push('FAO');
      }

      if (irrigated) {
        fits.push(1);
        good.push('the parcel is irrigated');
      } else if (climate && climate.annualPrecipitationMm >= requiredMm) {
        fits.push(1);
        good.push('rainfall covers the water requirement');
      } else if (seed.droughtTolerance) {
        fits.push(DROUGHT_FIT[seed.droughtTolerance]);
        if (seed.droughtTolerance === 'HIGH') good.push('it tolerates drought well');
        else weak.push('drought tolerance is limited for a non-irrigated parcel');
      } else {
        fits.push(NEUTRAL_FIT);
        missingData.push('drought tolerance');
      }

      if (soil && seed.soilPreference.length > 0) {
        const matches = seed.soilPreference.includes(soil.texture);
        fits.push(matches ? 1 : 0.5);
        if (matches) good.push('the soil texture is suitable');
        else weak.push('the soil texture is not among the preferred ones');
      }

      const fit = fits.reduce((sum, value) => sum + value, 0) / fits.length;
      const score = Math.round(base.score * 0.6 + fit * 40);
      const list = (items: string[]) => (items.length < 2 ? (items[0] ?? '') : `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`);
      const sentences = [
        good.length ? `${seed.name} is a good fit because ${list(good)}.` : '',
        weak.length ? `Note: ${list(weak)}.` : '',
        missingData.length ? `Indicative score, missing: ${list(missingData)}.` : '',
      ].filter(Boolean);
      return { seed, score, reason: sentences.join(' ') || `${seed.name} is compatible with this crop.`, missingData };
    })
    .sort((a, b) => b.score - a.score || a.seed.name.localeCompare(b.seed.name));
}

export interface SeedFilters {
  search?: string;
  crop?: CropCode | null;
  brand?: string | null;
  droughtTolerance?: CatalogLevel | null;
  waterRequirement?: CatalogLevel | null;
  yieldPotential?: CatalogLevel | null;
  faoMin?: number | null;
  faoMax?: number | null;
  sort?: 'name' | 'fao' | 'yield';
}

const normalized = (value: string | null | undefined) =>
  (value ?? '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().trim();

export function filterSeeds<T extends SeedEntry>(seeds: readonly T[], filters: SeedFilters): T[] {
  const query = normalized(filters.search);
  const { faoMin, faoMax } = filters;
  const result = seeds.filter(
    (seed) =>
      (!query || normalized(`${seed.name} ${seed.brand ?? ''} ${CROPS[seed.crop].name}`).includes(query)) &&
      (!filters.crop || seed.crop === filters.crop) &&
      (!filters.brand || seed.brand === filters.brand) &&
      (!filters.droughtTolerance || seed.droughtTolerance === filters.droughtTolerance) &&
      (!filters.waterRequirement || seed.waterRequirement === filters.waterRequirement) &&
      (!filters.yieldPotential || seed.yieldPotential === filters.yieldPotential) &&
      (faoMin == null || (seed.fao !== null && seed.fao >= faoMin)) &&
      (faoMax == null || (seed.fao !== null && seed.fao <= faoMax)),
  );
  const last = Number.POSITIVE_INFINITY;
  if (filters.sort === 'fao') return result.sort((a, b) => (a.fao ?? last) - (b.fao ?? last));
  if (filters.sort === 'yield') return result.sort((a, b) => (b.expectedYieldMax ?? -1) - (a.expectedYieldMax ?? -1));
  return result.sort((a, b) => a.name.localeCompare(b.name));
}

export function filterTractors<T extends TractorEntry>(tractors: readonly T[], filters: { search?: string; brand?: string | null; sort?: 'name' | 'power' }): T[] {
  const query = normalized(filters.search);
  const result = tractors.filter(
    (tractor) =>
      (!query || normalized(`${tractor.brand} ${tractor.model} ${tractor.series ?? ''}`).includes(query)) &&
      (!filters.brand || tractor.brand === filters.brand),
  );
  if (filters.sort === 'power') return result.sort((a, b) => b.horsePower - a.horsePower);
  return result.sort((a, b) => `${a.brand} ${a.model}`.localeCompare(`${b.brand} ${b.model}`, undefined, { numeric: true }));
}

const CROP_ALIASES: Record<string, CropCode> = {
  porumb: 'CORN', corn: 'CORN', maize: 'CORN',
  grau: 'WHEAT', wheat: 'WHEAT',
  orz: 'BARLEY', barley: 'BARLEY',
  'floarea-soarelui': 'SUNFLOWER', 'floarea soarelui': 'SUNFLOWER', sunflower: 'SUNFLOWER',
  rapita: 'RAPESEED', rapeseed: 'RAPESEED',
  soia: 'SOY', soy: 'SOY', soybean: 'SOY',
  lucerna: 'ALFALFA', alfalfa: 'ALFALFA',
  ovaz: 'OAT', oat: 'OAT', oats: 'OAT',
  cartof: 'POTATO', potato: 'POTATO',
  sfecla: 'BEET', 'sfecla de zahar': 'BEET', beet: 'BEET',
  legume: 'VEGETABLES', vegetables: 'VEGETABLES',
};

const LEVEL_ALIASES: Record<string, CatalogLevel> = {
  low: 'LOW', scazuta: 'LOW', scazut: 'LOW', mica: 'LOW',
  medium: 'MEDIUM', medie: 'MEDIUM', mediu: 'MEDIUM',
  high: 'HIGH', ridicata: 'HIGH', ridicat: 'HIGH', mare: 'HIGH',
};

function cellOf(row: Record<string, string>, aliases: readonly string[]) {
  const cells = new Map(Object.entries(row).map(([key, value]) => [normalized(key).replace(/[\s_-]+/g, ''), value.trim()]));
  for (const alias of aliases) {
    const value = cells.get(alias);
    if (value) return value;
  }
  return null;
}

function numberOf(row: Record<string, string>, aliases: readonly string[]) {
  const value = cellOf(row, aliases);
  if (value === null) return null;
  const parsed = Number(value.replace(',', '.'));
  return Number.isFinite(parsed) ? parsed : null;
}

const levelOf = (row: Record<string, string>, aliases: readonly string[]) => LEVEL_ALIASES[normalized(cellOf(row, aliases))] ?? null;

/** Converts a row from the imported file into a catalog product. */
export function seedFromRow(row: Record<string, string>): SeedEntry | string {
  const name = cellOf(row, ['name', 'nume', 'hibrid', 'soi', 'produs']);
  if (!name) return 'The product name is missing.';
  const crop = CROP_ALIASES[normalized(cellOf(row, ['crop', 'cultura']))];
  if (!crop) return `${name}: the crop is not recognized.`;
  const type = normalized(cellOf(row, ['type', 'tip']));
  return createSeedEntry({
    name,
    crop,
    type: type === 'soi' || type === 'variety' ? 'VARIETY' : type === 'hibrid' || type === 'hybrid' ? 'HYBRID' : crop === 'WHEAT' || crop === 'SOY' ? 'VARIETY' : 'HYBRID',
    brand: cellOf(row, ['brand', 'producator', 'marca']),
    fao: numberOf(row, ['fao']),
    vegetationDays: numberOf(row, ['vegetationdays', 'zilevegetatie', 'zile']),
    expectedYieldMin: numberOf(row, ['expectedyieldmin', 'productiemin']),
    expectedYieldMax: numberOf(row, ['expectedyieldmax', 'productiemax']),
    waterRequirement: levelOf(row, ['waterrequirement', 'necesarapa']),
    droughtTolerance: levelOf(row, ['droughttolerance', 'tolerance', 'toleranta', 'tolerantaseceta']),
    notes: cellOf(row, ['notes', 'observatii']),
  });
}

export function tractorFromRow(row: Record<string, string>): TractorEntry | string {
  const brand = cellOf(row, ['brand', 'marca', 'producator']);
  const model = cellOf(row, ['model']);
  if (!brand || !model) return 'The brand or model is missing.';
  const horsePower = numberOf(row, ['horsepower', 'cp', 'putere', 'hp']);
  if (!horsePower || horsePower <= 0) return `${brand} ${model}: the power is not valid.`;
  return createTractorEntry({
    brand,
    model,
    horsePower,
    series: cellOf(row, ['series', 'serie']),
    fuelConsumptionMin: numberOf(row, ['fuelconsumptionmin', 'consummin']) ?? undefined,
    fuelConsumptionMax: numberOf(row, ['fuelconsumptionmax', 'consummax']) ?? undefined,
    tankCapacity: numberOf(row, ['tankcapacity', 'rezervor']),
    weight: numberOf(row, ['weight', 'greutate', 'masa']),
    driveSystem: cellOf(row, ['drivesystem', 'tractiune']) ?? '4x4',
  });
}

/** Reads a CSV file, with comma or semicolon separator. */
export function parseCsv(text: string): Record<string, string>[] {
  const clean = text.replace(/^﻿/, '');
  const firstLine = clean.split(/\r?\n/, 1)[0] ?? '';
  const separator = (firstLine.match(/;/g)?.length ?? 0) > (firstLine.match(/,/g)?.length ?? 0) ? ';' : ',';
  const rows: string[][] = [];
  let row: string[] = [];
  let cell = '';
  let quoted = false;
  for (let i = 0; i < clean.length; i += 1) {
    const char = clean[i]!;
    if (quoted) {
      if (char === '"' && clean[i + 1] === '"') {
        cell += '"';
        i += 1;
      } else if (char === '"') quoted = false;
      else cell += char;
    } else if (char === '"') quoted = true;
    else if (char === separator) {
      row.push(cell);
      cell = '';
    } else if (char === '\n' || char === '\r') {
      if (char === '\r' && clean[i + 1] === '\n') i += 1;
      row.push(cell);
      rows.push(row);
      row = [];
      cell = '';
    } else cell += char;
  }
  if (cell || row.length) rows.push([...row, cell]);
  return tableToRows(rows);
}

/** The first line of the table contains the column names. */
export function tableToRows(table: readonly (readonly string[])[]): Record<string, string>[] {
  const [header, ...rows] = table;
  if (!header) return [];
  return rows
    .filter((row) => row.some((cell) => cell.trim()))
    .map((row) => Object.fromEntries(header.map((name, index) => [name.trim(), row[index] ?? ''])));
}
