import { acquireLock } from '../lib/cache';
import { mapLimit } from '../lib/http';
import { logger } from '../lib/logger';
import { prisma } from '../lib/prisma';
import { completeIrrigation, irrigationInclude, serializeIrrigation } from '../modules/irrigation/irrigation.service';
import { notify } from '../modules/notifications/notifications.service';
import { refreshSoilProfile } from '../modules/soil/soil.service';
import { checkParcelAlerts } from '../modules/weather/weather.service';
import { completeWork, liveProgress, serializeWork, workInclude } from '../modules/work/work.service';
import { emitToFarm } from '../realtime/socket';

const PROGRESS_INTERVAL_MS = 15_000;
const REMINDER_INTERVAL_MS = 5 * 60_000;
const WEATHER_INTERVAL_MS = 30 * 60_000;
const SOIL_RETRY_INTERVAL_MS = 6 * 3_600_000;
const IDEMPOTENCY_RETENTION_MS = 7 * 86_400_000;
const LOCATION_RETENTION_MS = 30 * 86_400_000;

/** Sends live progress and completes operations that have reached 100%. */
export async function tickProgress(now = new Date()) {
  const [works, runs] = await Promise.all([
    prisma.workSession.findMany({ where: { status: 'RUNNING' }, include: workInclude }),
    prisma.irrigationRun.findMany({ where: { status: 'RUNNING' }, include: irrigationInclude }),
  ]);

  for (const work of works) {
    const progress = liveProgress(work, now);
    if (progress.finished) await completeWork(work.id, now);
    else {
      await prisma.workSession.updateMany({ where: { id: work.id, status: 'RUNNING' }, data: { progressPct: progress.percent } });
      emitToFarm(work.farmId, 'work:progress', serializeWork(work, now));
    }
  }
  for (const run of runs) {
    const progress = liveProgress(run, now);
    if (progress.finished) await completeIrrigation(run.id, now);
    else {
      await prisma.irrigationRun.updateMany({ where: { id: run.id, status: 'RUNNING' }, data: { progressPct: progress.percent } });
      emitToFarm(run.farmId, 'irrigation:progress', serializeIrrigation(run, now));
    }
  }
}

/** Notifies the assignee 24 hours before the deadline. */
export async function sendTaskReminders(now = new Date()) {
  const tasks = await prisma.task.findMany({
    where: {
      status: { not: 'DONE' },
      reminderSentAt: null,
      assigneeId: { not: null },
      dueDate: { lte: new Date(now.getTime() + 86_400_000) },
    },
    include: { parcel: { select: { name: true } } },
    take: 500,
  });
  for (const task of tasks) {
    const { count } = await prisma.task.updateMany({
      where: { id: task.id, reminderSentAt: null },
      data: { reminderSentAt: now },
    });
    if (count === 0) continue;
    await notify({
      farmId: task.farmId,
      userIds: [task.assigneeId!],
      title: task.dueDate < now ? 'Task overdue' : 'Deadline approaching',
      body: `${task.title}${task.parcel ? ` on parcel ${task.parcel.name}` : ''}.`,
      data: { type: 'task', id: task.id },
    });
  }
}

async function checkWeatherAlerts() {
  const parcels = await prisma.parcel.findMany({ where: { archivedAt: null } });
  await mapLimit(parcels, 5, async (parcel) => {
    await checkParcelAlerts(parcel).catch((error) => logger.warn('Alert check failed', (error as Error).message));
  });
}

async function retrySoilProfiles() {
  const parcels = await prisma.parcel.findMany({
    where: { archivedAt: null, soilProfile: { status: { in: ['PENDING', 'FAILED'] } } },
    take: 25,
  });
  await mapLimit(parcels, 2, async (parcel) => {
    await refreshSoilProfile(parcel.id, parcel.centerLat, parcel.centerLng);
  });
}

const cleanIdempotency = () =>
  prisma.$transaction([
    prisma.idempotencyRecord.deleteMany({ where: { createdAt: { lt: new Date(Date.now() - IDEMPOTENCY_RETENTION_MS) } } }),
    prisma.machineLocation.deleteMany({ where: { recordedAt: { lt: new Date(Date.now() - LOCATION_RETENTION_MS) } } }),
  ]);

const timers: NodeJS.Timeout[] = [];

function every(name: string, intervalMs: number, job: () => Promise<unknown>) {
  let running = false;
  const run = async () => {
    if (running) return;
    running = true;
    try {
      // The lock expires before the next run, so only one instance executes the job.
      if (await acquireLock(`job:${name}`, Math.max(1, Math.floor(intervalMs / 1000) - 1))) await job();
    } catch (error) {
      logger.error(`Job failed: ${name}`, error);
    } finally {
      running = false;
    }
  };
  timers.push(setInterval(run, intervalMs));
}

export function startScheduler() {
  every('progress', PROGRESS_INTERVAL_MS, tickProgress);
  every('task-reminders', REMINDER_INTERVAL_MS, sendTaskReminders);
  every('weather-alerts', WEATHER_INTERVAL_MS, checkWeatherAlerts);
  every('soil-retry', SOIL_RETRY_INTERVAL_MS, retrySoilProfiles);
  every('idempotency-clean', SOIL_RETRY_INTERVAL_MS, cleanIdempotency);
}

export const stopScheduler = () => timers.splice(0).forEach(clearInterval);
