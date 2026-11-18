import { CROPS, type CropCode, type SoilTexture } from './crops';
import { recommendCrops } from './recommendations';
import type { CropHistoryEntry } from './rotation';

export type CatalogLevel = 'LOW' | 'MEDIUM' | 'HIGH';
export type SeedType = 'HYBRID' | 'VARIETY';
export type WorkOperation = 'PLOWING' | 'DISCING' | 'SUBSOILING' | 'SOWING' | 'FERTILIZING' | 'SPRAYING' | 'HARVESTING';

/** Valorile necunoscute rămân goale. Ele se completează din fișa tehnică a producătorului. */
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
  /** Consumul este estimat din putere, deoarece nu a fost introdus din fișa tehnică. */
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
  /** Puterea necesară pentru fiecare metru de lucru. */
  hpPerMeter: number;
  /** Partea din intervalul de consum al tractorului folosită la această lucrare. */
  fuelLoad: number;
}

export const LEVEL_LABELS: Record<CatalogLevel, string> = { LOW: 'Scăzută', MEDIUM: 'Medie', HIGH: 'Ridicată' };
export const SEED_TYPE_LABELS: Record<SeedType, string> = { HYBRID: 'Hibrid', VARIETY: 'Soi' };

export function maturityGroupFor(fao: number | null) {
  if (fao === null) return null;
  if (fao < 300) return 'Timpuriu';
  if (fao < 400) return 'Semitimpuriu';
  if (fao < 500) return 'Semitardiv';
  return 'Tardiv';
}

/** Potențialul de producție raportat la producția de referință a culturii. */
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

/** Produsele de pornire ale catalogului. Administratorul fermei completează restul datelor. */
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
  if (horsePower < 150) return { category: 'Utilitar', recommendedUsage: 'Lucrări ușoare, transport și erbicidat.' };
  if (horsePower < 250) return { category: 'Mediu', recommendedUsage: 'Semănat, fertilizat și pregătirea terenului.' };
  return { category: 'Mare', recommendedUsage: 'Arat, scarificat și utilaje late pe suprafețe mari.' };
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

/** Valori orientative pentru utilaje tractate uzuale. */
export const IMPLEMENT_CATALOG: ImplementEntry[] = [
  { id: 'plug', name: 'Plug reversibil', operation: 'PLOWING', widthsM: [1.6, 2.1, 2.8, 3.5], speedKmh: 7, efficiencyPct: 80, hpPerMeter: 90, fuelLoad: 0.8 },
  { id: 'disc', name: 'Grapă cu discuri', operation: 'DISCING', widthsM: [3, 4, 6, 8], speedKmh: 11, efficiencyPct: 80, hpPerMeter: 30, fuelLoad: 0.6 },
  { id: 'scarificator', name: 'Scarificator', operation: 'SUBSOILING', widthsM: [2.5, 3, 4, 5], speedKmh: 8, efficiencyPct: 80, hpPerMeter: 70, fuelLoad: 0.8 },
  { id: 'semanatoare', name: 'Semănătoare', operation: 'SOWING', widthsM: [3, 4.5, 6, 8, 12], speedKmh: 8, efficiencyPct: 70, hpPerMeter: 22, fuelLoad: 0.35 },
  { id: 'fertilizator', name: 'Mașină de fertilizat', operation: 'FERTILIZING', widthsM: [12, 18, 24], speedKmh: 12, efficiencyPct: 70, hpPerMeter: 3, fuelLoad: 0.25 },
  { id: 'erbicidator', name: 'Mașină de erbicidat', operation: 'SPRAYING', widthsM: [18, 24, 28], speedKmh: 10, efficiencyPct: 70, hpPerMeter: 4, fuelLoad: 0.25 },
];

export const DEFAULT_FUEL_PRICE_RON = 7.2;
const DEFAULT_FUEL_LOAD = 0.35;
/** Costul orar al operatorului departajează tractoarele mici, lente, de cele mari, rapide. */
const OPERATOR_COST_RON_H = 50;
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
  /** Tractorul există în parcul fermei sau este doar o sugestie din catalog. */
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

