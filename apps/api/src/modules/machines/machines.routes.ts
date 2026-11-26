import { TILLAGE_CODES } from '@fermapp/domain';
import { MachineType } from '@prisma/client';
import { z } from 'zod';
import { requireMachine, requireMember } from '../../lib/access';
import { prisma } from '../../lib/prisma';
import { ApiRouter, idParams, ROLES } from '../../lib/route';

export const machineRoutes = new ApiRouter('/machines', 'Machines');

const fields = {
  name: z.string().trim().min(1).max(120),
  type: z.enum(MachineType),
  brand: z.string().trim().max(60).nullable().optional(),
  model: z.string().trim().max(60).nullable().optional(),
  powerHp: z.number().positive().max(2000).nullable().optional(),
  avgConsumptionLh: z.number().positive().max(500).nullable().optional(),
  operatorId: z.uuid().nullable().optional(),
  /** Tillage technologies the machine suits; empty uses the defaults of its type. */
  tillageSystems: z.array(z.enum(TILLAGE_CODES)).max(TILLAGE_CODES.length).optional(),
};

const include = { operator: { select: { id: true, fullName: true } } };

machineRoutes.get('/', { summary: 'List machines', roles: ROLES.all }, async ({ auth }) =>
  prisma.machine.findMany({
    where: { farmId: auth.farmId, archivedAt: null },
    include,
    orderBy: { name: 'asc' },
  }),
);

machineRoutes.post(
  '/',
  { summary: 'Add a machine', roles: ROLES.managers, body: z.object(fields) },
  async ({ auth, body }) => {
    if (body.operatorId) await requireMember(auth.farmId, body.operatorId);
    return prisma.machine.create({ data: { ...body, farmId: auth.farmId }, include });
  },
);

machineRoutes.patch(
  '/:id',
  { summary: 'Update a machine', roles: ROLES.managers, params: idParams, body: z.object(fields).partial() },
  async ({ auth, params, body }) => {
    await requireMachine(auth.farmId, params.id);
    if (body.operatorId) await requireMember(auth.farmId, body.operatorId);
    return prisma.machine.update({ where: { id: params.id }, data: body, include });
  },
);

machineRoutes.delete(
  '/:id',
  { summary: 'Archive a machine', roles: ROLES.managers, params: idParams },
  async ({ auth, params }) => {
    await requireMachine(auth.farmId, params.id);
    await prisma.machine.update({ where: { id: params.id }, data: { archivedAt: new Date() } });
  },
);
