import { z } from 'zod';
import { prisma } from '../../lib/prisma';
import { ApiRouter, idParams, ROLES } from '../../lib/route';

export const notificationRoutes = new ApiRouter('/notifications', 'Notificări');

notificationRoutes.get(
  '/',
  {
    summary: 'Ultimele notificări ale utilizatorului',
    roles: ROLES.all,
    query: z.object({ limit: z.coerce.number().int().min(1).max(100).default(50) }),
  },
  async ({ auth, query }) =>
    prisma.notification.findMany({
      where: { userId: auth.userId, farmId: auth.farmId },
      orderBy: { createdAt: 'desc' },
      take: query.limit,
    }),
);

notificationRoutes.post(
  '/:id/read',
  { summary: 'Marchează notificarea drept citită', roles: ROLES.all, status: 204, params: idParams },
  async ({ auth, params }) => {
    await prisma.notification.updateMany({
      where: { id: params.id, userId: auth.userId, readAt: null },
      data: { readAt: new Date() },
    });
  },
);
