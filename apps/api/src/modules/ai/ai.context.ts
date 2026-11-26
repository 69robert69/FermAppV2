import { CROPS, ECONOMIC_CATEGORIES, ECONOMIC_LABELS, economicInsights, estimateParcelTillage, formatEuro, isTillageSystem, parseTillageHistory, rankRotationOptions, CROP_CODES, TILLAGE_SYSTEMS, tillageRecommendations, vegetationHealth } from '@fermapp/domain';
import { getForecast, upcomingHours } from '../../integrations/openMeteo';
import { logger } from '../../lib/logger';
import { prisma } from '../../lib/prisma';
import { farmEconomics } from '../economics/economics.service';
import { WORK_LABELS } from '../work/work.service';

export interface ParcelContext {
  parcel: { name: string; code: string; areaHa: number; locality: string | null; county: string | null };
  year: number;
  currentCrop: string | null;
  cropHistory: { year: number; crop: string; yieldTHa: number | null }[];
  soil: { label: string | null; ph: number | null; organicMatterPct: number | null; clayPct: number | null; waterHoldingMmPerM: number | null } | null;
  vegetation: { date: string; ndvi: number; ndre: number | null; healthIndex: number; label: string } | null;
  recentWork: { date: string; work: string; status: string }[];
  rotationNextYear: { crop: string; score: number; verdict: string; explanation: string }[];
  weatherNext7Days: { precipitationMm: number; tMinC: number; tMaxC: number; maxWindKmh: number } | null;
  currentWindKmh: number | null;
  /** Soil tillage technology and what it implies for the parcel. */
  tillage: {
    system: string;
    soilHealthScore: number;
    waterRetentionStars: number;
    estimatedCostPerHa: number;
    estimatedFuelLPerHa: number;
    discouragedOperations: string[];
    advice: string[];
    history: { year: number; system: string }[];
  } | null;
  /** Real production cost and profit of the season, in euro. */
  economics: {
    costPerHaEur: number;
    totalCostEur: number;
    shareByCategoryPct: Record<string, number>;
    expectedProfitPerHaEur: number | null;
    actualProfitPerHaEur: number | null;
    findings: string[];
  } | null;
}

/** Parcel data, read only from the authenticated user's farm. */
export async function buildParcelContext(farmId: string, parcelId: string): Promise<ParcelContext | null> {
  const parcel = await prisma.parcel.findFirst({
    where: { id: parcelId, farmId },
    include: {
      seasons: { orderBy: { year: 'desc' }, take: 10 },
      soilProfile: true,
      satellite: { orderBy: { date: 'desc' }, take: 1 },
      workSessions: { orderBy: { startedAt: 'desc' }, take: 8 },
    },
  });
  if (!parcel) return null;

  const year = new Date().getFullYear();
  const current = parcel.seasons.find((season) => season.year === year);
  const soil = parcel.soilProfile?.status === 'READY' ? parcel.soilProfile : null;
  const observation = parcel.satellite[0];

  const forecast = await getForecast(parcel.centerLat, parcel.centerLng).catch((error) => {
    logger.warn('Forecast unavailable for the assistant', (error as Error).message);
    return null;
  });
  const week = forecast?.days.slice(1, 8) ?? [];

  return {
    parcel: {
      name: parcel.name,
      code: parcel.code,
      areaHa: Math.round(parcel.areaHa * 100) / 100,
      locality: parcel.locality,
      county: parcel.county,
    },
    year,
    currentCrop: current ? CROPS[current.crop].name : null,
    cropHistory: parcel.seasons.map((season) => ({
      year: season.year,
      crop: CROPS[season.crop].name,
      yieldTHa: season.yieldT != null ? Math.round((season.yieldT / parcel.areaHa) * 100) / 100 : null,
    })),
    soil: soil
      ? {
          label: soil.label,
          ph: soil.ph,
          organicMatterPct: soil.organicMatterPct,
          clayPct: soil.clayPct,
          waterHoldingMmPerM: soil.waterHoldingMmPerM,
        }
      : null,
    vegetation: observation
      ? {
          date: observation.date.toISOString().slice(0, 10),
          ndvi: observation.ndvi,
          ndre: observation.ndre,
          healthIndex: observation.healthIndex,
          label: vegetationHealth(observation.ndvi, observation.ndre).label,
        }
      : null,
    recentWork: parcel.workSessions.map((work) => ({
      date: work.startedAt.toISOString().slice(0, 10),
      work: WORK_LABELS[work.type],
      status: work.status,
    })),
    rotationNextYear: rankRotationOptions(year + 1, parcel.seasons, CROP_CODES)
      .slice(0, 5)
      .map((option) => ({
        crop: CROPS[option.crop].name,
        score: option.score,
        verdict: option.verdict,
        explanation: option.explanation,
      })),
    weatherNext7Days: week.length
      ? {
          precipitationMm: Math.round(week.reduce((sum, day) => sum + day.precipitationMm, 0) * 10) / 10,
          tMinC: Math.min(...week.map((day) => day.tMinC)),
          tMaxC: Math.max(...week.map((day) => day.tMaxC)),
          maxWindKmh: Math.max(...week.map((day) => day.windMaxKmh ?? 0)),
        }
      : null,
    currentWindKmh: forecast ? (upcomingHours(forecast)[0]?.windKmh ?? null) : null,
    tillage: tillageContext(parcel.tillageSystem, parcel.tillageHistory, parcel.areaHa, current?.crop ?? null),
    economics: await economicsContext(farmId, parcelId, year, parcel.tillageSystem),
  };
}