/** Alege tractorul cu cel mai mic cost total pentru lucrare, dintre cele cu putere suficientă. */
export function recommendMachine(input: {
  areaHa: number;
  operation: WorkOperation;
  tractors: readonly TractorOption[];
  implements?: readonly ImplementEntry[];
  fuelPricePerL?: number;
  slopePct?: number | null;
}): MachineRecommendation | null {
  const { areaHa, operation, tractors, fuelPricePerL = DEFAULT_FUEL_PRICE_RON, slopePct } = input;
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
      reason: `${implement.name} de ${workingWidthM} m lucrează ${capacityHaH} ha/oră cu ${tractor.horsePower} CP.`,
      rank: cost.fuelCost + durationHours * OPERATOR_COST_RON_H,
    }];
  });
  // Tractoarele din parcul fermei au prioritate față de sugestiile din catalog.
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
/** Aproximare uzuală: un punct FAO corespunde la circa 3,5 grade-zile peste 10 °C. */
const GDD_PER_FAO = 3.5;
const DROUGHT_FIT: Record<CatalogLevel, number> = { LOW: 0.4, MEDIUM: 0.7, HIGH: 0.95 };

/** Ordonează produsele compatibile după sol, climă, rotație și irigații. */
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
        if (fit === 1) good.push(`FAO ${seed.fao} ajunge la maturitate în clima parcelei`);
        else weak.push(`FAO ${seed.fao} are nevoie de un sezon mai lung decât cel local`);
      } else {
        fits.push(NEUTRAL_FIT);
        if (seed.fao === null) missingData.push('FAO');
      }

      if (irrigated) {
        fits.push(1);
        good.push('parcela are irigații');
      } else if (climate && climate.annualPrecipitationMm >= requiredMm) {
        fits.push(1);
        good.push('precipitațiile acoperă necesarul de apă');
      } else if (seed.droughtTolerance) {
        fits.push(DROUGHT_FIT[seed.droughtTolerance]);
        if (seed.droughtTolerance === 'HIGH') good.push('tolerează bine seceta');
        else weak.push('toleranța la secetă este limitată pentru o parcelă neirigată');
      } else {
        fits.push(NEUTRAL_FIT);
        missingData.push('toleranța la secetă');
      }

      if (soil && seed.soilPreference.length > 0) {
        const matches = seed.soilPreference.includes(soil.texture);
        fits.push(matches ? 1 : 0.5);
        if (matches) good.push('textura solului este potrivită');
        else weak.push('textura solului nu este printre cele preferate');
      }

      const fit = fits.reduce((sum, value) => sum + value, 0) / fits.length;
      const score = Math.round(base.score * 0.6 + fit * 40);
      const list = (items: string[]) => (items.length < 2 ? (items[0] ?? '') : `${items.slice(0, -1).join(', ')} și ${items[items.length - 1]}`);
      const sentences = [
        good.length ? `${seed.name} se potrivește deoarece ${list(good)}.` : '',
        weak.length ? `Atenție: ${list(weak)}.` : '',
        missingData.length ? `Scor orientativ, lipsesc: ${list(missingData)}.` : '',
      ].filter(Boolean);
      return { seed, score, reason: sentences.join(' ') || `${seed.name} este compatibil cu această cultură.`, missingData };
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
  ovaz: 'OAT', oat: 'OAT',
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

/** Transformă un rând din fișierul importat în produs de catalog. */
export function seedFromRow(row: Record<string, string>): SeedEntry | string {
  const name = cellOf(row, ['name', 'nume', 'hibrid', 'soi', 'produs']);
  if (!name) return 'Lipsește numele produsului.';
  const crop = CROP_ALIASES[normalized(cellOf(row, ['crop', 'cultura']))];
  if (!crop) return `${name}: cultura nu este recunoscută.`;
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
    droughtTolerance: levelOf(row, ['droughttolerance', 'toleranta', 'tolerantaseceta']),
    notes: cellOf(row, ['notes', 'observatii']),
  });
}

export function tractorFromRow(row: Record<string, string>): TractorEntry | string {
  const brand = cellOf(row, ['brand', 'marca', 'producator']);
  const model = cellOf(row, ['model']);
  if (!brand || !model) return 'Lipsește marca sau modelul.';
  const horsePower = numberOf(row, ['horsepower', 'cp', 'putere', 'hp']);
  if (!horsePower || horsePower <= 0) return `${brand} ${model}: puterea nu este validă.`;
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

/** Citește un fișier CSV, cu separator virgulă sau punct și virgulă. */
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

/** Prima linie a tabelului conține numele coloanelor. */
export function tableToRows(table: readonly (readonly string[])[]): Record<string, string>[] {
  const [header, ...rows] = table;
  if (!header) return [];
  return rows
    .filter((row) => row.some((cell) => cell.trim()))
    .map((row) => Object.fromEntries(header.map((name, index) => [name.trim(), row[index] ?? ''])));
}
