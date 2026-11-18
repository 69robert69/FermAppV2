import { timedProgress } from '@fermapp/domain';
import type { Machine, WorkSession } from '@prisma/client';
import { prisma } from '../../lib/prisma';
import { emitToFarm } from '../../realtime/socket';
import { notify } from '../notifications/notifications.service';

type Run = Pick<WorkSession, 'startedAt' | 'durationHours' | 'pausedMs' | 'pausedAt' | 'status' | 'progressPct' | 'completedAt'>;

/** Progresul live al unei operațiuni cu durată cunoscută (lucrare sau irigare). */
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
  PLOWING: 'Arat',
  DISCING: 'Discuit',
  SUBSOILING: 'Scarificat',
  SOWING: 'Semănat',
  FERTILIZING: 'Fertilizat',
  SPRAYING: 'Erbicidat',
  HARVESTING: 'Recoltat',
} as const;

const fuelFor = (machine: Pick<Machine, 'avgConsumptionLh'> | null, elapsedSeconds: number) =>
  machine?.avgConsumptionLh ? Math.round((machine.avgConsumptionLh * elapsedSeconds) / 36) / 100 : null;

/** Încheie lucrarea o singură dată, chiar dacă rulează mai multe instanțe de server. */
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
  const managers = await prisma.farmMembership.findMany({
    where: { farmId: work.farmId, role: { in: ['ADMIN', 'FARM_MANAGER'] } },
    select: { userId: true },
  });
  await notify({
    farmId: work.farmId,
    userIds: [...new Set([work.operatorId, ...managers.map((member) => member.userId)])],
    title: 'Lucrare finalizată',
    body: `${WORK_LABELS[work.type]} pe parcela ${work.parcel.name}: ${progress.percent}% realizat.`,
    data: { type: 'work', id: work.id },
  });
  return completed;
}
