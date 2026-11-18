import {
  CROP_CODES,
  CROPS,
  cropProgress,
  evaluateRotation,
  nextRecommendedAction,
  parcelEconomics,
  rankRotationOptions,
  soilIndices,
  sprayingAlerts,
  vegetationHealth,
  type NextAction,
  type NextActionInput,
  type ParcelPlanData,
} from '@fermapp/domain';
import type { Parcel, SatelliteObservation, SoilProfile } from '@prisma/client';
import { getForecast, localHour, upcomingHours } from '../../integrations/openMeteo';
import { requireParcel } from '../../lib/access';
import { logger } from '../../lib/logger';
import { prisma } from '../../lib/prisma';
import type { AuthContext } from '../../lib/tokens';
import { irrigationInclude, serializeIrrigation } from '../irrigation/irrigation.service';
import { planForRole } from '../plans/plan.service';
import { serializeWork, workInclude } from '../work/work.service';

const DAY_MS = 86_400_000;
const round = (value: number, digits = 1) => Math.round(value * 10 ** digits) / 10 ** digits;

const SYSTEM_LABELS = { REEL: 'Tambur', PIVOT: 'Pivot', LINEAR: 'Liniar' } as const;

export async function parcelWeather(parcel: Pick<Parcel, 'id' | 'centerLat' | 'centerLng'>) {
  const forecast = await getForecast(parcel.centerLat, parcel.centerLng);
  const hours = upcomingHours(forecast);
  const today = localHour(forecast).slice(0, 10);

  const rainChance = new Map<string, number>();
  for (const hour of forecast.hours) {
    const date = hour.time.slice(0, 10);
    rainChance.set(date, Math.max(rainChance.get(date) ?? 0, hour.precipitationProbabilityPct ?? 0));
  }
  const days = forecast.days
    .filter((day) => day.date >= today)
    .map((day) => ({ ...day, rainProbabilityPct: rainChance.get(day.date) ?? null }));

  // Istoricul zilnic se păstrează pentru analize, iar o eroare aici nu afectează prognoza.
  const current = days[0];
  if (current) {
    const values = {
      tMaxC: current.tMaxC,
      tMinC: current.tMinC,
      precipitationMm: current.precipitationMm,
      humidityPct: current.humidityPct,
      windMaxKmh: current.windMaxKmh,
      et0Mm: current.et0Mm,
    };
    await prisma.weatherData
      .upsert({
        where: { parcelId_date: { parcelId: parcel.id, date: new Date(current.date) } },
        create: { ...values, parcelId: parcel.id, date: new Date(current.date) },
        update: values,
      })
      .catch((error) => logger.warn('Salvare meteo eșuată', (error as Error).message));
  }

  return {
    timezone: forecast.timezone,
    current: hours[0] ?? null,
    today: days[0] ?? null,
    next7: days.slice(1, 8),
    next14: days.slice(1, 15),
    alerts: sprayingAlerts(hours.slice(0, 6)),
  };
}

export function soilSection(profile: SoilProfile | null) {
  if (!profile) return { status: 'PENDING' as const };
  const ready =
    profile.status === 'READY' &&
    profile.ph !== null &&
    profile.organicMatterPct !== null &&
    profile.clayPct !== null &&
    profile.sandPct !== null &&
    profile.waterHoldingMmPerM !== null;
  return {
    ...profile,
    indices: ready
      ? soilIndices({
          ph: profile.ph!,
          organicMatterPct: profile.organicMatterPct!,
          clayPct: profile.clayPct!,
          sandPct: profile.sandPct!,
          waterHoldingMmPerM: profile.waterHoldingMmPerM!,
        })
      : null,
  };
}

export async function rotationScore(parcelId: string, now = new Date()) {
  const seasons = await prisma.cropSeason.findMany({
    where: { parcelId },
    orderBy: { year: 'desc' },
    take: 10,
    select: { year: true, crop: true },
  });
  const year = now.getFullYear();
  const current = seasons.find((season) => season.year === year);
  const history = seasons.map((season) => ({ ...season, name: CROPS[season.crop].name }));
  if (current) {
    return { ...evaluateRotation(current.crop, year, seasons), mode: 'CURRENT' as const, history };
  }
  const best = rankRotationOptions(year, seasons, CROP_CODES)[0]!;
  return { ...best, mode: 'SUGGESTED' as const, history };
}

