import { CROPS, type CropCode } from './crops';
import { summarizeCosts, type CostSummaryInput } from './costs';

/** Indicative duration from sowing to harvest, in days. */
export const CROP_SEASON_DAYS: Record<CropCode, number> = {
  WHEAT: 270,
  BARLEY: 250,
  CORN: 170,
  SUNFLOWER: 125,
  RAPESEED: 300,
  SOY: 130,
  ALFALFA: 180,
  OAT: 110,
  POTATO: 120,
  BEET: 190,
  VEGETABLES: 100,
};

const DAY_MS = 86_400_000;
const clamp = (value: number, min = 0, max = 100) => Math.max(min, Math.min(max, value));
const round1 = (value: number) => Math.round(value * 10) / 10;

export type CropProgressStatus = 'NOT_SOWN' | 'GROWING' | 'READY' | 'HARVESTED';

export interface CropProgress {
  status: CropProgressStatus;
  vegetationDay: number;
  totalDays: number;
  percent: number;
  estimatedHarvestDate: string | null;
}

export function cropProgress(input: {
  crop: CropCode;
  sowingDate?: Date | null;
  harvestDate?: Date | null;
  expectedHarvestDate?: Date | null;
  now?: Date;
}): CropProgress {
  const { crop, sowingDate, harvestDate, expectedHarvestDate, now = new Date() } = input;
  const defaultDays = CROP_SEASON_DAYS[crop];
  if (!sowingDate || sowingDate > now) {
    return {
      status: 'NOT_SOWN',
      vegetationDay: 0,
      totalDays: defaultDays,
      percent: 0,
      estimatedHarvestDate: expectedHarvestDate?.toISOString() ?? null,
    };
  }
  const estimated = harvestDate ?? expectedHarvestDate ?? new Date(sowingDate.getTime() + defaultDays * DAY_MS);
  const totalDays = Math.max(1, Math.round((estimated.getTime() - sowingDate.getTime()) / DAY_MS));
  const reference = harvestDate && harvestDate < now ? harvestDate : now;
  const vegetationDay = Math.min(totalDays, Math.floor((reference.getTime() - sowingDate.getTime()) / DAY_MS) + 1);
  const percent = Math.round((vegetationDay / totalDays) * 100);
  const status: CropProgressStatus = harvestDate && harvestDate <= now ? 'HARVESTED' : percent >= 95 ? 'READY' : 'GROWING';
  return { status, vegetationDay, totalDays, percent, estimatedHarvestDate: estimated.toISOString() };
}

export interface SoilIndices {
  soilScore: number;
  fertilityIndex: number;
  waterRetentionIndex: number;
}

/** Indicative 0-100 indices, computed from soil properties. */
export function soilIndices(soil: {
  ph: number;
  organicMatterPct: number;
  clayPct: number;
  sandPct: number;
  waterHoldingMmPerM: number;
}): SoilIndices {
  const phDistance = soil.ph < 6.2 ? 6.2 - soil.ph : soil.ph > 7.5 ? soil.ph - 7.5 : 0;
  const phScore = clamp(100 - phDistance * 40);
  const organicScore = clamp((soil.organicMatterPct / 4) * 100);
  const clayDistance = soil.clayPct < 15 ? 15 - soil.clayPct : soil.clayPct > 40 ? soil.clayPct - 40 : 0;
  const textureScore = clamp(100 - clayDistance * 2.5 - Math.max(0, soil.sandPct - 60) * 2);
  const fertilityIndex = Math.round(organicScore * 0.6 + phScore * 0.4);
  const waterRetentionIndex = Math.round(clamp((soil.waterHoldingMmPerM / 200) * 100));
  return {
    fertilityIndex,
    waterRetentionIndex,
    soilScore: Math.round(fertilityIndex * 0.4 + waterRetentionIndex * 0.3 + textureScore * 0.3),
  };
}

export interface SprayAlert {
  code: 'WIND' | 'RAIN';
  message: string;
}

export const SPRAY_WIND_LIMIT_KMH = 15;
export const SPRAY_RAIN_LIMIT_PCT = 60;

