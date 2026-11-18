import { CROP_CODES, CROPS, type CropCode, type SoilTexture } from './crops';
import { recommendCrops, type CropRecommendation } from './recommendations';
import { evaluateRotation, type CropHistoryEntry } from './rotation';

const DAY_MS = 86_400_000;
const MIN_DAYS_BEFORE_SOWING = 7;
const IRRIGATION_DOSE_MM = 30;
const MONTHS = ['ianuarie', 'februarie', 'martie', 'aprilie', 'mai', 'iunie', 'iulie', 'august', 'septembrie', 'octombrie', 'noiembrie', 'decembrie'];

export const OTHER_CROP = 'OTHER';
export type LastCrop = CropCode | typeof OTHER_CROP;

export type PlanStepKey =
  | 'SOIL_PREP'
  | 'SEED_PURCHASE'
  | 'BASE_FERTILIZATION'
  | 'SOWING'
  | 'PRE_EMERGENT'
  | 'EMERGENCE_CHECK'
  | 'TOP_DRESSING'
  | 'DISEASE_MONITORING'
  | 'PEST_MONITORING'
  | 'HARVEST_PREP'
  | 'HARVEST';

export type PlanTaskType =
  | 'SOIL_PREP'
  | 'SEED_PURCHASE'
  | 'FERTILIZATION'
  | 'SOWING'
  | 'HERBICIDE'
  | 'EMERGENCE_CHECK'
  | 'DISEASE_CHECK'
  | 'PEST_CHECK'
  | 'HARVEST_PREP'
  | 'HARVEST';

interface CropCalendar {
  sowing: readonly [month: number, day: number];
  emergenceDays: number;
  /** Zile de la semănat. Lipsește la culturile care nu primesc azot fazial. */
  topDressingDays: number | null;
  diseaseDays: number;
  pestDays: number;
  harvestDays: number;
  /** Perioada în care lipsa apei afectează cel mai mult producția. */
  criticalMonths: readonly [from: number, to: number];
  /** Partea din precipitațiile anuale disponibilă culturii. */
  rainShare: number;
  priceEurPerT: number;
  costEurHa: number;
  /** Necesar de N, P₂O₅ și K₂O, în kg pentru o tonă de producție. */
  nutrientsPerT: readonly [n: number, p: number, k: number];
}

/** Valori orientative pentru condițiile din România. */
export const CROP_CALENDARS: Record<CropCode, CropCalendar> = {
  WHEAT: { sowing: [10, 5], emergenceDays: 12, topDressingDays: 165, diseaseDays: 205, pestDays: 225, harvestDays: 275, criticalMonths: [4, 5], rainShare: 0.8, priceEurPerT: 200, costEurHa: 800, nutrientsPerT: [25, 10, 6] },
  BARLEY: { sowing: [9, 28], emergenceDays: 12, topDressingDays: 165, diseaseDays: 195, pestDays: 215, harvestDays: 270, criticalMonths: [4, 5], rainShare: 0.8, priceEurPerT: 180, costEurHa: 700, nutrientsPerT: [24, 10, 7] },
  CORN: { sowing: [4, 5], emergenceDays: 12, topDressingDays: 40, diseaseDays: 66, pestDays: 118, harvestDays: 193, criticalMonths: [7, 8], rainShare: 0.6, priceEurPerT: 190, costEurHa: 1000, nutrientsPerT: [23, 10, 7] },
  SUNFLOWER: { sowing: [4, 1], emergenceDays: 12, topDressingDays: 35, diseaseDays: 75, pestDays: 60, harvestDays: 150, criticalMonths: [6, 7], rainShare: 0.6, priceEurPerT: 430, costEurHa: 650, nutrientsPerT: [40, 18, 12] },
  RAPESEED: { sowing: [8, 28], emergenceDays: 8, topDressingDays: 190, diseaseDays: 225, pestDays: 205, harvestDays: 305, criticalMonths: [4, 5], rainShare: 0.8, priceEurPerT: 460, costEurHa: 850, nutrientsPerT: [50, 25, 12] },
  SOY: { sowing: [4, 25], emergenceDays: 12, topDressingDays: null, diseaseDays: 70, pestDays: 85, harvestDays: 145, criticalMonths: [7, 8], rainShare: 0.6, priceEurPerT: 400, costEurHa: 650, nutrientsPerT: [0, 15, 12] },
  ALFALFA: { sowing: [4, 1], emergenceDays: 10, topDressingDays: null, diseaseDays: 40, pestDays: 50, harvestDays: 75, criticalMonths: [6, 8], rainShare: 0.7, priceEurPerT: 130, costEurHa: 500, nutrientsPerT: [0, 6, 20] },
  OAT: { sowing: [3, 15], emergenceDays: 14, topDressingDays: 40, diseaseDays: 70, pestDays: 80, harvestDays: 125, criticalMonths: [5, 6], rainShare: 0.6, priceEurPerT: 170, costEurHa: 550, nutrientsPerT: [25, 10, 8] },
  POTATO: { sowing: [4, 5], emergenceDays: 20, topDressingDays: 35, diseaseDays: 60, pestDays: 50, harvestDays: 130, criticalMonths: [6, 7], rainShare: 0.6, priceEurPerT: 200, costEurHa: 4000, nutrientsPerT: [4, 1.5, 6] },
  BEET: { sowing: [3, 25], emergenceDays: 14, topDressingDays: 45, diseaseDays: 100, pestDays: 60, harvestDays: 195, criticalMonths: [7, 8], rainShare: 0.65, priceEurPerT: 40, costEurHa: 1800, nutrientsPerT: [2, 1, 2.5] },
  VEGETABLES: { sowing: [4, 20], emergenceDays: 10, topDressingDays: 35, diseaseDays: 50, pestDays: 40, harvestDays: 100, criticalMonths: [6, 8], rainShare: 0.55, priceEurPerT: 350, costEurHa: 6000, nutrientsPerT: [3, 1.5, 4] },
};

