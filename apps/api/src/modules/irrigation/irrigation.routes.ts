import { CROPS, estimateIrrigation, waterBalance } from '@fermapp/domain';
import { IrrigationSystemType, RunStatus } from '@prisma/client';
import { z } from 'zod';
import { getHistory } from '../../integrations/openMeteo';
import { requireActiveParcel, requireParcel } from '../../lib/access';
import { conflict, notFound } from '../../lib/errors';
import { prisma } from '../../lib/prisma';
import { ApiRouter, idParams, ROLES } from '../../lib/route';
import { emitToFarm } from '../../realtime/socket';
import { completeIrrigation, irrigationInclude, serializeIrrigation } from './irrigation.service';

export const irrigationRoutes = new ApiRouter('', 'Irigații');

const route = {
  routeLengthM: z.number().positive().max(100_000),
  speedMPerH: z.number().positive().max(10_000),
};

async function requireRun(farmId: string, id: string) {
  const run = await prisma.irrigationRun.findFirst({ where: { id, farmId } });
  if (!run) throw notFound('Irigarea');
  return run;
}

async function publish(id: string) {
  const run = await prisma.irrigationRun.findUniqueOrThrow({ where: { id }, include: irrigationInclude });
  const payload = serializeIrrigation(run);
  emitToFarm(run.farmId, 'irrigation:updated', payload);
  return payload;
}

irrigationRoutes.post(
  '/irrigation/estimate',
  { summary: 'Calculează durata și ETA irigării', roles: ROLES.all, status: 200, body: z.object(route) },
  async ({ body }) => estimateIrrigation(body.routeLengthM, body.speedMPerH),
);

irrigationRoutes.get(
  '/irrigation',
  {
    summary: 'Listează irigările',
    roles: ROLES.all,
    query: z.object({ status: z.enum(RunStatus).optional(), parcelId: z.uuid().optional() }),
  },
  async ({ auth, query }) => {
    const runs = await prisma.irrigationRun.findMany({
      where: { farmId: auth.farmId, status: query.status, parcelId: query.parcelId },
      include: irrigationInclude,
      orderBy: { startedAt: 'desc' },
      take: 100,
    });
    return runs.map((run) => serializeIrrigation(run));
  },
);

irrigationRoutes.post(
  '/irrigation',
  {
    summary: 'Pornește o irigare',
    roles: ROLES.field,
    body: z.object({
      parcelId: z.uuid(),
      systemType: z.enum(IrrigationSystemType),
      doseMm: z.number().positive().max(200).nullable().optional(),
      ...route,
    }),
  },
  async ({ auth, body }) => {
    await requireActiveParcel(auth.farmId, body.parcelId);
    const active = await prisma.irrigationRun.findFirst({
      where: { parcelId: body.parcelId, status: { in: ['RUNNING', 'PAUSED'] } },
    });
    if (active) throw conflict('Parcela are deja o irigare activă.');
    const run = await prisma.irrigationRun.create({
      data: {
        ...body,
        farmId: auth.farmId,
        durationHours: body.routeLengthM / body.speedMPerH,
      },
    });
    return publish(run.id);
  },
);

irrigationRoutes.get(
  '/irrigation/:id',
  { summary: 'Detaliile și progresul irigării', roles: ROLES.all, params: idParams },
  async ({ auth, params }) => {
    await requireRun(auth.farmId, params.id);
    return publish(params.id);
  },
);

irrigationRoutes.post(
  '/irrigation/:id/pause',
  { summary: 'Pune irigarea pe pauză', roles: ROLES.field, status: 200, params: idParams },
  async ({ auth, params }) => {
    await requireRun(auth.farmId, params.id);
    const { count } = await prisma.irrigationRun.updateMany({
      where: { id: params.id, status: 'RUNNING' },
      data: { status: 'PAUSED', pausedAt: new Date() },
    });
    if (count === 0) throw conflict('Doar o irigare în desfășurare poate fi pusă pe pauză.');
    return publish(params.id);
  },
);

irrigationRoutes.post(
  '/irrigation/:id/resume',
  { summary: 'Reia irigarea', roles: ROLES.field, status: 200, params: idParams },
  async ({ auth, params }) => {
    const run = await requireRun(auth.farmId, params.id);
    if (run.status !== 'PAUSED' || !run.pausedAt) throw conflict('Irigarea nu este pe pauză.');
    await prisma.irrigationRun.update({
      where: { id: run.id },
      data: {
        status: 'RUNNING',
        pausedAt: null,
        pausedMs: { increment: BigInt(Date.now() - run.pausedAt.getTime()) },
      },
    });
    return publish(run.id);
  },
);

irrigationRoutes.post(
  '/irrigation/:id/complete',
  { summary: 'Finalizează irigarea', roles: ROLES.field, status: 200, params: idParams },
  async ({ auth, params }) => {
    await requireRun(auth.farmId, params.id);
    if (!(await completeIrrigation(params.id))) throw conflict('Irigarea este deja încheiată.');
    return publish(params.id);
  },
);

irrigationRoutes.post(
  '/irrigation/:id/cancel',
  { summary: 'Anulează irigarea', roles: ROLES.field, status: 200, params: idParams },
  async ({ auth, params }) => {
    await requireRun(auth.farmId, params.id);
    const { count } = await prisma.irrigationRun.updateMany({
      where: { id: params.id, status: { in: ['RUNNING', 'PAUSED'] } },
      data: { status: 'CANCELLED', completedAt: new Date(), pausedAt: null },
    });
    if (count === 0) throw conflict('Irigarea este deja încheiată.');
    return publish(params.id);
  },
);

irrigationRoutes.get(
  '/parcels/:id/water',
  {
    summary: 'Necesarul de apă, precipitațiile primite și deficitul',
    roles: ROLES.all,
    params: idParams,
    query: z.object({ year: z.coerce.number().int().min(2000).max(2100).optional() }),
  },
  async ({ auth, params, query }) => {
    const parcel = await requireParcel(auth.farmId, params.id);
    const year = query.year ?? new Date().getFullYear();
    const season = await prisma.cropSeason.findUnique({
      where: { parcelId_year: { parcelId: parcel.id, year } },
    });
    if (!season) throw notFound(`Cultura pentru anul ${year}`);

    // Fără date de semănat și recoltare se folosește sezonul de vegetație aprilie-septembrie.
    const start = (season.sowingDate ?? new Date(Date.UTC(year, 3, 1))).toISOString().slice(0, 10);
    const latestAvailable = new Date(Date.now() - 2 * 86_400_000);
    const seasonEnd = season.harvestDate ?? new Date(Date.UTC(year, 8, 30));
    const end = (seasonEnd < latestAvailable ? seasonEnd : latestAvailable).toISOString().slice(0, 10);

    const days = start <= end ? await getHistory(parcel.centerLat, parcel.centerLng, start, end) : [];
    const precipitationMm = days.reduce((sum, day) => sum + day.precipitationMm, 0);
    const irrigation = await prisma.irrigationRun.findMany({
      where: { parcelId: parcel.id, status: 'COMPLETED', startedAt: { gte: new Date(start) }, doseMm: { not: null } },
      select: { doseMm: true, progressPct: true },
    });
    const irrigationMm = irrigation.reduce((sum, run) => sum + (run.doseMm! * run.progressPct) / 100, 0);

    return {
      year,
      cropName: CROPS[season.crop].name,
      period: { start, end },
      precipitationMm: Math.round(precipitationMm * 10) / 10,
      irrigationMm: Math.round(irrigationMm * 10) / 10,
      ...waterBalance({ crop: season.crop, precipitationMm, irrigationMm }),
    };
  },
);
