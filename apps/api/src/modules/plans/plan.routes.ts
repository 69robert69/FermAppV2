import {
  buildParcelPlan,
  computeGeoMetrics,
  CROP_CODES,
  nextSeasonYear,
  OTHER_CROP,
  type ParcelPlanData,
  type PolygonGeometry,
} from '@fermapp/domain';
import { z } from 'zod';
import { requireActiveParcel, requireParcel } from '../../lib/access';
import { badRequest, forbidden } from '../../lib/errors';
import { prisma } from '../../lib/prisma';
import { ApiRouter, idParams, ROLES } from '../../lib/route';
import type { AuthContext } from '../../lib/tokens';
import { createParcelData, loadSoilInBackground, parcelFields, polygonSchema } from '../parcels/parcels.routes';
import {
  announcePlan,
  historyWithLastCrop,
  isCropCode,
  lastCropLabel,
  loadSiteData,
  parcelCropHistory,
  planForRole,
  savePlan,
} from './plan.service';

export const planRoutes = new ApiRouter('/parcels', 'Plan tehnologic');

const choice = {
  lastCrop: z.union([z.enum(CROP_CODES), z.literal(OTHER_CROP)]),
  lastCropName: z.string().trim().max(60).optional(),
  plannedCrop: z.enum(CROP_CODES),
};

const exactlyOneTarget = (value: { parcelId?: string; geometry?: unknown; draft?: unknown }) =>
  Boolean(value.parcelId) !== Boolean(value.geometry ?? value.draft);

const analyzeBody = z
  .object({ ...choice, parcelId: z.uuid().optional(), name: z.string().trim().max(120).optional(), geometry: polygonSchema.optional() })
  .refine(exactlyOneTarget, { message: 'Trimite fie parcela existentă, fie conturul parcelei noi.' });

const completeBody = z
  .object({ ...choice, parcelId: z.uuid().optional(), draft: z.object(parcelFields).optional() })
  .refine(exactlyOneTarget, { message: 'Trimite fie parcela existentă, fie datele parcelei noi.' });

async function buildPlanFor(
  auth: AuthContext,
  input: {
    parcelId?: string;
    name?: string;
    geometry?: unknown;
    lastCrop: z.output<typeof choice.lastCrop>;
    lastCropName?: string;
    plannedCrop: z.output<typeof choice.plannedCrop>;
  },
): Promise<ParcelPlanData> {
  const existing = input.parcelId ? await requireActiveParcel(auth.farmId, input.parcelId) : null;
  const metrics = existing ? null : computeGeoMetrics(input.geometry as PolygonGeometry);
  const lat = existing?.centerLat ?? metrics!.center.lat;
  const lng = existing?.centerLng ?? metrics!.center.lng;

  const [site, history] = await Promise.all([
    loadSiteData(lat, lng, existing?.id),
    existing ? parcelCropHistory(existing.id, nextSeasonYear()) : Promise.resolve([]),
  ]);
  return buildParcelPlan({
    crop: input.plannedCrop,
    parcelName: existing?.name ?? input.name ?? 'Parcelă nouă',
    areaHa: existing?.areaHa ?? metrics!.areaHa,
    history: historyWithLastCrop(history, input.lastCrop),
    lastCropName: lastCropLabel(input.lastCrop, input.lastCropName),
    ...site,
  });
}

planRoutes.post(
  '/wizard/analyze',
  {
    summary: 'Analizează parcela și generează planul fără să salveze nimic',
    roles: ROLES.planners,
    status: 200,
    body: analyzeBody,
  },
  async ({ auth, body }) => planForRole(await buildPlanFor(auth, body), auth.role),
);

planRoutes.post(
  '/wizard/complete',
  {
    summary: 'Salvează parcela, culturile, planul tehnologic și task-urile',
    roles: ROLES.planners,
    body: completeBody,
  },
  async ({ auth, body }) => {
    if (body.draft && auth.role === 'AGRONOMIST') throw forbidden('Doar managerii pot crea parcele noi.');
    const plan = await buildPlanFor(auth, {
      ...body,
      name: body.draft?.name,
      geometry: body.draft?.geometry,
    });
    const lastYear = nextSeasonYear() - 1;
    if (plan.year <= lastYear) throw badRequest('Anul planului trebuie să fie după ultima cultură.');

    const parcel = await prisma.$transaction(async (tx) => {
      const target = body.parcelId
        ? await tx.parcel.update({ where: { id: body.parcelId }, data: { lastCrop: body.lastCrop } })
        : await tx.parcel.create({ data: { ...(await createParcelData(auth.farmId, body.draft!)), lastCrop: body.lastCrop } });

      if (isCropCode(body.lastCrop)) {
        await tx.cropSeason.upsert({
          where: { parcelId_year: { parcelId: target.id, year: lastYear } },
          create: { parcelId: target.id, year: lastYear, crop: body.lastCrop },
          update: { crop: body.lastCrop },
        });
      }
      await tx.cropSeason.upsert({
        where: { parcelId_year: { parcelId: target.id, year: plan.year } },
        create: {
          parcelId: target.id,
          year: plan.year,
          crop: plan.crop,
          expectedHarvestDate: new Date(plan.harvestDate),
          expectedYieldTHa: plan.report.expectedYieldTHa,
        },
        update: {
          crop: plan.crop,
          expectedHarvestDate: new Date(plan.harvestDate),
          expectedYieldTHa: plan.report.expectedYieldTHa,
        },
      });
      await savePlan(tx, { farmId: auth.farmId, parcelId: target.id, plan, trigger: 'WIZARD' });
      return target;
    });

    if (body.draft) loadSoilInBackground(parcel);
    announcePlan(auth.farmId, parcel.id);
    return { parcel, plan: planForRole(plan, auth.role) };
  },
);

planRoutes.get(
  '/:id/plan',
  { summary: 'Planul activ și rapoartele salvate ale parcelei', roles: ROLES.all, params: idParams },
  async ({ auth, params }) => {
    await requireParcel(auth.farmId, params.id);
    const plans = await prisma.parcelPlan.findMany({
      where: { parcelId: params.id },
      orderBy: { createdAt: 'desc' },
      take: 20,
    });
    const view = (item: (typeof plans)[number]) => ({
      id: item.id,
      year: item.year,
      crop: item.crop,
      active: item.active,
      trigger: item.trigger,
      createdAt: item.createdAt,
      data: planForRole(item.data as unknown as ParcelPlanData, auth.role),
    });
    return { active: plans.filter((item) => item.active).map(view)[0] ?? null, history: plans.map(view) };
  },
);