export interface PlanStep {
  key: PlanStepKey;
  title: string;
  taskType: PlanTaskType;
  date: string;
  priority: 'MEDIUM' | 'HIGH';
  /** Termenul a trecut, deci pasul nu mai devine task. */
  past: boolean;
}

/** Anul agricol următor. După 1 august, recolta anului curent este considerată ultima cultură. */
export const nextSeasonYear = (now: Date = new Date()) =>
  now.getUTCMonth() >= 7 ? now.getUTCFullYear() + 1 : now.getUTCFullYear();

const startOfDay = (date: Date) => new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate(), 9));
const addDays = (date: Date, days: number) => new Date(date.getTime() + days * DAY_MS);
const sowingIn = (crop: CropCode, year: number) => {
  const [month, day] = CROP_CALENDARS[crop].sowing;
  return new Date(Date.UTC(year, month - 1, day, 9));
};

export interface CalendarOptions {
  now?: Date;
  /** Data reală a semănatului, dacă a fost înregistrată. */
  sowingDate?: Date | null;
  /** Anul recoltei, pentru o cultură deja înregistrată. Altfel se alege următoarea fereastră posibilă. */
  harvestYear?: number;
}

function resolveSowing(crop: CropCode, { now = new Date(), sowingDate, harvestYear }: CalendarOptions): Date {
  if (sowingDate) return sowingDate;
  const { harvestDays } = CROP_CALENDARS[crop];
  if (harvestYear !== undefined) {
    const sameYear = sowingIn(crop, harvestYear);
    return addDays(sameYear, harvestDays).getUTCFullYear() > harvestYear ? sowingIn(crop, harvestYear - 1) : sameYear;
  }
  const earliest = addDays(startOfDay(now), MIN_DAYS_BEFORE_SOWING);
  const thisYear = sowingIn(crop, now.getUTCFullYear());
  return thisYear >= earliest ? thisYear : sowingIn(crop, now.getUTCFullYear() + 1);
}

