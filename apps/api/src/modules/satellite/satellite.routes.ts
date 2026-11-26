import { vegetationHealth } from '@fermapp/domain';
import { z } from 'zod';
import { fetchIndexImage, fetchObservations, isSentinelConfigured } from '../../integrations/sentinel';
import { requireParcel } from '../../lib/access';
import { notConfigured, notFound } from '../../lib/errors';
import { prisma } from '../../lib/prisma';
import { ApiRouter, FileResult, idParams, ROLES } from '../../lib/route';

export const satelliteRoutes = new ApiRouter('/parcels/:id/satellite', 'Satellite');

const isoDate = z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'The date must have the format YYYY-MM-DD.');

const withHealth = (observation: { ndvi: number; ndre: number | null }) => vegetationHealth(observation.ndvi, observation.ndre);

satelliteRoutes.get(
  '/',
  {
    summary: 'NDVI, NDRE and Health Index history',
    roles: ROLES.all,
    params: idParams,
    query: z.object({ limit: z.coerce.number().int().min(1).max(200).default(60) }),
  },
  async ({ auth, params, query }) => {
    await requireParcel(auth.farmId, params.id);
    const observations = await prisma.satelliteObservation.findMany({
      where: { parcelId: params.id },
      orderBy: { date: 'desc' },
      take: query.limit,
    });
    return {
      configured: isSentinelConfigured(),
      observations: observations.map((item) => ({ ...item, health: withHealth(item) })),
    };
  },
);

satelliteRoutes.post(
  '/refresh',
  {
    summary: 'Download Sentinel-2 observations for the given period',
    roles: ROLES.planners,
    status: 200,
    params: idParams,
    body: z.object({ days: z.number().int().min(1).max(365).default(60) }),
  },
  async ({ auth, params, body }) => {
    const parcel = await requireParcel(auth.farmId, params.id);
    if (!isSentinelConfigured()) throw notConfigured('The satellite imagery service');
    const to = new Date();
    const from = new Date(to.getTime() - body.days * 86_400_000);
    const observations = await fetchObservations(
      parcel.geometry,
      from.toISOString().slice(0, 10),
      to.toISOString().slice(0, 10),
    );
    await Promise.all(
      observations.map((item) => {
        const health = vegetationHealth(item.ndvi, item.ndre);
        const data = {
          ndvi: item.ndvi,
          ndre: item.ndre,
          cloudPct: item.cloudPct,
          healthIndex: health.healthIndex,
          healthClass: health.healthClass,
        };
        return prisma.satelliteObservation.upsert({
          where: { parcelId_date: { parcelId: parcel.id, date: new Date(item.date) } },
          create: { ...data, parcelId: parcel.id, date: new Date(item.date) },
          update: data,
        });
      }),
    );
    return { imported: observations.length };
  },
);

satelliteRoutes.get(
  '/compare',
  {
    summary: 'Compare two observation dates',
    roles: ROLES.all,
    params: idParams,
    query: z.object({ from: isoDate, to: isoDate }),
  },
  async ({ auth, params, query }) => {
    await requireParcel(auth.farmId, params.id);
    const find = (date: string) =>
      prisma.satelliteObservation.findUnique({
        where: { parcelId_date: { parcelId: params.id, date: new Date(date) } },
      });
    const [before, after] = await Promise.all([find(query.from), find(query.to)]);
    if (!before || !after) throw notFound('The observation for one of the dates');
    const round = (value: number) => Math.round(value * 1000) / 1000;
    return {
      before: { ...before, health: withHealth(before) },
      after: { ...after, health: withHealth(after) },
      change: {
        ndvi: round(after.ndvi - before.ndvi),
        ndre: before.ndre !== null && after.ndre !== null ? round(after.ndre - before.ndre) : null,
        healthIndex: after.healthIndex - before.healthIndex,
      },
    };
  },
);

satelliteRoutes.get(
  '/image',
  {
    summary: 'Coloured index image for a date',
    roles: ROLES.all,
    produces: 'image/png',
    params: idParams,
    query: z.object({ date: isoDate, index: z.enum(['NDVI', 'NDRE']).default('NDVI') }),
  },
  async ({ auth, params, query }) => {
    const parcel = await requireParcel(auth.farmId, params.id);
    const image = await fetchIndexImage(parcel.geometry, query.date, query.index);
    return new FileResult(image, 'image/png', `${query.index}-${query.date}.png`, true);
  },
);