/** Spraying conditions for the next few hours. */
export function sprayingAlerts(
  hours: readonly { windKmh: number; precipitationProbabilityPct?: number | null }[],
): SprayAlert[] {
  const alerts: SprayAlert[] = [];
  const wind = hours[0]?.windKmh ?? 0;
  if (wind > SPRAY_WIND_LIMIT_KMH) {
    alerts.push({ code: 'WIND', message: `Wind ${Math.round(wind)} km/h. Do not spray.` });
  }
  const rain = Math.max(0, ...hours.map((hour) => hour.precipitationProbabilityPct ?? 0));
  if (rain > SPRAY_RAIN_LIMIT_PCT) {
    alerts.push({ code: 'RAIN', message: `Rain probability ${Math.round(rain)}%. Postpone spraying.` });
  }
  return alerts;
}

export type ActionPriority = 'LOW' | 'MEDIUM' | 'HIGH';

export interface NextAction {
  code: string;
  action: string;
  reason: string;
  priority: ActionPriority;
  riskLevel: ActionPriority;
  confidencePct: number;
  dueInDays: number | null;
}

export interface NextActionInput {
  crop?: CropCode | null;
  progress?: Pick<CropProgress, 'status' | 'percent'> | null;
  weather?: {
    meanHumidityPct: number;
    meanTempC: number;
    minTempC: number;
    rainyDays: number;
    precipitationMm: number;
    et0Mm: number;
  } | null;
  soilMoisturePct?: number | null;
  healthClass?: string | null;
  recentWork?: readonly { type: string; date: Date }[];
  now?: Date;
}

const NITROGEN_CROPS: readonly CropCode[] = ['WHEAT', 'BARLEY', 'CORN', 'RAPESEED', 'OAT'];
const LOW_SOIL_MOISTURE_PCT = 30;
const WATER_DEFICIT_MM = 20;

/** Picks the next action using agronomic rules, from most to least urgent. */
export function nextRecommendedAction(input: NextActionInput): NextAction {
  const { crop, progress, weather, soilMoisturePct, healthClass, recentWork = [], now = new Date() } = input;
  const confidence =
    50 +
    (weather ? 15 : 0) +
    (progress && progress.status !== 'NOT_SOWN' ? 15 : 0) +
    (soilMoisturePct != null ? 10 : 0) +
    (healthClass ? 5 : 0);
  const result = (action: Omit<NextAction, 'confidencePct'>): NextAction => ({ ...action, confidencePct: confidence });
  const workedWithin = (type: string, days: number) =>
    recentWork.some((work) => work.type === type && now.getTime() - work.date.getTime() <= days * DAY_MS);

  if (!crop) {
    return result({
      code: 'PLAN_CROP',
      action: 'Plan the crop for this parcel.',
      reason: 'The parcel has no crop recorded for the current year.',
      priority: 'MEDIUM',
      riskLevel: 'LOW',
      dueInDays: null,
    });
  }
  const name = CROPS[crop].name.toLowerCase();

  if (!progress || progress.status === 'NOT_SOWN') {
    return result({
      code: 'SET_SOWING',
      action: 'Fill in the sowing date.',
      reason: `Without this date the stage of the ${name} crop cannot be estimated.`,
      priority: 'MEDIUM',
      riskLevel: 'LOW',
      dueInDays: null,
    });
  }
  if (progress.status === 'HARVESTED') {
    return result({
      code: 'POST_HARVEST',
      action: 'Prepare the land for the next crop.',
      reason: `The ${name} crop has been harvested.`,
      priority: 'LOW',
      riskLevel: 'LOW',
      dueInDays: 14,
    });
  }
  if (progress.status === 'READY') {
    return result({
      code: 'HARVEST',
      action: 'Prepare for harvest.',
      reason: `The ${name} crop has reached ${progress.percent}% of its estimated cycle. Check grain moisture.`,
      priority: 'HIGH',
      riskLevel: 'MEDIUM',
      dueInDays: 7,
    });
  }
  if (weather && weather.minTempC <= 0 && progress.percent < 30) {
    return result({
      code: 'FROST',
      action: 'Check the plants after the expected frost.',
      reason: `${Math.round(weather.minTempC)} °C is expected and the crop is at a sensitive stage.`,
      priority: 'HIGH',
      riskLevel: 'HIGH',
      dueInDays: 2,
    });
  }
  if (healthClass === 'PROBLEM') {
    return result({
      code: 'SCOUT',
      action: 'Inspect the parcel in the field.',
      reason: 'The latest satellite image indicates a possible vegetation problem.',
      priority: 'HIGH',
      riskLevel: 'HIGH',
      dueInDays: 2,
    });
  }
  const waterDeficit = weather ? weather.et0Mm - weather.precipitationMm : 0;
  const lowMoisture = soilMoisturePct != null && soilMoisturePct < LOW_SOIL_MOISTURE_PCT;
  if ((lowMoisture || waterDeficit > WATER_DEFICIT_MM) && progress.percent >= 15 && progress.percent <= 85) {
    return result({
      code: 'IRRIGATE',
      action: 'Plan an irrigation within the next 3 days.',
      reason: lowMoisture
        ? `Soil moisture is ${Math.round(soilMoisturePct!)}%.`
        : `Evapotranspiration exceeds rainfall by ${Math.round(waterDeficit)} mm over the next 7 days.`,
      priority: 'HIGH',
      riskLevel: 'MEDIUM',
      dueInDays: 3,
    });
  }
  const foliarRisk =
    weather &&
    weather.meanHumidityPct >= 75 &&
    weather.meanTempC >= 12 &&
    weather.meanTempC <= 28 &&
    weather.rainyDays >= 2;
  if (foliarRisk && progress.percent >= 30 && progress.percent <= 80 && !workedWithin('SPRAYING', 10)) {
    return result({
      code: 'FUNGICIDE',
      action: 'Check for foliar diseases and prepare a fungicide treatment within 4 days.',
      reason: `Mean humidity of ${Math.round(weather.meanHumidityPct)}% and ${weather.rainyDays} rainy days raise the risk of foliar diseases.`,
      priority: 'HIGH',
      riskLevel: 'HIGH',
      dueInDays: 4,
    });
  }
  if (NITROGEN_CROPS.includes(crop) && progress.percent >= 15 && progress.percent <= 45 && !workedWithin('FERTILIZING', 30)) {
    return result({
      code: 'NITROGEN',
      action: 'Plan a nitrogen fertilization.',
      reason: `The ${name} crop is actively growing and no fertilization has been recorded in the last 30 days.`,
      priority: 'MEDIUM',
      riskLevel: 'MEDIUM',
      dueInDays: 7,
    });
  }
  if (progress.percent <= 25 && !workedWithin('SPRAYING', 21)) {
    return result({
      code: 'WEEDS',
      action: 'Check the level of weed infestation.',
      reason: 'In the early stages, weeds compete strongly with the crop for water and nutrients.',
      priority: 'MEDIUM',
      riskLevel: 'MEDIUM',
      dueInDays: 5,
    });
  }
  return result({
    code: 'MONITOR',
    action: 'Keep monitoring the crop.',
    reason: 'The available data does not indicate an urgent intervention.',
    priority: 'LOW',
    riskLevel: 'LOW',
    dueInDays: 7,
  });
}

