import {
  buildParcelPlan,
  classifySoil,
  CROPS,
  nextSeasonYear,
  OTHER_CROP,
  type CropCode,
  type CropHistoryEntry,
  type LastCrop,
  type ParcelPlanData,
  type PlanClimate,
  type PlanSoil,
  type SoilTexture,
} from '@fermapp/domain';
import type { Prisma, TaskType } from '@prisma/client';
import { getClimateNormals } from '../../integrations/nasaPower';
import { fetchSoilGrids } from '../../integrations/soilGrids';
import { cached } from '../../lib/cache';
import { logger } from '../../lib/logger';
import { prisma } from '../../lib/prisma';
import { emitToFarm } from '../../realtime/socket';

const SOIL_CACHE_SECONDS = 86_400;

export interface SiteData {
  soil: PlanSoil | null;
  climate: PlanClimate | null;
}

/** Soil and climate data for the location. An unavailable source does not stop planning. */
export async function loadSiteData(lat: number, lng: number, parcelId?: string): Promise<SiteData> {
  const profile = parcelId ? await prisma.soilProfile.findUnique({ where: { parcelId } }) : null;
  const saved: PlanSoil | null =
    profile?.status === 'READY' && profile.ph !== null && profile.texture !== null
      ? { ph: profile.ph, texture: profile.texture as SoilTexture, label: profile.label }
      : null;

  const [soil, climate] = await Promise.all([
    saved ??
      cached(`plan-soil:${lat.toFixed(4)}:${lng.toFixed(4)}`, SOIL_CACHE_SECONDS, async () => {
        const values = await fetchSoilGrids(lat, lng);
        if (!values) throw new Error('SoilGrids has no data for this location.');
        const soilClass = classifySoil(values);
        return { ph: values.ph, texture: soilClass.texture, label: soilClass.label };
      }).catch((error) => {
        logger.warn('Soil unavailable for plan', (error as Error).message);
        return null;
      }),
    getClimateNormals(lat, lng).catch((error) => {
      logger.warn('Climate unavailable for plan', (error as Error).message);
      return null;
    }),
  ]);
  return { soil, climate };
}

/** The parcel history, completed with the last crop declared in the wizard. */
export function historyWithLastCrop(history: CropHistoryEntry[], lastCrop: LastCrop, now = new Date()) {
  if (lastCrop === OTHER_CROP) return history;
  const year = nextSeasonYear(now) - 1;
  return [...history.filter((entry) => entry.year !== year), { crop: lastCrop, year }];
}

export const lastCropLabel = (lastCrop: LastCrop, name?: string | null) =>
  lastCrop === OTHER_CROP ? name?.trim() || 'Other crop' : CROPS[lastCrop].name;

export async function parcelCropHistory(parcelId: string, beforeYear?: number): Promise<CropHistoryEntry[]> {
  return prisma.cropSeason.findMany({
    where: { parcelId, ...(beforeYear === undefined ? {} : { year: { lt: beforeYear } }) },
    select: { crop: true, year: true },
    orderBy: { year: 'desc' },
  });
}

type Tx = Prisma.TransactionClient;

