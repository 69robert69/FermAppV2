import { CROPS, cropName } from '@fermapp/domain';
import { z } from 'zod';
import { prisma } from '../../lib/prisma';
import { ApiRouter, ROLES } from '../../lib/route';
import { serializeIrrigation, irrigationInclude } from '../irrigation/irrigation.service';
import { serializeWork, workInclude } from '../work/work.service';

export const dashboardRoutes = new ApiRouter('', 'Dashboard');

const round = (value: number) => Math.round(value * 100) / 100;

dashboardRoutes.get('/dashboard', { summary: 'Indicatorii principali ai fermei', roles: ROLES.all }, async ({ auth }) => {
  const year = new Date().getFullYear();
  const farmId = auth.farmId;
  const [parcels, seasons, activeWork, activeIrrigation, openTasks, overdueTasks, alerts] = await Promise.all([
    prisma.parcel.findMany({ where: { farmId, archivedAt: null }, select: { id: true, areaHa: true } }),
    prisma.cropSeason.findMany({
      where: { year, parcel: { farmId, archivedAt: null } },
      select: { crop: true, parcelId: true, parcel: { select: { areaHa: true } } },
    }),
    prisma.workSession.findMany({
      where: { farmId, status: { in: ['RUNNING', 'PAUSED'] } },
      include: workInclude,
      orderBy: { startedAt: 'desc' },
    }),
    prisma.irrigationRun.findMany({
      where: { farmId, status: { in: ['RUNNING', 'PAUSED'] } },
      include: irrigationInclude,
      orderBy: { startedAt: 'desc' },
    }),
    prisma.task.count({ where: { farmId, status: { not: 'DONE' } } }),
    prisma.task.count({ where: { farmId, status: { not: 'DONE' }, dueDate: { lt: new Date() } } }),
    prisma.weatherAlert.findMany({
      where: { farmId },
      include: { parcel: { select: { id: true, name: true } } },
      orderBy: { createdAt: 'desc' },
      take: 5,
    }),
  ]);

  const totalAreaHa = round(parcels.reduce((sum, parcel) => sum + parcel.areaHa, 0));
  const cultivatedAreaHa = round(seasons.reduce((sum, season) => sum + season.parcel.areaHa, 0));
  const byCrop = new Map<string, number>();
  for (const season of seasons) byCrop.set(season.crop, (byCrop.get(season.crop) ?? 0) + season.parcel.areaHa);

  return {
    year,
    parcels: {
      count: parcels.length,
      totalAreaHa,
      cultivatedAreaHa,
      freeAreaHa: round(Math.max(0, totalAreaHa - cultivatedAreaHa)),
    },
    crops: [...byCrop.entries()]
      .map(([crop, areaHa]) => ({ crop, name: cropName(crop as keyof typeof CROPS), areaHa: round(areaHa) }))
      .sort((a, b) => b.areaHa - a.areaHa),
    activeWork: activeWork.map((work) => serializeWork(work)),
    activeIrrigation: activeIrrigation.map((run) => serializeIrrigation(run)),
    tasks: { open: openTasks, overdue: overdueTasks },
    alerts,
  };
});

dashboardRoutes.get(
  '/sync/changes',
  {
    summary: 'Modificările de după ultima sincronizare offline',
    roles: ROLES.all,
    query: z.object({ since: z.coerce.date().optional() }),
  },
  async ({ auth, query }) => {
    const farmId = auth.farmId;
    const serverTime = new Date();
    const updated = query.since ? { updatedAt: { gte: query.since } } : {};
    const [parcels, seasons, machines, tasks, work, irrigation, taskIds] = await Promise.all([
      prisma.parcel.findMany({ where: { farmId, ...updated } }),
      prisma.cropSeason.findMany({ where: { parcel: { farmId }, ...updated } }),
      prisma.machine.findMany({ where: { farmId, ...updated } }),
      prisma.task.findMany({ where: { farmId, ...updated }, include: { parcel: { select: { id: true, name: true, code: true } }, assignee: { select: { id: true, fullName: true } } } }),
      prisma.workSession.findMany({ where: { farmId, ...updated }, include: workInclude, take: 500, orderBy: { updatedAt: 'desc' } }),
      prisma.irrigationRun.findMany({ where: { farmId, ...updated }, include: irrigationInclude, take: 500, orderBy: { updatedAt: 'desc' } }),
      prisma.task.findMany({ where: { farmId }, select: { id: true } }),
    ]);
    return {
      serverTime: serverTime.toISOString(),
      parcels,
      seasons: seasons.map((season) => ({
        ...season,
        expectedPricePerT: season.expectedPricePerT === null ? null : Number(season.expectedPricePerT),
        actualPricePerT: season.actualPricePerT === null ? null : Number(season.actualPricePerT),
      })),
      machines,
      tasks,
      work: work.map((item) => serializeWork(item)),
      irrigation: irrigation.map((item) => serializeIrrigation(item)),
      // Lista completă de id-uri permite aplicației să elimine local task-urile șterse.
      taskIds: taskIds.map((task) => task.id),
    };
  },
);