export interface ParcelEconomics {
  seedsCost: number;
  fertilizerCost: number;
  chemicalCost: number;
  fuelCost: number;
  laborCost: number;
  irrigationCost: number;
  otherCost: number;
  totalCost: number;
  costPerHa: number;
  expectedIncome: number | null;
  expectedProfit: number | null;
  profitMarginPct: number | null;
  roiPct: number | null;
  actualIncome: number | null;
  actualProfit: number | null;
}

export function parcelEconomics(input: CostSummaryInput & { recordedIncome?: number }): ParcelEconomics {
  const summary = summarizeCosts(input);
  const expectedIncome = summary.estimatedRevenue;
  const expectedProfit = summary.estimatedProfit;
  const actualIncome = input.recordedIncome ? input.recordedIncome : summary.actualRevenue;
  return {
    seedsCost: summary.byCategory.SEED,
    fertilizerCost: summary.byCategory.FERTILIZER,
    chemicalCost: summary.byCategory.PESTICIDE,
    fuelCost: summary.byCategory.FUEL,
    laborCost: summary.byCategory.LABOR,
    irrigationCost: summary.byCategory.IRRIGATION,
    otherCost: summary.byCategory.OTHER,
    totalCost: summary.totalCost,
    costPerHa: summary.costPerHa,
    expectedIncome,
    expectedProfit,
    profitMarginPct: expectedIncome && expectedProfit !== null ? round1((expectedProfit / expectedIncome) * 100) : null,
    roiPct: summary.totalCost > 0 && expectedProfit !== null ? round1((expectedProfit / summary.totalCost) * 100) : null,
    actualIncome,
    actualProfit: actualIncome === null ? null : Math.round((actualIncome - summary.totalCost) * 100) / 100,
  };
}
