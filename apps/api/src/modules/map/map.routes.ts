import { CROPS, cropProgress } from '@fermapp/domain';
import { prisma } from '../../lib/prisma';
import { ApiRouter, ROLES } from '../../lib/route';
import { irrigationInclude, serializeIrrigation } from '../irrigation/irrigation.service';
import { serializeWork, workInclude } from '../work/work.service';

export const mapRoutes = new ApiRouter('/map', 'Map');

const SYSTEM_LABELS = { REEL: 'Hose reel', PIVOT: 'Pivot', LINEAR: 'Linear' } as const;
const ACTIVE = ['RUNNING', 'PAUSED'] as const;

mapRoutes.get(
  '/overview',
  { summary: 'Live status of all parcels, for display on the map', roles: ROLES.all },
  async ({ auth }) => {
    const now = new Date();
    const year = now.getFullYear();
    const [parcels, work, irrigation, openTasks] = await Promise.all([
      prisma.parcel.findMany({
        where: { farmId: auth.farmId, archivedAt: null },
        select: {
          id: true,
          seasons: { where: { year }, take: 1 },
          satellite: { orderBy: { date: 'desc' }, take: 1, select: { ndvi: true } },
          plans: { where: { active: true, year: { gte: year } }, orderBy: { year: 'asc' }, take: 1, select: { crop: true, year: true } },
        },
      }),
      prisma.workSession.findMany({
        where: { farmId: auth.farmId, status: { in: [...ACTIVE] } },
        include: { ...workInclude, locations: { orderBy: { recordedAt: 'desc' }, take: 1 } },
        orderBy: { startedAt: 'asc' },
      }),
      prisma.irrigationRun.findMany({
        where: { farmId: auth.farmId, status: { in: [...ACTIVE] } },
        include: irrigationInclude,
        orderBy: { startedAt: 'asc' },
      }),
      prisma.task.groupBy({
        by: ['parcelId'],
        where: { farmId: auth.farmId, status: { not: 'DONE' }, parcelId: { not: null } },
        _count: { _all: true },
      }),
    ]);

    const tasksByParcel = new Map(openTasks.map((row) => [row.parcelId, row._count._all]));

    return parcels.map((parcel) => {
      const planned = parcel.plans[0];
      const recorded = parcel.seasons[0];
      // The last crop declared in the wizard is not the crop in the field when the plan starts in a future year.
      const season = recorded && planned && planned.year > year && !recorded.sowingDate ? undefined : recorded;
      const code = season?.crop ?? planned?.crop ?? null;
      const progress = season
        ? cropProgress({
            crop: season.crop,
            sowingDate: season.sowingDate,
            harvestDate: season.harvestDate,
            expectedHarvestDate: season.expectedHarvestDate,
            now,
          })
        : null;
      return {
        parcelId: parcel.id,
        crop: code ? { code, name: CROPS[code].name, planned: !season, year: season?.year ?? planned!.year } : null,
        growthPct: progress && progress.status !== 'NOT_SOWN' ? progress.percent : null,
        openTasks: tasksByParcel.get(parcel.id) ?? 0,
        ndvi: parcel.satellite[0]?.ndvi ?? null,
        work: work
          .filter((item) => item.parcelId === parcel.id)
          .map(({ locations, ...item }) => ({
            ...serializeWork(item, now),
            lastLocation: locations[0] ? { ...locations[0], id: locations[0].id.toString() } : null,
          })),
        irrigation: irrigation
          .filter((run) => run.parcelId === parcel.id)
          .map((run, index) => ({ ...serializeIrrigation(run, now), label: `${SYSTEM_LABELS[run.systemType]} #${index + 1}` })),
      };
    });
  },
);
