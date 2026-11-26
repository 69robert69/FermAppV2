import { taskForFinishedWork, timedProgress } from '@fermapp/domain';
import type { Machine, WorkSession } from '@prisma/client';
import { prisma } from '../../lib/prisma';
import { emitToFarm } from '../../realtime/socket';
import { notify } from '../notifications/notifications.service';

type Run = Pick<WorkSession, 'startedAt' | 'durationHours' | 'pausedMs' | 'pausedAt' | 'status' | 'progressPct' | 'completedAt'>;

/** Live progress of an operation with known duration (field work or irrigation). */
export function liveProgress(run: Run, now = new Date()) {
  const durationMs = run.durationHours * 3_600_000;
  const progress = timedProgress({
    startedAt: run.startedAt,
    durationMs,
    pausedMs: Number(run.pausedMs),
    pausedAt: run.pausedAt,
    now,
  });
  const active = run.status === 'RUNNING' || run.status === 'PAUSED';
  return {
    percent: active ? progress.percent : run.progressPct,
    elapsedSeconds: Math.round(progress.elapsedMs / 1000),
    remainingSeconds: active ? Math.round(progress.remainingMs / 1000) : 0,
    eta: run.status === 'RUNNING' ? progress.eta.toISOString() : null,
    finished: progress.finished,
  };
}

export const serializeWork = <T extends WorkSession>(work: T, now = new Date()) => ({
  ...work,
  pausedMs: Number(work.pausedMs),
  progress: liveProgress(work, now),
  areaDoneHa: Math.round(work.areaHa * liveProgress(work, now).percent) / 100,
});

export const workInclude = {
  parcel: { select: { id: true, name: true, code: true } },
  machine: { select: { id: true, name: true, avgConsumptionLh: true } },
  operator: { select: { id: true, fullName: true } },
};

export const WORK_LABELS = {
  PLOWING: 'Ploughing',
  DISCING: 'Discing',
  SUBSOILING: 'Subsoiling',
  SOWING: 'Sowing',
  FERTILIZING: 'Fertilizing',
  SPRAYING: 'Spraying',
  HARVESTING: 'Harvesting',
} as const;

const fuelFor = (machine: Pick<Machine, 'avgConsumptionLh'> | null, elapsedSeconds: number) =>
  machine?.avgConsumptionLh ? Math.round((machine.avgConsumptionLh * elapsedSeconds) / 36) / 100 : null;

/**
 * A finished operation carries out the task planned for it: the earliest open
 * task of the matching type on the parcel, due by a few days later.
 */
async function completeLinkedTask(farmId: string, parcelId: string, workType: string, now: Date) {
  const open = await prisma.task.findMany({ where: { farmId, parcelId, status: { not: 'DONE' } }, select: { id: true, status: true, type: true, dueDate: true } });
  const task = taskForFinishedWork(open, workType, now);
  if (!task) return;
  const updated = await prisma.task.update({
    where: { id: task.id },
    data: { status: 'DONE', completedAt: now },
    include: { parcel: { select: { id: true, name: true } }, assignee: { select: { id: true, fullName: true } } },
  });
  emitToFarm(farmId, 'task:updated', updated);
}

/** Completes the operation only once, even if several server instances are running. */
export async function completeWork(workId: string, now = new Date()) {
  const work = await prisma.workSession.findUnique({ where: { id: workId }, include: workInclude });
  if (!work || (work.status !== 'RUNNING' && work.status !== 'PAUSED')) return null;

  const progress = liveProgress(work, now);
  const { count } = await prisma.workSession.updateMany({
    where: { id: workId, status: { in: ['RUNNING', 'PAUSED'] } },
    data: {
      status: 'COMPLETED',
      completedAt: now,
      pausedAt: null,
      progressPct: progress.percent,
      fuelLiters: fuelFor(work.machine, progress.elapsedSeconds),
    },
  });
  if (count === 0) return null;

  const completed = await prisma.workSession.findUniqueOrThrow({ where: { id: workId }, include: workInclude });
  emitToFarm(work.farmId, 'work:updated', serializeWork(completed, now));
  await completeLinkedTask(work.farmId, work.parcelId, work.type, now);
  const managers = await prisma.farmMembership.findMany({
    where: { farmId: work.farmId, role: { in: ['ADMIN', 'FARM_MANAGER'] } },
    select: { userId: true },
  });
  await notify({
    farmId: work.farmId,
    userIds: [...new Set([work.operatorId, ...managers.map((member) => member.userId)])],
    title: 'Operation completed',
    body: `${WORK_LABELS[work.type]} on parcel ${work.parcel.name}: ${progress.percent}% done.`,
    data: { type: 'work', id: work.id },
  });
  return completed;
}
