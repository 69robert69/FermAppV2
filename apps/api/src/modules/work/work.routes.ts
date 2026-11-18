import { estimateWork } from '@fermapp/domain';
import { RunStatus, WorkType } from '@prisma/client';
import { z } from 'zod';
import { requireActiveParcel, requireMachine, requireMember } from '../../lib/access';
import { conflict, forbidden, notFound } from '../../lib/errors';
import { prisma } from '../../lib/prisma';
import { ApiRouter, idParams, ROLES } from '../../lib/route';
import type { AuthContext } from '../../lib/tokens';
import { emitToFarm } from '../../realtime/socket';
import { completeWork, serializeWork, workInclude } from './work.service';

export const workRoutes = new ApiRouter('/work', 'Work');

const params = {
  workingWidthM: z.number().positive().max(100),
  speedKmh: z.number().positive().max(60),
  efficiencyPct: z.number().positive().max(100),
};

async function requireControllableWork(auth: AuthContext, id: string) {
  const work = await prisma.workSession.findFirst({ where: { id, farmId: auth.farmId } });
  if (!work) throw notFound('Lucrarea');
  if (auth.role === 'OPERATOR' && work.operatorId !== auth.userId) {
    throw forbidden('Poți controla doar lucrările proprii.');
  }
  return work;
}

async function publish(id: string) {
  const work = await prisma.workSession.findUniqueOrThrow({ where: { id }, include: workInclude });
  const payload = serializeWork(work);
  emitToFarm(work.farmId, 'work:updated', payload);
  return payload;
}

workRoutes.post(
  '/estimate',
  {
    summary: 'Calculează capacitatea, durata și ETA',
    roles: ROLES.all,
    status: 200,
    body: z.object({ parcelId: z.uuid(), ...params }),
  },
  async ({ auth, body }) => {
    const parcel = await requireActiveParcel(auth.farmId, body.parcelId);
    return { areaHa: parcel.areaHa, ...estimateWork(parcel.areaHa, body) };
  },
);

workRoutes.get(
  '/',
  {
    summary: 'Listează lucrările',
    roles: ROLES.all,
    query: z.object({
      status: z.enum(RunStatus).optional(),
      parcelId: z.uuid().optional(),
      limit: z.coerce.number().int().min(1).max(200).default(50),
    }),
  },
  async ({ auth, query }) => {
    const items = await prisma.workSession.findMany({
      where: { farmId: auth.farmId, status: query.status, parcelId: query.parcelId },
      include: workInclude,
      orderBy: { startedAt: 'desc' },
      take: query.limit,
    });
    return items.map((item) => serializeWork(item));
  },
);

workRoutes.post(
  '/',
  {
    summary: 'Pornește o lucrare',
    roles: ROLES.field,
    body: z.object({
      parcelId: z.uuid(),
      machineId: z.uuid().nullable().optional(),
      operatorId: z.uuid().optional(),
      type: z.enum(WorkType),
      notes: z.string().trim().max(1000).optional(),
      ...params,
    }),
  },
  async ({ auth, body }) => {
    const parcel = await requireActiveParcel(auth.farmId, body.parcelId);
    if (body.machineId) await requireMachine(auth.farmId, body.machineId);
    // Operatorul pornește lucrări doar în numele lui.
    const operatorId = auth.role === 'OPERATOR' ? auth.userId : (body.operatorId ?? auth.userId);
    await requireMember(auth.farmId, operatorId);

    if (body.machineId) {
      const busy = await prisma.workSession.findFirst({
        where: { machineId: body.machineId, status: { in: ['RUNNING', 'PAUSED'] } },
      });
      if (busy) throw conflict('Utilajul este deja folosit într-o lucrare activă.');
    }

    const estimate = estimateWork(parcel.areaHa, body);
    const work = await prisma.workSession.create({
      data: {
        farmId: auth.farmId,
        parcelId: parcel.id,
        machineId: body.machineId ?? null,
        operatorId,
        type: body.type,
        notes: body.notes,
        areaHa: parcel.areaHa,
        workingWidthM: body.workingWidthM,
        speedKmh: body.speedKmh,
        efficiencyPct: body.efficiencyPct,
        capacityHaH: estimate.capacityHaH,
        durationHours: parcel.areaHa / ((body.workingWidthM * body.speedKmh * (body.efficiencyPct / 100)) / 10),
      },
    });
    return publish(work.id);
  },
);