/** Calendarul tehnologic al culturii, calculat pornind de la data semănatului. */
export function planCalendar(crop: CropCode, options: CalendarOptions = {}) {
  const calendar = CROP_CALENDARS[crop];
  const today = startOfDay(options.now ?? new Date());
  const sowing = resolveSowing(crop, options);
  const harvest = addDays(sowing, calendar.harvestDays);
  const planned = options.harvestYear === undefined && !options.sowingDate;

  const definitions: { key: PlanStepKey; title: string; taskType: PlanTaskType; days: number; priority?: 'HIGH' }[] = [
    { key: 'SOIL_PREP', title: 'Pregătirea terenului', taskType: 'SOIL_PREP', days: -26 },
    { key: 'SEED_PURCHASE', title: 'Achiziție sămânță', taskType: 'SEED_PURCHASE', days: -11 },
    { key: 'BASE_FERTILIZATION', title: 'Fertilizare de bază', taskType: 'FERTILIZATION', days: -6 },
    { key: 'SOWING', title: 'Semănat', taskType: 'SOWING', days: 0, priority: 'HIGH' },
    { key: 'PRE_EMERGENT', title: 'Erbicid preemergent', taskType: 'HERBICIDE', days: 2 },
    { key: 'EMERGENCE_CHECK', title: 'Verificare răsărire', taskType: 'EMERGENCE_CHECK', days: calendar.emergenceDays },
    ...(calendar.topDressingDays === null
      ? []
      : [{ key: 'TOP_DRESSING' as const, title: 'Fertilizare fazială', taskType: 'FERTILIZATION' as const, days: calendar.topDressingDays }]),
    { key: 'PEST_MONITORING', title: 'Monitorizare dăunători', taskType: 'PEST_CHECK', days: calendar.pestDays },
    { key: 'DISEASE_MONITORING', title: 'Monitorizare boli', taskType: 'DISEASE_CHECK', days: calendar.diseaseDays },
    { key: 'HARVEST_PREP', title: 'Pregătire recoltare', taskType: 'HARVEST_PREP', days: calendar.harvestDays - 14 },
    { key: 'HARVEST', title: 'Recoltare', taskType: 'HARVEST', days: calendar.harvestDays, priority: 'HIGH' },
  ];

  const steps: PlanStep[] = definitions
    .map(({ days, priority, ...step }) => {
      const date = addDays(sowing, days);
      // Pașii pregătitori rămași în urmă la un plan nou se fac imediat.
      const due = planned && date < today ? today : date;
      return { ...step, priority: priority ?? ('MEDIUM' as const), date: due.toISOString(), past: due < today };
    })
    .sort((a, b) => a.date.localeCompare(b.date));

  return { sowingDate: sowing.toISOString(), harvestDate: harvest.toISOString(), seasonYear: harvest.getUTCFullYear(), steps };
}

export interface PlanSoil {
  ph: number;
  texture: SoilTexture;
  label?: string | null;
}

export interface PlanClimate {
  seasonTempC: number;
  annualPrecipitationMm: number;
  gddBase10?: number;
}

export interface RankedCrop extends CropRecommendation {
  /** Motivul scorului, într-o singură propoziție. */
  reason: string;
}

function joinClauses(clauses: string[]) {
  if (clauses.length < 2) return clauses[0] ?? '';
  return `${clauses.slice(0, -1).join(', ')} și ${clauses[clauses.length - 1]}`;
}

function explain(
  recommendation: CropRecommendation,
  year: number,
  history: readonly CropHistoryEntry[],
  soil: PlanSoil | null,
  climate: PlanClimate | null,
): string {
  const profile = CROPS[recommendation.crop];
  const rotation = evaluateRotation(recommendation.crop, year, history);
  const previous = history.find((entry) => entry.year === year - 1);
  const good: string[] = [];
  const weak: string[] = [];

  if (rotation.score >= 70) {
    if (previous && previous.crop !== recommendation.crop) {
      const previousProfile = CROPS[previous.crop];
      good.push(`parcela a avut ${previousProfile.name.toLowerCase()} anul trecut`);
      if (previousProfile.family === 'LEGUME') good.push(`valorifică azotul lăsat în sol de ${previousProfile.name.toLowerCase()}`);
    } else {
      good.push('rotația este favorabilă');
    }
    if (profile.family === 'LEGUME') good.push('îmbogățește solul în azot');
  } else {
    weak.push(rotation.explanation.replace(/\.$/, '').replace(/^./, (letter) => letter.toLowerCase()));
  }

  if (soil) {
    const [min, max] = profile.phRange;
    if (soil.ph >= min && soil.ph <= max) good.push(`pH-ul de ${soil.ph} este optim`);
    else weak.push(`pH-ul de ${soil.ph} este în afara intervalului ${min}-${max}`);
    if (!profile.textures.includes(soil.texture)) weak.push('textura solului nu este potrivită');
  }
  if (climate) {
    const needed = profile.waterMm[0];
    if (climate.annualPrecipitationMm >= needed) good.push(`precipitațiile de ${Math.round(climate.annualPrecipitationMm)} mm acoperă necesarul minim`);
    else weak.push(`precipitațiile de ${Math.round(climate.annualPrecipitationMm)} mm sunt sub necesarul minim de ${needed} mm`);
  }

  if (recommendation.score >= 70 && good.length > 0) {
    return `${profile.name} se potrivește deoarece ${joinClauses(good)}.`;
  }
  if (weak.length > 0) return `${profile.name} are un scor mai mic deoarece ${joinClauses(weak)}.`;
  return `${profile.name} are un scor orientativ, deoarece lipsesc date despre ${joinClauses(recommendation.missingData)}.`;
}

