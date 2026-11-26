import {
  CROP_CODES,
  CROPS,
  cropDevelopment,
  evaluateRotation,
  rankRotationOptions,
  recommendCrops,
  type CropHistoryEntry,
  type SoilTexture,
} from '@fermapp/domain';
import type { CropSeason } from '@prisma/client';
import { z } from 'zod';
import { getClimateNormals, getPowerDaily, summarizeClimateYears } from '../../integrations/nasaPower';
import { requireParcel } from '../../lib/access';
import { badRequest, notFound } from '../../lib/errors';
import { logger } from '../../lib/logger';
import { prisma } from '../../lib/prisma';
import { ApiRouter, idParams, ROLES } from '../../lib/route';
import { regeneratePlanSafely, removePlan } from '../plans/plan.service';

export const cropRoutes = new ApiRouter('', 'Crops and rotation');

const cropCode = z.enum(CROP_CODES);
const year = z.coerce.number().int().min(1990).max(2100);
const decimalInput = z.number().min(0).max(1_000_000);

const seasonFields = {
  crop: cropCode,
  year: z.number().int().min(1990).max(2100),
  sowingDate: z.coerce.date().nullable().optional(),
  expectedHarvestDate: z.coerce.date().nullable().optional(),
  harvestDate: z.coerce.date().nullable().optional(),
  yieldT: z.number().min(0).max(1_000_000).nullable().optional(),
  harvestMoisture: z.number().min(0).max(100).nullable().optional(),
  expectedYieldTHa: z.number().min(0).max(500).nullable().optional(),
  expectedPricePerT: decimalInput.nullable().optional(),
  actualPricePerT: decimalInput.nullable().optional(),
  notes: z.string().trim().max(2000).nullable().optional(),
};

export const serializeSeason = (season: CropSeason) => ({
  ...season,
  expectedPricePerT: season.expectedPricePerT === null ? null : Number(season.expectedPricePerT),
  actualPricePerT: season.actualPricePerT === null ? null : Number(season.actualPricePerT),
});

export async function parcelHistory(parcelId: string): Promise<CropHistoryEntry[]> {
  const seasons = await prisma.cropSeason.findMany({
    where: { parcelId },
    select: { crop: true, year: true },
    orderBy: { year: 'desc' },
  });
  return seasons;
}

cropRoutes.get('/crops', { summary: 'Catalog of predefined crops', roles: ROLES.all }, async () =>
  CROP_CODES.map((code) => CROPS[code]),
);

cropRoutes.get(
  '/parcels/:id/seasons',
  { summary: 'Crop history for the last 10 years', roles: ROLES.all, params: idParams },
  async ({ auth, params }) => {
    await requireParcel(auth.farmId, params.id);
    const seasons = await prisma.cropSeason.findMany({
      where: { parcelId: params.id, year: { gte: new Date().getFullYear() - 10 } },
      orderBy: { year: 'desc' },
    });
    return seasons.map(serializeSeason);
  },
);

cropRoutes.post(
  '/parcels/:id/seasons',
  { summary: 'Add a crop to the parcel history', roles: ROLES.planners, params: idParams, body: z.object(seasonFields) },
  async ({ auth, params, body }) => {
    await requireParcel(auth.farmId, params.id);
    const season = await prisma.cropSeason.create({ data: { ...body, parcelId: params.id } });
    await regeneratePlanSafely(auth.farmId, params.id, season.year);
    return serializeSeason(await prisma.cropSeason.findUniqueOrThrow({ where: { id: season.id } }));
  },
);

async function requireSeason(farmId: string, id: string) {
  const season = await prisma.cropSeason.findFirst({ where: { id, parcel: { farmId } } });
  if (!season) throw notFound('Season');
  return season;
}

cropRoutes.patch(
  '/seasons/:id',
  {
    summary: 'Update a crop record',
    roles: ROLES.planners,
    params: idParams,
    body: z.object(seasonFields).partial(),
  },
  async ({ auth, params, body }) => {
    const before = await requireSeason(auth.farmId, params.id);
    const season = await prisma.cropSeason.update({ where: { id: params.id }, data: body });
    const replanned =
      season.crop !== before.crop ||
      season.year !== before.year ||
      season.sowingDate?.getTime() !== before.sowingDate?.getTime();
    if (!replanned) return serializeSeason(season);
    if (season.year !== before.year) await removePlan(auth.farmId, season.parcelId, before.year);
    await regeneratePlanSafely(auth.farmId, season.parcelId, season.year, season.crop !== before.crop);
    return serializeSeason(await prisma.cropSeason.findUniqueOrThrow({ where: { id: season.id } }));
  },
);

cropRoutes.delete(
  '/seasons/:id',
  { summary: 'Delete a crop record', roles: ROLES.planners, params: idParams },
  async ({ auth, params }) => {
    const season = await requireSeason(auth.farmId, params.id);
    await prisma.cropSeason.delete({ where: { id: params.id } });
    await removePlan(auth.farmId, season.parcelId, season.year);
  },
);