workRoutes.get(
  '/:id',
  { summary: 'Detaliile și progresul unei lucrări', roles: ROLES.all, params: idParams },
  async ({ auth, params: { id } }) => {
    const work = await prisma.workSession.findFirst({ where: { id, farmId: auth.farmId }, include: workInclude });
    if (!work) throw notFound('Lucrarea');
    return serializeWork(work);
  },
);

workRoutes.post(
  '/:id/location',
  {
    summary: 'Trimite poziția GPS a utilajului',
    roles: ROLES.field,
    status: 204,
    params: idParams,
    body: z.object({
      lat: z.number().min(-90).max(90),
      lng: z.number().min(-180).max(180),
      headingDeg: z.number().min(0).max(360).nullable().optional(),
      speedKmh: z.number().min(0).max(200).nullable().optional(),
      accuracyM: z.number().min(0).max(100_000).nullable().optional(),
    }),
  },
  async ({ auth, params: { id }, body }) => {
    const work = await requireControllableWork(auth, id);
    if (work.status !== 'RUNNING' && work.status !== 'PAUSED') throw conflict('Lucrarea este încheiată.');
    const location = await prisma.machineLocation.create({
      data: { ...body, workSessionId: work.id, machineId: work.machineId },
    });
    emitToFarm(auth.farmId, 'machine:location', {
      workId: work.id,
      parcelId: work.parcelId,
      machineId: work.machineId,
      lat: location.lat,
      lng: location.lng,
      headingDeg: location.headingDeg,
      speedKmh: location.speedKmh,
      recordedAt: location.recordedAt,
    });
  },
);

workRoutes.get(
  '/:id/location',
  { summary: 'Ultima poziție GPS a utilajului', roles: ROLES.all, params: idParams },
  async ({ auth, params: { id } }) => {
    const work = await prisma.workSession.findFirst({ where: { id, farmId: auth.farmId } });
    if (!work) throw notFound('Lucrarea');
    const location = await prisma.machineLocation.findFirst({
      where: { workSessionId: id },
      orderBy: { recordedAt: 'desc' },
    });
    return location ? { ...location, id: location.id.toString(), workId: id } : null;
  },
);

workRoutes.post(
  '/:id/pause',
  { summary: 'Pune lucrarea pe pauză', roles: ROLES.field, status: 200, params: idParams },
  async ({ auth, params: { id } }) => {
    await requireControllableWork(auth, id);
    const { count } = await prisma.workSession.updateMany({
      where: { id, status: 'RUNNING' },
      data: { status: 'PAUSED', pausedAt: new Date() },
    });
    if (count === 0) throw conflict('Doar o lucrare în desfășurare poate fi pusă pe pauză.');
    return publish(id);
  },
);

workRoutes.post(
  '/:id/resume',
  { summary: 'Reia lucrarea', roles: ROLES.field, status: 200, params: idParams },
  async ({ auth, params: { id } }) => {
    const work = await requireControllableWork(auth, id);
    if (work.status !== 'PAUSED' || !work.pausedAt) throw conflict('Lucrarea nu este pe pauză.');
    const pausedFor = BigInt(Date.now() - work.pausedAt.getTime());
    await prisma.workSession.update({
      where: { id },
      data: { status: 'RUNNING', pausedAt: null, pausedMs: { increment: pausedFor } },
    });
    return publish(id);
  },
);

workRoutes.post(
  '/:id/complete',
  { summary: 'Finalizează lucrarea', roles: ROLES.field, status: 200, params: idParams },
  async ({ auth, params: { id } }) => {
    await requireControllableWork(auth, id);
    if (!(await completeWork(id))) throw conflict('Lucrarea este deja încheiată.');
    return publish(id);
  },
);

workRoutes.post(
  '/:id/cancel',
  { summary: 'Anulează lucrarea', roles: ROLES.field, status: 200, params: idParams },
  async ({ auth, params: { id } }) => {
    await requireControllableWork(auth, id);
    const { count } = await prisma.workSession.updateMany({
      where: { id, status: { in: ['RUNNING', 'PAUSED'] } },
      data: { status: 'CANCELLED', completedAt: new Date(), pausedAt: null },
    });
    if (count === 0) throw conflict('Lucrarea este deja încheiată.');
    return publish(id);
  },
);
