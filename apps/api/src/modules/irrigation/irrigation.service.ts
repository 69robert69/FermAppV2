import type { IrrigationRun } from '@prisma/client';
import { prisma } from '../../lib/prisma';
import { emitToFarm } from '../../realtime/socket';
import { notify } from '../notifications/notifications.service';
import { liveProgress } from '../work/work.service';

export const irrigationInclude = { parcel: { select: { id: true, name: true, code: true } } };

export const serializeIrrigation = <T extends IrrigationRun>(run: T, now = new Date()) => ({
  ...run,
  pausedMs: Number(run.pausedMs),
  progress: liveProgress(run, now),
});

/** Completes the irrigation only once and notifies the team. */
export async function completeIrrigation(id: string, now = new Date()) {
  const run = await prisma.irrigationRun.findUnique({ where: { id }, include: irrigationInclude });
  if (!run || (run.status !== 'RUNNING' && run.status !== 'PAUSED')) return null;

  const progress = liveProgress(run, now);
  const { count } = await prisma.irrigationRun.updateMany({
    where: { id, status: { in: ['RUNNING', 'PAUSED'] } },
    data: { status: 'COMPLETED', completedAt: now, pausedAt: null, progressPct: progress.percent },
  });
  if (count === 0) return null;

  const completed = await prisma.irrigationRun.findUniqueOrThrow({ where: { id }, include: irrigationInclude });
  emitToFarm(run.farmId, 'irrigation:updated', serializeIrrigation(completed, now));
  await notify({
    farmId: run.farmId,
    roles: ['ADMIN', 'FARM_MANAGER', 'OPERATOR'],
    title: 'Irrigation completed',
    body: `Irrigation on parcel ${run.parcel.name} has ended: ${progress.percent}% done.`,
    data: { type: 'irrigation', id },
  });
  return completed;
}
