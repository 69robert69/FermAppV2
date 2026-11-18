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

export const cropRoutes = new ApiRouter('', 'Culturi și rotație');

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

cropRoutes.get('/crops', { summary: 'Catalogul culturilor predefinite', roles: ROLES.all }, async () =>
  CROP_CODES.map((code) => CROPS[code]),
);

cropRoutes.get(
  '/parcels/:id/seasons',
  { summary: 'Istoricul culturilor pe ultimii 10 ani', roles: ROLES.all, params: idParams },
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
  { summary: 'Adaugă o cultură în istoricul parcelei', roles: ROLES.planners, params: idParams, body: z.object(seasonFields) },
  async ({ auth, params, body }) => {
    await requireParcel(auth.farmId, params.id);
    const season = await prisma.cropSeason.create({ data: { ...body, parcelId: params.id } });
    await regeneratePlanSafely(auth.farmId, params.id, season.year);
    return serializeSeason(await prisma.cropSeason.findUniqueOrThrow({ where: { id: season.id } }));
  },
);

async function requireSeason(farmId: string, id: string) {
  const season = await prisma.cropSeason.findFirst({ where: { id, parcel: { farmId } } });
  if (!season) throw notFound('Sezonul');
  return season;
}

cropRoutes.patch(
  '/seasons/:id',
  {
    summary: 'Modifică o înregistrare de cultură',
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
  { summary: 'Șterge o înregistrare de cultură', roles: ROLES.planners, params: idParams },
  async ({ auth, params }) => {
    const season = await requireSeason(auth.farmId, params.id);
    await prisma.cropSeason.delete({ where: { id: params.id } });
    await removePlan(auth.farmId, season.parcelId, season.year);
  },
);

cropRoutes.post(
  '/parcels/:id/rotation/evaluate',
  {
    summary: 'Evaluează cultura propusă',
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
    summary: 'Ordonează toate culturile după scorul de rotație',
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
    summary: 'Recomandă culturi după sol, climă și rotație',
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
        logger.warn('Normale climatice indisponibile', (error as Error).message);
        return null;
      }),
    ]);
    const soilInput =
      soil?.status === 'READY' && soil.ph !== null && soil.texture !== null
        ? { ph: soil.ph, texture: soil.texture as SoilTexture }
        : null;
    const targetYear = query.year ?? new Date().getFullYear() + 1;
    const recommendations = recommendCrops({ year: targetYear, history, soil: soilInput, climate });

    // Ultimul rezultat se păstrează pentru istoric, fără să blocheze răspunsul la o eroare.
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
      .catch((error) => logger.warn('Salvare recomandări eșuată', (error as Error).message));

    return { year: targetYear, soil: soil?.label ?? null, climate, recommendations };
  },
);

const MAX_CLIMATE_YEARS = 10;

cropRoutes.get(
  '/parcels/:id/climate',
  {
    summary: 'Compară anii agricoli pe baza datelor NASA POWER',
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
    summary: 'Estimează dezvoltarea culturii curente prin grade-zile (GDD)',
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
    if (!season) throw notFound(`Cultura pentru anul ${target}`);
    if (!season.sowingDate) throw badRequest('Completează data semănatului pentru a estima dezvoltarea culturii.');

    const start = season.sowingDate.toISOString().slice(0, 10);
    // Datele NASA POWER apar cu câteva zile întârziere.
    const available = new Date(Date.now() - 5 * 86_400_000);
    const stop = season.harvestDate && season.harvestDate < available ? season.harvestDate : available;
    const end = stop.toISOString().slice(0, 10);
    if (start > end) throw badRequest('Nu există încă date meteo după data semănatului.');

    const days = await getPowerDaily(parcel.centerLat, parcel.centerLng, start, end);
    return { sowingDate: start, dataUntil: end, ...cropDevelopment(season.crop, days) };
  },
);
