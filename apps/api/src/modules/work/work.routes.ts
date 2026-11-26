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
  if (!work) throw notFound('Operation');
  if (auth.role === 'OPERATOR' && work.operatorId !== auth.userId) {
    throw forbidden('You can only control your own operations.');
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
    summary: 'Calculate capacity, duration and ETA',
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
    summary: 'List operations',
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
    summary: 'Start an operation',
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
    // Operators can only start operations in their own name.
    const operatorId = auth.role === 'OPERATOR' ? auth.userId : (body.operatorId ?? auth.userId);
    await requireMember(auth.farmId, operatorId);

    if (body.machineId) {
      const busy = await prisma.workSession.findFirst({
        where: { machineId: body.machineId, status: { in: ['RUNNING', 'PAUSED'] } },
      });
      if (busy) throw conflict('The machine is already used in an active operation.');
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
  { summary: 'Details and progress of an operation', roles: ROLES.all, params: idParams },
  async ({ auth, params: { id } }) => {
    const work = await prisma.workSession.findFirst({ where: { id, farmId: auth.farmId }, include: workInclude });
    if (!work) throw notFound('Operation');
    return serializeWork(work);
  },
);

workRoutes.post(
  '/:id/location',
  {
    summary: 'Send the machine’s GPS position',
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
    if (work.status !== 'RUNNING' && work.status !== 'PAUSED') throw conflict('The operation has ended.');
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
  { summary: 'Latest GPS position of the machine', roles: ROLES.all, params: idParams },
  async ({ auth, params: { id } }) => {
    const work = await prisma.workSession.findFirst({ where: { id, farmId: auth.farmId } });
    if (!work) throw notFound('Operation');
    const location = await prisma.machineLocation.findFirst({
      where: { workSessionId: id },
      orderBy: { recordedAt: 'desc' },
    });
    return location ? { ...location, id: location.id.toString(), workId: id } : null;
  },
);

workRoutes.post(
  '/:id/pause',
  { summary: 'Pause the operation', roles: ROLES.field, status: 200, params: idParams },
  async ({ auth, params: { id } }) => {
    await requireControllableWork(auth, id);
    const { count } = await prisma.workSession.updateMany({
      where: { id, status: 'RUNNING' },
      data: { status: 'PAUSED', pausedAt: new Date() },
    });
    if (count === 0) throw conflict('Only a running operation can be paused.');
    return publish(id);
  },
);

workRoutes.post(
  '/:id/resume',
  { summary: 'Resume the operation', roles: ROLES.field, status: 200, params: idParams },
  async ({ auth, params: { id } }) => {
    const work = await requireControllableWork(auth, id);
    if (work.status !== 'PAUSED' || !work.pausedAt) throw conflict('The operation is not paused.');
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
  { summary: 'Complete the operation', roles: ROLES.field, status: 200, params: idParams },
  async ({ auth, params: { id } }) => {
    await requireControllableWork(auth, id);
    if (!(await completeWork(id))) throw conflict('The operation has already ended.');
    return publish(id);
  },
);

workRoutes.post(
  '/:id/cancel',
  { summary: 'Cancel the operation', roles: ROLES.field, status: 200, params: idParams },
  async ({ auth, params: { id } }) => {
    await requireControllableWork(auth, id);
    const { count } = await prisma.workSession.updateMany({
      where: { id, status: { in: ['RUNNING', 'PAUSED'] } },
      data: { status: 'CANCELLED', completedAt: new Date(), pausedAt: null },
    });
    if (count === 0) throw conflict('The operation has already ended.');
    return publish(id);
  },
);
