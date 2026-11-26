import { COST_CATEGORIES, summarizeCosts } from '@fermapp/domain';
import type { CostEntry } from '@prisma/client';
import { z } from 'zod';
import { requireParcel } from '../../lib/access';
import { notFound } from '../../lib/errors';
import { prisma } from '../../lib/prisma';
import { ApiRouter, idParams, ROLES } from '../../lib/route';

export const costRoutes = new ApiRouter('', 'Costs');

const fields = {
  parcelId: z.uuid(),
  year: z.number().int().min(2000).max(2100),
  category: z.enum(COST_CATEGORIES),
  amount: z.number().positive().max(100_000_000),
  currency: z.literal('EUR').default('EUR'),
  date: z.coerce.date().optional(),
  notes: z.string().trim().max(500).nullable().optional(),
};

const serialize = (cost: CostEntry) => ({ ...cost, amount: Number(cost.amount) });

costRoutes.get(
  '/costs',
  {
    summary: 'List costs',
    roles: ROLES.planners,
    query: z.object({
      parcelId: z.uuid().optional(),
      year: z.coerce.number().int().min(2000).max(2100).optional(),
    }),
  },
  async ({ auth, query }) => {
    const costs = await prisma.costEntry.findMany({
      where: { farmId: auth.farmId, parcelId: query.parcelId, year: query.year },
      orderBy: { date: 'desc' },
      take: 500,
    });
    return costs.map(serialize);
  },
);

costRoutes.post(
  '/costs',
  { summary: 'Add a cost', roles: ROLES.managers, body: z.object(fields) },
  async ({ auth, body }) => {
    await requireParcel(auth.farmId, body.parcelId);
    return serialize(await prisma.costEntry.create({ data: { ...body, farmId: auth.farmId } }));
  },
);

async function requireCost(farmId: string, id: string) {
  const cost = await prisma.costEntry.findFirst({ where: { id, farmId } });
  if (!cost) throw notFound('Cost');
  return cost;
}

const { parcelId: _parcelId, ...editable } = fields;

costRoutes.patch(
  '/costs/:id',
  {
    summary: 'Update a cost',
    roles: ROLES.managers,
    params: idParams,
    body: z.object({ ...editable, currency: z.string().trim().length(3).toUpperCase().optional() }).partial(),
  },
  async ({ auth, params, body }) => {
    await requireCost(auth.farmId, params.id);
    return serialize(await prisma.costEntry.update({ where: { id: params.id }, data: body }));
  },
);

costRoutes.delete(
  '/costs/:id',
  { summary: 'Delete a cost', roles: ROLES.managers, params: idParams },
  async ({ auth, params }) => {
    await requireCost(auth.farmId, params.id);
    await prisma.costEntry.delete({ where: { id: params.id } });
  },
);

costRoutes.get(
  '/parcels/:id/costs/summary',
  {
    summary: 'Cost per hectare, total cost and estimated or actual profit',
    roles: ROLES.planners,
    params: idParams,
    query: z.object({ year: z.coerce.number().int().min(2000).max(2100).optional() }),
  },
  async ({ auth, params, query }) => {
    const parcel = await requireParcel(auth.farmId, params.id);
    const year = query.year ?? new Date().getFullYear();
    const [costs, season] = await Promise.all([
      prisma.costEntry.findMany({ where: { parcelId: parcel.id, year } }),
      prisma.cropSeason.findUnique({ where: { parcelId_year: { parcelId: parcel.id, year } } }),
    ]);
    return {
      year,
      areaHa: parcel.areaHa,
      crop: season?.crop ?? null,
      ...summarizeCosts({
        areaHa: parcel.areaHa,
        costs: costs.map((cost) => ({ category: cost.category, amount: Number(cost.amount) })),
        expectedYieldTHa: season?.expectedYieldTHa,
        expectedPricePerT: season?.expectedPricePerT == null ? null : Number(season.expectedPricePerT),
        actualYieldT: season?.yieldT,
        actualPricePerT: season?.actualPricePerT == null ? null : Number(season.actualPricePerT),
      }),
    };
  },
);