/** Ordonează culturile după sol, climă și rotație, cu explicația fiecărui scor. */
export function rankCropsForParcel(input: {
  year: number;
  history: readonly CropHistoryEntry[];
  soil: PlanSoil | null;
  climate: PlanClimate | null;
}): RankedCrop[] {
  const { year, history, soil, climate } = input;
  return recommendCrops({ year, history, soil, climate, crops: CROP_CODES }).map((item) => ({
    ...item,
    reason: explain(item, year, history, soil, climate),
  }));
}

export interface FertilizationStage {
  stage: string;
  date: string | null;
  nKgHa: number;
  pKgHa: number;
  kKgHa: number;
}

export interface ParcelReport {
  parcelName: string;
  areaHa: number;
  soilType: string | null;
  lastCropName: string | null;
  plannedCropName: string;
  recommendedCrop: CropCode;
  recommendedCropName: string;
  recommendedScore: number;
  compatibilityScore: number;
  expectedYieldTHa: number;
  /** Lipsesc pentru operatori, care nu au acces la datele financiare. */
  expectedProfitEurHa?: number;
  priceEurPerT?: number;
  costEurHa?: number;
  risks: string[];
  recommendation: string;
  missingData: string[];
}

export interface ParcelPlanData {
  year: number;
  crop: CropCode;
  cropName: string;
  rotationScore: number;
  rotationExplanation: string;
  sowingDate: string;
  harvestDate: string;
  steps: PlanStep[];
  irrigation: {
    requiredMm: number;
    effectiveRainMm: number | null;
    deficitMm: number | null;
    passes: number;
    doseMm: number;
    criticalPeriod: string;
  };
  fertilization: FertilizationStage[];
  monitoring: { title: string; date: string }[];
  recommendations: RankedCrop[];
  report: ParcelReport;
}

export interface ParcelPlanInput {
  crop: CropCode;
  parcelName: string;
  areaHa: number;
  /** Culturile anterioare, inclusiv ultima cultură declarată. */
  history: readonly CropHistoryEntry[];
  /** Denumirea ultimei culturi, când nu face parte din catalog. */
  lastCropName?: string | null;
  soil: PlanSoil | null;
  climate: PlanClimate | null;
  now?: Date;
  /** Anul unei culturi deja înregistrate. */
  year?: number;
  sowingDate?: Date | null;
}

const roundTo5 = (value: number) => Math.round(value / 5) * 5;
const period = ([from, to]: readonly [number, number]) => `${MONTHS[from - 1]}-${MONTHS[to - 1]}`;