export async function economicsFor(parcel: Pick<Parcel, 'id' | 'areaHa'>, year = new Date().getFullYear()) {
  const [costs, season, incomes] = await Promise.all([
    prisma.costEntry.findMany({ where: { parcelId: parcel.id, year } }),
    prisma.cropSeason.findUnique({ where: { parcelId_year: { parcelId: parcel.id, year } } }),
    prisma.income.findMany({ where: { parcelId: parcel.id, year } }),
  ]);
  return {
    year,
    seasonId: season?.id ?? null,
    expectedYieldTHa: season?.expectedYieldTHa ?? null,
    expectedPricePerT: season?.expectedPricePerT == null ? null : Number(season.expectedPricePerT),
    ...parcelEconomics({
      areaHa: parcel.areaHa,
      costs: costs.map((cost) => ({ category: cost.category, amount: Number(cost.amount) })),
      expectedYieldTHa: season?.expectedYieldTHa,
      expectedPricePerT: season?.expectedPricePerT == null ? null : Number(season.expectedPricePerT),
      actualYieldT: season?.yieldT,
      actualPricePerT: season?.actualPricePerT == null ? null : Number(season.actualPricePerT),
      recordedIncome: incomes.reduce((sum, income) => sum + Number(income.amount), 0),
    }),
  };
}

/** Cea mai apropiată observație de data țintă, dacă există una în toleranța dată. */
function nearest(observations: SatelliteObservation[], target: number, toleranceDays: number, exclude?: string) {
  let best: SatelliteObservation | null = null;
  let bestDistance = toleranceDays * DAY_MS;
  for (const item of observations) {
    if (item.id === exclude) continue;
    const distance = Math.abs(item.date.getTime() - target);
    if (distance <= bestDistance) {
      best = item;
      bestDistance = distance;
    }
  }
  return best;
}

export function ndviComparison(observations: SatelliteObservation[], now = new Date()) {
  const view = (item: SatelliteObservation | null) =>
    item
      ? {
          date: item.date.toISOString().slice(0, 10),
          ndvi: item.ndvi,
          ndre: item.ndre,
          healthIndex: item.healthIndex,
          health: vegetationHealth(item.ndvi, item.ndre),
        }
      : null;
  const latest = observations[0] ?? null;
  return {
    latest: view(latest),
    sevenDaysAgo: view(nearest(observations, now.getTime() - 7 * DAY_MS, 4, latest?.id)),
    thirtyDaysAgo: view(nearest(observations, now.getTime() - 30 * DAY_MS, 8, latest?.id)),
  };
}

async function latestSoilMoisture(parcelId: string) {
  const reading = await prisma.iotReading.findFirst({
    where: {
      device: { parcelId, kind: 'SOIL_MOISTURE' },
      recordedAt: { gte: new Date(Date.now() - 2 * DAY_MS) },
    },
    orderBy: { recordedAt: 'desc' },
  });
  return reading?.value ?? null;
}

/** Pentru o cultură planificată, următoarea acțiune este primul pas nefinalizat din plan. */
function nextPlanAction(
  plan: ParcelPlanData,
  tasks: readonly { planStepKey: string | null; status: string; title: string; dueDate: Date }[],
  now: Date,
): NextAction | null {
  const next = tasks
    .filter((task) => task.planStepKey && task.status !== 'DONE')
    .sort((a, b) => a.dueDate.getTime() - b.dueDate.getTime())[0];
  if (!next) return null;
  return {
    code: 'PLAN_STEP',
    action: next.title,
    reason: `Este următorul pas din planul tehnologic pentru ${plan.cropName.toLowerCase()}.`,
    priority: 'MEDIUM',
    riskLevel: 'LOW',
    confidencePct: 80,
    dueInDays: Math.max(0, Math.ceil((next.dueDate.getTime() - now.getTime()) / DAY_MS)),
  };
}