/** Replaces the year's plan and its not-started tasks. Work that was started or completed is kept. */
export async function savePlan(tx: Tx, input: { farmId: string; parcelId: string; plan: ParcelPlanData; trigger: 'WIZARD' | 'CROP_CHANGED' }) {
  const { farmId, parcelId, plan, trigger } = input;

  const kept = await tx.task.findMany({
    where: { parcelId, status: { not: 'TODO' }, plan: { parcelId, year: plan.year, crop: plan.crop } },
    select: { planStepKey: true },
  });
  const keptSteps = new Set(kept.map((task) => task.planStepKey));

  await tx.task.deleteMany({ where: { parcelId, status: 'TODO', plan: { parcelId, year: plan.year } } });
  await tx.parcelPlan.updateMany({ where: { parcelId, year: plan.year, active: true }, data: { active: false } });

  const saved = await tx.parcelPlan.create({
    data: {
      parcelId,
      year: plan.year,
      crop: plan.crop,
      trigger,
      data: plan as unknown as Prisma.InputJsonValue,
    },
  });

  await tx.task.createMany({
    data: plan.steps
      .filter((step) => !step.past && !keptSteps.has(step.key))
      .map((step) => ({
        farmId,
        parcelId,
        planId: saved.id,
        planStepKey: step.key,
        type: step.taskType as TaskType,
        title: `${step.title}: ${plan.cropName}`,
        description: `Generated automatically from the ${plan.year} technology plan.`,
        priority: step.priority,
        dueDate: new Date(step.date),
      })),
  });

  await tx.cropRecommendation.deleteMany({ where: { parcelId, year: plan.year } });
  await tx.cropRecommendation.createMany({
    data: plan.recommendations.map((item) => ({
      parcelId,
      year: plan.year,
      crop: item.crop,
      score: item.score,
      reasons: [item.reason, ...item.reasons],
    })),
  });

  await tx.parcel.update({ where: { id: parcelId }, data: { plannedCrop: plan.crop } });
  return saved;
}

export function announcePlan(farmId: string, parcelId: string) {
  emitToFarm(farmId, 'task:updated', { parcelId });
  emitToFarm(farmId, 'parcel:updated', { parcelId });
}

/**
 * Recalculates everything that depends on the crop: rotation, calendar, water, fertilization, monitoring,
 * tasks and recommendations. Completed years remain unchanged.
 */
export async function regeneratePlan(farmId: string, parcelId: string, year: number, cropChanged: boolean, now = new Date()) {
  if (year < now.getUTCFullYear()) return null;
  const [parcel, season, history] = await Promise.all([
    prisma.parcel.findUnique({ where: { id: parcelId } }),
    prisma.cropSeason.findUnique({ where: { parcelId_year: { parcelId, year } } }),
    parcelCropHistory(parcelId, year),
  ]);
  if (!parcel || !season) return null;

  const site = await loadSiteData(parcel.centerLat, parcel.centerLng, parcelId);
  const plan = buildParcelPlan({
    crop: season.crop,
    parcelName: parcel.name,
    areaHa: parcel.areaHa,
    history,
    ...site,
    year,
    sowingDate: season.sowingDate,
    now,
  });

  const saved = await prisma.$transaction(async (tx) => {
    const result = await savePlan(tx, { farmId, parcelId, plan, trigger: 'CROP_CHANGED' });
    await tx.cropSeason.update({
      where: { id: season.id },
      // Values entered by the user remain, as long as the crop is the same.
      data: {
        expectedHarvestDate: cropChanged || !season.expectedHarvestDate ? new Date(plan.harvestDate) : undefined,
        expectedYieldTHa: cropChanged || season.expectedYieldTHa === null ? plan.report.expectedYieldTHa : undefined,
      },
    });
    return result;
  });
  announcePlan(farmId, parcelId);
  return saved;
}

/** Saving a crop succeeds even when recalculating the plan fails. */
export async function regeneratePlanSafely(farmId: string, parcelId: string, year: number, cropChanged = false) {
  try {
    await regeneratePlan(farmId, parcelId, year, cropChanged);
  } catch (error) {
    logger.error('Plan recalculation failed', error);
  }
  emitToFarm(farmId, 'parcel:updated', { parcelId });
}

/** Deleting the crop also removes its not-started tasks. */
export async function removePlan(farmId: string, parcelId: string, year: number) {
  await prisma.$transaction([
    prisma.task.deleteMany({ where: { parcelId, status: 'TODO', plan: { parcelId, year } } }),
    prisma.parcelPlan.updateMany({ where: { parcelId, year, active: true }, data: { active: false } }),
  ]);
  announcePlan(farmId, parcelId);
}

/** Financial data is not sent to operators. */
export function planForRole(data: ParcelPlanData, role: string): ParcelPlanData {
  if (role !== 'OPERATOR') return data;
  const { expectedProfitEurHa: _profit, priceEurPerT: _price, costEurHa: _cost, ...report } = data.report;
  return { ...data, report };
}

export const isCropCode = (value: string): value is CropCode => value in CROPS;
