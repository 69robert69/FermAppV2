import { TaskPriority, TaskStatus, TaskType } from '@prisma/client';
import { z } from 'zod';
import { requireMember, requireParcel } from '../../lib/access';
import { forbidden, notFound } from '../../lib/errors';
import { prisma } from '../../lib/prisma';
import { ApiRouter, idParams, ROLES } from '../../lib/route';
import { emitToFarm } from '../../realtime/socket';
import { notify } from '../notifications/notifications.service';

export const taskRoutes = new ApiRouter('/tasks', 'To Do');

export const TASK_LABELS: Record<TaskType, string> = {
  FERTILIZATION: 'Fertilization',
  HERBICIDE: 'Herbicide application',
  FUNGICIDE: 'Fungicide application',
  INSECTICIDE: 'Insecticide application',
  PEST_CHECK: 'Pest check',
  DISEASE_CHECK: 'Disease check',
  MOISTURE_CHECK: 'Moisture check',
  LEAF_ANALYSIS: 'Leaf analysis',
  HARVEST: 'Harvest',
  SOIL_PREP: 'Seedbed preparation',
  SEED_PURCHASE: 'Seed purchase',
  SOWING: 'Sowing',
  EMERGENCE_CHECK: 'Emergence check',
  HARVEST_PREP: 'Harvest preparation',
};

const include = {
  parcel: { select: { id: true, name: true, code: true } },
  assignee: { select: { id: true, fullName: true } },
};

const fields = {
  type: z.enum(TaskType),
  title: z.string().trim().min(1).max(160).optional(),
  description: z.string().trim().max(2000).nullable().optional(),
  priority: z.enum(TaskPriority).default('MEDIUM'),
  dueDate: z.coerce.date(),
  parcelId: z.uuid().nullable().optional(),
  assigneeId: z.uuid().nullable().optional(),
};

const completion = (status: TaskStatus) => ({ completedAt: status === 'DONE' ? new Date() : null });

taskRoutes.get(
  '/types',
  { summary: 'Predefined tasks', roles: ROLES.all },
  async () => Object.entries(TASK_LABELS).map(([code, label]) => ({ code, label })),
);

taskRoutes.get(
  '/',
  {
    summary: 'List tasks',
    roles: ROLES.all,
    query: z.object({
      status: z.enum(TaskStatus).optional(),
      parcelId: z.uuid().optional(),
      mine: z.enum(['true', 'false']).default('false'),
    }),
  },
  async ({ auth, query }) =>
    prisma.task.findMany({
      where: {
        farmId: auth.farmId,
        status: query.status,
        parcelId: query.parcelId,
        ...(query.mine === 'true' ? { assigneeId: auth.userId } : {}),
      },
      include,
      orderBy: [{ status: 'asc' }, { dueDate: 'asc' }],
      take: 500,
    }),
);

taskRoutes.post(
  '/',
  { summary: 'Create a task', roles: ROLES.planners, body: z.object(fields) },
  async ({ auth, body }) => {
    if (body.parcelId) await requireParcel(auth.farmId, body.parcelId);
    if (body.assigneeId) await requireMember(auth.farmId, body.assigneeId);
    const task = await prisma.task.create({
      data: { ...body, title: body.title ?? TASK_LABELS[body.type], farmId: auth.farmId },
      include,
    });
    emitToFarm(auth.farmId, 'task:updated', task);
    if (task.assigneeId && task.assigneeId !== auth.userId) {
      await notify({
        farmId: auth.farmId,
        userIds: [task.assigneeId],
        title: 'New task',
        body: `${task.title}${task.parcel ? ` on parcel ${task.parcel.name}` : ''}.`,
        data: { type: 'task', id: task.id },
      });
    }
    return task;
  },
);

async function requireTask(farmId: string, id: string) {
  const task = await prisma.task.findFirst({ where: { id, farmId } });
  if (!task) throw notFound('Task');
  return task;
}

taskRoutes.patch(
  '/:id',
  {
    summary: 'Update a task',
    roles: ROLES.planners,
    params: idParams,
    body: z.object({
      ...fields,
      priority: z.enum(TaskPriority).optional(),
      type: z.enum(TaskType).optional(),
      dueDate: z.coerce.date().optional(),
      title: z.string().trim().min(1).max(160).optional(),
    }),
  },
  async ({ auth, params, body }) => {
    const current = await requireTask(auth.farmId, params.id);
    if (body.parcelId) await requireParcel(auth.farmId, body.parcelId);
    if (body.assigneeId) await requireMember(auth.farmId, body.assigneeId);
    const dueChanged = body.dueDate && body.dueDate.getTime() !== current.dueDate.getTime();
    const task = await prisma.task.update({
      where: { id: params.id },
      data: { ...body, ...(dueChanged ? { reminderSentAt: null } : {}) },
      include,
    });
    emitToFarm(auth.farmId, 'task:updated', task);
    return task;
  },
);

taskRoutes.post(
  '/:id/status',
  {
    summary: 'Change the status. Operators can only change their own tasks.',
    roles: ROLES.all,
    status: 200,
    params: idParams,
    body: z.object({ status: z.enum(TaskStatus) }),
  },
  async ({ auth, params, body }) => {
    const current = await requireTask(auth.farmId, params.id);
    if (auth.role === 'OPERATOR' && current.assigneeId !== auth.userId) {
      throw forbidden('You can only update tasks assigned to you.');
    }
    const task = await prisma.task.update({
      where: { id: params.id },
      data: { status: body.status, ...completion(body.status) },
      include,
    });
    emitToFarm(auth.farmId, 'task:updated', task);
    return task;
  },
);

taskRoutes.delete(
  '/:id',
  { summary: 'Delete a task', roles: ROLES.planners, params: idParams },
  async ({ auth, params }) => {
    await requireTask(auth.farmId, params.id);
    await prisma.task.delete({ where: { id: params.id } });
    emitToFarm(auth.farmId, 'task:updated', { id: params.id, deleted: true });
  },
);