export async function buildIntelligence(auth: AuthContext, parcelId: string) {
  await requireParcel(auth.farmId, parcelId);
  const now = new Date();
  const year = now.getFullYear();

  const [parcel, activeWork, activeIrrigation, recentWork, tasks, soilMoisturePct, photos, observations, plans] = await Promise.all([
    prisma.parcel.findUniqueOrThrow({
      where: { id: parcelId },
      include: {
        seasons: { where: { year } },
        soilProfile: true,
        satellite: { orderBy: { date: 'desc' }, take: 40 },
      },
    }),
    prisma.workSession.findMany({
      where: { parcelId, status: { in: ['RUNNING', 'PAUSED'] } },
      include: {
        ...workInclude,
        locations: { orderBy: { recordedAt: 'desc' }, take: 1 },
      },
      orderBy: { startedAt: 'desc' },
    }),
    prisma.irrigationRun.findMany({
      where: { parcelId, status: { in: ['RUNNING', 'PAUSED'] } },
      include: irrigationInclude,
      orderBy: { startedAt: 'asc' },
    }),
    prisma.workSession.findMany({
      where: { parcelId, status: 'COMPLETED', startedAt: { gte: new Date(now.getTime() - 60 * DAY_MS) } },
      select: { type: true, startedAt: true },
    }),
    prisma.task.findMany({
      where: { parcelId },
      include: { assignee: { select: { id: true, fullName: true } } },
      orderBy: [{ status: 'asc' }, { dueDate: 'asc' }],
      take: 50,
    }),
    latestSoilMoisture(parcelId),
    prisma.photo.findMany({ where: { parcelId }, orderBy: { createdAt: 'desc' }, take: 12 }),
    prisma.observation.findMany({
      where: { parcelId },
      include: { author: { select: { id: true, fullName: true } } },
      orderBy: { createdAt: 'desc' },
      take: 10,
    }),
    prisma.parcelPlan.findMany({ where: { parcelId }, orderBy: { createdAt: 'desc' }, take: 10 }),
  ]);

  const activePlan = plans.filter((item) => item.active && item.year >= year).sort((a, b) => a.year - b.year)[0] ?? null;
  const planData = activePlan ? (activePlan.data as unknown as ParcelPlanData) : null;

  const weather = await parcelWeather(parcel).catch((error) => {
    logger.warn('Meteo indisponibil în panoul parcelei', (error as Error).message);
    return null;
  });

  // Ultima cultură declarată în wizard nu este cultura din câmp când planul începe într-un an viitor.
  const recorded = parcel.seasons[0] ?? null;
  const season = recorded && activePlan && activePlan.year > year && !recorded.sowingDate ? null : recorded;
  const progress = season
    ? cropProgress({
        crop: season.crop,
        sowingDate: season.sowingDate,
        harvestDate: season.harvestDate,
        expectedHarvestDate: season.expectedHarvestDate,
        now,
      })
    : null;

  const week = weather?.next7 ?? [];
  const mean = (values: number[]) => values.reduce((sum, value) => sum + value, 0) / Math.max(1, values.length);
  const weatherInput: NextActionInput['weather'] = week.length
    ? {
        meanHumidityPct: mean(week.map((day) => day.humidityPct ?? 0)),
        meanTempC: mean(week.map((day) => day.tMeanC ?? (day.tMaxC + day.tMinC) / 2)),
        minTempC: Math.min(...week.map((day) => day.tMinC)),
        rainyDays: week.filter((day) => day.precipitationMm >= 1).length,
        precipitationMm: week.reduce((sum, day) => sum + day.precipitationMm, 0),
        et0Mm: week.reduce((sum, day) => sum + (day.et0Mm ?? 0), 0),
      }
    : null;

  const irrigated = parcel.irrigatedAreaHa === null ? null : Math.min(parcel.irrigatedAreaHa, parcel.areaHa);
  const count = (status: string) => tasks.filter((task) => task.status === status).length;

  return {
    generatedAt: now.toISOString(),
    header: {
      id: parcel.id,
      name: parcel.name,
      code: parcel.code,
      areaHa: round(parcel.areaHa, 2),
      county: parcel.county,
      locality: parcel.locality,
      lat: parcel.centerLat,
      lng: parcel.centerLng,
    },
    crop: season
      ? {
          seasonId: season.id,
          code: season.crop,
          name: CROPS[season.crop].name,
          year: season.year,
          sowingDate: season.sowingDate,
          ...progress!,
        }
      : null,
    rotation: await rotationScore(parcel.id, now),
    irrigation: {
      configured: irrigated !== null,
      irrigatedHa: irrigated === null ? null : round(irrigated, 2),
      notIrrigatedHa: irrigated === null ? null : round(parcel.areaHa - irrigated, 2),
      coveragePct: irrigated === null ? null : Math.round((irrigated / parcel.areaHa) * 100),
    },
    activeIrrigation: activeIrrigation.map((run, index) => ({
      ...serializeIrrigation(run, now),
      label: `${SYSTEM_LABELS[run.systemType]} #${index + 1}`,
    })),
    activeWork: activeWork.map(({ locations, ...work }) => ({
      ...serializeWork(work, now),
      lastLocation: locations[0] ? { ...locations[0], id: locations[0].id.toString() } : null,
    })),
    nextAction:
      (!season && planData ? nextPlanAction(planData, tasks, now) : null) ??
      nextRecommendedAction({
        crop: season?.crop,
        progress,
        weather: weatherInput,
        soilMoisturePct,
        healthClass: parcel.satellite[0]?.healthClass,
        recentWork: recentWork.map((work) => ({ type: work.type, date: work.startedAt })),
        now,
      }),
    plan: activePlan
      ? { id: activePlan.id, year: activePlan.year, createdAt: activePlan.createdAt, data: planForRole(planData!, auth.role) }
      : null,
    planHistory: plans.map((item) => ({
      id: item.id,
      year: item.year,
      cropName: CROPS[item.crop].name,
      trigger: item.trigger,
      active: item.active,
      createdAt: item.createdAt,
    })),
    tasks: {
      summary: { todo: count('TODO'), inProgress: count('IN_PROGRESS'), done: count('DONE') },
      items: tasks,
    },
    weather,
    soil: soilSection(parcel.soilProfile),
    // Datele financiare nu se trimit operatorilor.
    economics: auth.role === 'OPERATOR' ? null : await economicsFor(parcel, year),
    satellite: ndviComparison(parcel.satellite, now),
    photos,
    observations,
  };
}