/** Generează planul complet al culturii: pași, calendar, apă, nutrienți, monitorizare și raport. */
export function buildParcelPlan(input: ParcelPlanInput): ParcelPlanData {
  const { crop, parcelName, areaHa, history, soil, climate, now = new Date(), year: fixedYear, sowingDate } = input;
  const profile = CROPS[crop];
  const calendar = CROP_CALENDARS[crop];
  const schedule = planCalendar(crop, { now, sowingDate, harvestYear: fixedYear });
  const year = fixedYear ?? schedule.seasonYear;

  // Toate culturile se compară în același an agricol, ca scorurile să rămână comparabile.
  const comparisonYear = Math.min(year, Math.max(nextSeasonYear(now), ...history.map((entry) => entry.year + 1)));
  const recommendations = rankCropsForParcel({ year: comparisonYear, history, soil, climate });
  const chosen = recommendations.find((item) => item.crop === crop)!;
  const best = recommendations[0]!;
  const rotation = evaluateRotation(crop, comparisonYear, history);

  const requiredMm = (profile.waterMm[0] + profile.waterMm[1]) / 2;
  const effectiveRainMm = climate ? Math.round(climate.annualPrecipitationMm * calendar.rainShare) : null;
  const deficitMm = effectiveRainMm === null ? null : Math.max(0, Math.round(requiredMm - effectiveRainMm));
  const passes = deficitMm ? Math.ceil(deficitMm / IRRIGATION_DOSE_MM) : 0;
  const criticalPeriod = period(calendar.criticalMonths);

  const waterFactor = effectiveRainMm === null ? 1 : Math.max(0.6, Math.min(1, effectiveRainMm / requiredMm));
  const scoreFactor = Math.max(0.6, Math.min(1.05, 0.55 + (chosen.score / 100) * 0.5));
  const expectedYieldTHa = Math.round(profile.referenceYieldTHa * scoreFactor * waterFactor * 10) / 10;
  const expectedProfitEurHa = roundTo5(expectedYieldTHa * calendar.priceEurPerT - calendar.costEurHa);

  const previous = [...history].sort((a, b) => b.year - a.year)[0];
  const nitrogenCredit = previous && CROPS[previous.crop].family === 'LEGUME' ? 30 : 0;
  const [nPerT, pPerT, kPerT] = calendar.nutrientsPerT;
  const nitrogen = Math.max(0, expectedYieldTHa * nPerT - nitrogenCredit);
  const dateOf = (key: PlanStepKey) => schedule.steps.find((step) => step.key === key)?.date ?? null;
  const hasTopDressing = calendar.topDressingDays !== null;
  const fertilization: FertilizationStage[] = [
    {
      stage: 'Fertilizare de bază',
      date: dateOf('BASE_FERTILIZATION'),
      nKgHa: roundTo5(hasTopDressing ? nitrogen * 0.3 : nitrogen),
      pKgHa: roundTo5(expectedYieldTHa * pPerT),
      kKgHa: roundTo5(expectedYieldTHa * kPerT),
    },
    ...(hasTopDressing
      ? [{ stage: 'Fertilizare fazială', date: dateOf('TOP_DRESSING'), nKgHa: roundTo5(nitrogen * 0.7), pKgHa: 0, kKgHa: 0 }]
      : []),
  ];

  const risks: string[] = [];
  if (deficitMm) {
    risks.push(`Secetă în perioada ${criticalPeriod}.`);
    risks.push(`Deficit de precipitații estimat la ${deficitMm} mm pe sezon.`);
  }
  if (rotation.score < 70) risks.push(rotation.explanation);
  if (soil && (soil.ph < profile.phRange[0] || soil.ph > profile.phRange[1])) {
    risks.push(`pH-ul de ${soil.ph} este în afara intervalului optim ${profile.phRange[0]}-${profile.phRange[1]}.`);
  }
  if (risks.length === 0) risks.push('Datele disponibile nu indică riscuri majore.');

  const advice = [
    deficitMm
      ? `Planifică minimum ${passes} ${passes === 1 ? 'ciclu' : 'cicluri'} de irigare de ${IRRIGATION_DOSE_MM} mm în perioada critică de dezvoltare (${criticalPeriod}).`
      : effectiveRainMm === null
        ? `Verifică rezerva de apă înainte de perioada critică (${criticalPeriod}).`
        : `Precipitațiile medii acoperă necesarul de apă. Urmărește umiditatea solului în perioada ${criticalPeriod}.`,
  ];
  if (best.crop !== crop && best.score - chosen.score >= 10) {
    advice.push(`${best.name} are un scor mai bun pe această parcelă: ${best.score}%.`);
  }

  const lastCropName = input.lastCropName ?? (previous ? CROPS[previous.crop].name : null);

  return {
    year,
    crop,
    cropName: profile.name,
    rotationScore: rotation.score,
    rotationExplanation: rotation.explanation,
    sowingDate: schedule.sowingDate,
    harvestDate: schedule.harvestDate,
    steps: schedule.steps,
    irrigation: { requiredMm, effectiveRainMm, deficitMm, passes, doseMm: IRRIGATION_DOSE_MM, criticalPeriod },
    fertilization,
    monitoring: schedule.steps
      .filter((step) => step.key === 'EMERGENCE_CHECK' || step.key === 'PEST_MONITORING' || step.key === 'DISEASE_MONITORING')
      .map((step) => ({ title: step.title, date: step.date })),
    recommendations,
    report: {
      parcelName,
      areaHa: Math.round(areaHa * 100) / 100,
      soilType: soil?.label ?? null,
      lastCropName,
      plannedCropName: profile.name,
      recommendedCrop: best.crop,
      recommendedCropName: best.name,
      recommendedScore: best.score,
      compatibilityScore: chosen.score,
      expectedYieldTHa,
      expectedProfitEurHa,
      priceEurPerT: calendar.priceEurPerT,
      costEurHa: calendar.costEurHa,
      risks,
      recommendation: advice.join(' '),
      missingData: chosen.missingData,
    },
  };
}