async function economicsContext(farmId: string, parcelId: string, year: number, tillage: string | null): Promise<ParcelContext['economics']> {
  const farm = await farmEconomics(farmId, year, parcelId).catch((error) => {
    logger.warn('Economics unavailable for the assistant', error);
    return null;
  });
  const item = farm?.parcels[0];
  if (!item || item.result.season.total <= 0) return null;
  const { season } = item.result;
  return {
    costPerHaEur: season.perHa,
    totalCostEur: season.total,
    shareByCategoryPct: Object.fromEntries(
      ECONOMIC_CATEGORIES.filter((category) => season.byCategory[category] > 0).map((category) => [ECONOMIC_LABELS[category], Math.round((season.byCategory[category] / season.total) * 100)]),
    ),
    expectedProfitPerHaEur: item.result.expected?.profitPerHa ?? null,
    actualProfitPerHaEur: item.result.actual?.profitPerHa ?? null,
    findings: economicInsights(item.result, { tillage: isTillageSystem(tillage) ? tillage : null }).map((insight) => insight.message),
  };
}

function tillageContext(system: string | null, history: unknown, areaHa: number, crop: (typeof CROP_CODES)[number] | null): ParcelContext['tillage'] {
  if (!isTillageSystem(system)) return null;
  const profile = TILLAGE_SYSTEMS[system];
  const estimate = estimateParcelTillage(system, areaHa);
  return {
    system: profile.name,
    soilHealthScore: estimate.soilHealth,
    waterRetentionStars: estimate.waterRetention,
    estimatedCostPerHa: estimate.costPerHa,
    estimatedFuelLPerHa: estimate.fuelPerHa,
    discouragedOperations: Object.values(profile.discouragedWork).filter((reason): reason is string => Boolean(reason)),
    advice: tillageRecommendations(system, { crop }).map((item) => item.message),
    history: parseTillageHistory(history).map((entry) => ({ year: entry.year, system: TILLAGE_SYSTEMS[entry.system].name })),
  };
}

/** Answer computed from data, used when the AI model is not configured or does not respond. */
export function summarizeWithoutModel(context: ParcelContext | null): string {
  if (!context) {
    return 'Choose a parcel to get an analysis based on its history, soil and forecast.';
  }
  const parts: string[] = [];
  if (context.soil?.ph != null && context.soil.organicMatterPct != null) {
    parts.push(`Parcel ${context.parcel.name} has pH ${context.soil.ph} and ${context.soil.organicMatterPct}% organic matter.`);
  } else {
    parts.push(`Parcel ${context.parcel.name} covers ${context.parcel.areaHa} ha, and its soil profile is not available yet.`);
  }
  if (context.economics) parts.push(`The real production cost is ${formatEuro(context.economics.costPerHaEur, 2)}/ha. ${context.economics.findings.slice(0, 2).join(' ')}`.trim());
  if (context.tillage) parts.push(`The parcel is farmed with ${context.tillage.system} (soil health ${context.tillage.soilHealthScore}/100). ${context.tillage.advice[0] ?? ''}`.trim());
  const last = context.cropHistory.find((season) => season.year < context.year);
  if (context.currentCrop) parts.push(`The ${context.year} crop is ${context.currentCrop.toLowerCase()}.`);
  else if (last) parts.push(`The last recorded crop is ${last.crop.toLowerCase()}, in ${last.year}.`);

  const good = context.rotationNextYear.filter((option) => option.verdict === 'RECOMMENDED').slice(0, 2);
  if (good.length > 0) {
    parts.push(`For next season, ${good.map((option) => option.crop.toLowerCase()).join(' or ')} is recommended.`);
  }
  const avoid = context.rotationNextYear.length === 0 ? null : context.rotationNextYear.at(-1);
  if (avoid && avoid.verdict === 'NOT_RECOMMENDED') parts.push(avoid.explanation);
  if (context.weatherNext7Days) {
    parts.push(
      `Over the next 7 days, ${context.weatherNext7Days.precipitationMm} mm of rainfall is expected, with temperatures between ${Math.round(context.weatherNext7Days.tMinC)} and ${Math.round(context.weatherNext7Days.tMaxC)} °C.`,
    );
  }
  if (context.vegetation) {
    parts.push(`The latest satellite image (${context.vegetation.date}) shows NDVI ${context.vegetation.ndvi}: ${context.vegetation.label.toLowerCase()}.`);
  }
  return parts.join(' ');
}
