import { MachineType } from '@prisma/client';
import { z } from 'zod';
import { requireMachine, requireMember } from '../../lib/access';
import { prisma } from '../../lib/prisma';
import { ApiRouter, idParams, ROLES } from '../../lib/route';

export const machineRoutes = new ApiRouter('/machines', 'Utilaje');

const fields = {
  name: z.string().trim().min(1).max(120),
  type: z.enum(MachineType),
  brand: z.string().trim().max(60).nullable().optional(),
  model: z.string().trim().max(60).nullable().optional(),
  powerHp: z.number().positive().max(2000).nullable().optional(),
  avgConsumptionLh: z.number().positive().max(500).nullable().optional(),
  operatorId: z.uuid().nullable().optional(),
};

const include = { operator: { select: { id: true, fullName: true } } };

machineRoutes.get('/', { summary: 'Listează utilajele', roles: ROLES.all }, async ({ auth }) =>
  prisma.machine.findMany({
    where: { farmId: auth.farmId, archivedAt: null },
    include,
    orderBy: { name: 'asc' },
  }),
);

machineRoutes.post(
  '/',
  { summary: 'Adaugă un utilaj', roles: ROLES.managers, body: z.object(fields) },
  async ({ auth, body }) => {
    if (body.operatorId) await requireMember(auth.farmId, body.operatorId);
    return prisma.machine.create({ data: { ...body, farmId: auth.farmId }, include });
  },
);

machineRoutes.patch(
  '/:id',
  { summary: 'Modifică un utilaj', roles: ROLES.managers, params: idParams, body: z.object(fields).partial() },
  async ({ auth, params, body }) => {
    await requireMachine(auth.farmId, params.id);
    if (body.operatorId) await requireMember(auth.farmId, body.operatorId);
    return prisma.machine.update({ where: { id: params.id }, data: body, include });
  },
);

machineRoutes.delete(
  '/:id',
  { summary: 'Arhivează un utilaj', roles: ROLES.managers, params: idParams },
  async ({ auth, params }) => {
    await requireMachine(auth.farmId, params.id);
    await prisma.machine.update({ where: { id: params.id }, data: { archivedAt: new Date() } });
  },
);