cropRoutes.post(
  '/parcels/:id/rotation/evaluate',
  {
    summary: 'Evaluate the proposed crop',
    roles: ROLES.all,
    status: 200,
    params: idParams,
    body: z.object({ crop: cropCode, year: z.number().int().min(1990).max(2100) }),
  },
  async ({ auth, params, body }) => {
    await requireParcel(auth.farmId, params.id);
    return evaluateRotation(body.crop, body.year, await parcelHistory(params.id));
  },
);

cropRoutes.get(
  '/parcels/:id/rotation/options',
  {
    summary: 'Rank all crops by rotation score',
    roles: ROLES.all,
    params: idParams,
    query: z.object({ year: year.optional() }),
  },
  async ({ auth, params, query }) => {
    await requireParcel(auth.farmId, params.id);
    const target = query.year ?? new Date().getFullYear() + 1;
    return rankRotationOptions(target, await parcelHistory(params.id), CROP_CODES);
  },
);

cropRoutes.get(
  '/parcels/:id/recommendations',
  {
    summary: 'Recommend crops by soil, climate and rotation',
    roles: ROLES.all,
    params: idParams,
    query: z.object({ year: year.optional() }),
  },
  async ({ auth, params, query }) => {
    const parcel = await requireParcel(auth.farmId, params.id);
    const [history, soil, climate] = await Promise.all([
      parcelHistory(parcel.id),
      prisma.soilProfile.findUnique({ where: { parcelId: parcel.id } }),
      getClimateNormals(parcel.centerLat, parcel.centerLng).catch((error) => {
        logger.warn('Climate normals unavailable', (error as Error).message);
        return null;
      }),
    ]);
    const soilInput =
      soil?.status === 'READY' && soil.ph !== null && soil.texture !== null
        ? { ph: soil.ph, texture: soil.texture as SoilTexture }
        : null;
    const targetYear = query.year ?? new Date().getFullYear() + 1;
    const recommendations = recommendCrops({ year: targetYear, history, soil: soilInput, climate });

    // The latest result is kept for history, without blocking the response on an error.
    await prisma
      .$transaction([
        prisma.cropRecommendation.deleteMany({ where: { parcelId: parcel.id, year: targetYear } }),
        prisma.cropRecommendation.createMany({
          data: recommendations.map((item) => ({
            parcelId: parcel.id,
            year: targetYear,
            crop: item.crop,
            score: item.score,
            reasons: item.reasons,
          })),
        }),
      ])
      .catch((error) => logger.warn('Saving recommendations failed', (error as Error).message));

    return { year: targetYear, soil: soil?.label ?? null, climate, recommendations };
  },
);

const MAX_CLIMATE_YEARS = 10;

cropRoutes.get(
  '/parcels/:id/climate',
  {
    summary: 'Compare agricultural years based on NASA POWER data',
    roles: ROLES.all,
    params: idParams,
    query: z.object({ years: z.coerce.number().int().min(1).max(MAX_CLIMATE_YEARS).default(5) }),
  },
  async ({ auth, params, query }) => {
    const parcel = await requireParcel(auth.farmId, params.id);
    const lastYear = new Date().getUTCFullYear() - 1;
    const days = await getPowerDaily(
      parcel.centerLat,
      parcel.centerLng,
      `${lastYear - query.years + 1}-01-01`,
      `${lastYear}-12-31`,
    );
    return { source: 'NASA POWER', years: summarizeClimateYears(days) };
  },
);

cropRoutes.get(
  '/parcels/:id/development',
  {
    summary: 'Estimate the development of the current crop using growing degree-days (GDD)',
    roles: ROLES.all,
    params: idParams,
    query: z.object({ year: year.optional() }),
  },
  async ({ auth, params, query }) => {
    const parcel = await requireParcel(auth.farmId, params.id);
    const target = query.year ?? new Date().getFullYear();
    const season = await prisma.cropSeason.findUnique({
      where: { parcelId_year: { parcelId: parcel.id, year: target } },
    });
    if (!season) throw notFound(`The crop for ${target}`);
    if (!season.sowingDate) throw badRequest('Fill in the sowing date to estimate crop development.');

    const start = season.sowingDate.toISOString().slice(0, 10);
    // NASA POWER data appears with a delay of a few days.
    const available = new Date(Date.now() - 5 * 86_400_000);
    const stop = season.harvestDate && season.harvestDate < available ? season.harvestDate : available;
    const end = stop.toISOString().slice(0, 10);
    if (start > end) throw badRequest('There is no weather data after the sowing date yet.');

    const days = await getPowerDaily(parcel.centerLat, parcel.centerLng, start, end);
    return { sowingDate: start, dataUntil: end, ...cropDevelopment(season.crop, days) };
  },
);
