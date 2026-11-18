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
  FERTILIZATION: 'Fertilizare',
  HERBICIDE: 'Erbicidare',
  FUNGICIDE: 'Fungicidare',
  INSECTICIDE: 'Insecticidare',
  PEST_CHECK: 'Verificare dăunători',
  DISEASE_CHECK: 'Verificare boli',
  MOISTURE_CHECK: 'Verificare umiditate',
  LEAF_ANALYSIS: 'Analiză frunze',
  HARVEST: 'Recoltare',
  SOIL_PREP: 'Pregătirea terenului',
  SEED_PURCHASE: 'Achiziție sămânță',
  SOWING: 'Semănat',
  EMERGENCE_CHECK: 'Verificare răsărire',
  HARVEST_PREP: 'Pregătire recoltare',
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
  { summary: 'Task-urile predefinite', roles: ROLES.all },
  async () => Object.entries(TASK_LABELS).map(([code, label]) => ({ code, label })),
);

taskRoutes.get(
  '/',
  {
    summary: 'Listează task-urile',
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
  { summary: 'Creează un task', roles: ROLES.planners, body: z.object(fields) },
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
        title: 'Task nou',
        body: `${task.title}${task.parcel ? ` pe parcela ${task.parcel.name}` : ''}.`,
        data: { type: 'task', id: task.id },
      });
    }
    return task;
  },
);

async function requireTask(farmId: string, id: string) {
  const task = await prisma.task.findFirst({ where: { id, farmId } });
  if (!task) throw notFound('Task-ul');
  return task;
}

taskRoutes.patch(
  '/:id',
  {
    summary: 'Modifică un task',
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
    summary: 'Schimbă statusul. Operatorii pot schimba doar task-urile proprii.',
    roles: ROLES.all,
    status: 200,
    params: idParams,
    body: z.object({ status: z.enum(TaskStatus) }),
  },
  async ({ auth, params, body }) => {
    const current = await requireTask(auth.farmId, params.id);
    if (auth.role === 'OPERATOR' && current.assigneeId !== auth.userId) {
      throw forbidden('Poți actualiza doar task-urile atribuite ție.');
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
  { summary: 'Șterge un task', roles: ROLES.planners, params: idParams },
  async ({ auth, params }) => {
    await requireTask(auth.farmId, params.id);
    await prisma.task.delete({ where: { id: params.id } });
    emitToFarm(auth.farmId, 'task:updated', { id: params.id, deleted: true });
  },
);
