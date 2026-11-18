import { Role } from '@prisma/client';
import { z } from 'zod';
import { requireMember } from '../../lib/access';
import { badRequest, conflict } from '../../lib/errors';
import { prisma } from '../../lib/prisma';
import { ApiRouter, ROLES } from '../../lib/route';
import { email, hashPassword, password } from '../auth/auth.routes';

export const teamRoutes = new ApiRouter('/team', 'Echipă');

const userParams = z.object({ userId: z.uuid() });

const member = (m: { role: Role; user: { id: string; fullName: string; email: string } }) => ({
  userId: m.user.id,
  fullName: m.user.fullName,
  email: m.user.email,
  role: m.role,
});

async function assertAnotherAdmin(farmId: string, userId: string) {
  const others = await prisma.farmMembership.count({ where: { farmId, role: 'ADMIN', userId: { not: userId } } });
  if (others === 0) throw badRequest('Ferma trebuie să aibă cel puțin un administrator.');
}

teamRoutes.get('/', { summary: 'Listează membrii fermei', roles: ROLES.all }, async ({ auth }) => {
  const members = await prisma.farmMembership.findMany({
    where: { farmId: auth.farmId },
    include: { user: { select: { id: true, fullName: true, email: true } } },
    orderBy: { createdAt: 'asc' },
  });
  return members.map(member);
});

teamRoutes.post(
  '/',
  {
    summary: 'Adaugă un membru. Parola este necesară doar pentru conturile noi.',
    roles: ROLES.admin,
    body: z.object({
      email,
      fullName: z.string().trim().min(2).max(120),
      role: z.enum(Role),
      password: password.optional(),
    }),
  },
  async ({ body, auth }) => {
    let user = await prisma.user.findUnique({ where: { email: body.email } });
    if (!user) {
      if (!body.password) throw badRequest('Parola este necesară pentru un cont nou.');
      user = await prisma.user.create({
        data: { email: body.email, fullName: body.fullName, passwordHash: await hashPassword(body.password) },
      });
    }
    const existing = await prisma.farmMembership.findUnique({
      where: { farmId_userId: { farmId: auth.farmId, userId: user.id } },
    });
    if (existing) throw conflict('Utilizatorul face deja parte din fermă.');
    await prisma.farmMembership.create({ data: { farmId: auth.farmId, userId: user.id, role: body.role } });
    return { userId: user.id, fullName: user.fullName, email: user.email, role: body.role };
  },
);

teamRoutes.patch(
  '/:userId',
  {
    summary: 'Schimbă rolul unui membru',
    roles: ROLES.admin,
    params: userParams,
    body: z.object({ role: z.enum(Role) }),
  },
  async ({ params, body, auth }) => {
    const current = await requireMember(auth.farmId, params.userId);
    if (current.role === 'ADMIN' && body.role !== 'ADMIN') await assertAnotherAdmin(auth.farmId, params.userId);
    const updated = await prisma.farmMembership.update({
      where: { id: current.id },
      data: { role: body.role },
      include: { user: { select: { id: true, fullName: true, email: true } } },
    });
    return member(updated);
  },
);

teamRoutes.delete(
  '/:userId',
  { summary: 'Elimină un membru din fermă', roles: ROLES.admin, params: userParams },
  async ({ params, auth }) => {
    const current = await requireMember(auth.farmId, params.userId);
    if (current.role === 'ADMIN') await assertAnotherAdmin(auth.farmId, params.userId);
    await prisma.$transaction([
      prisma.farmMembership.delete({ where: { id: current.id } }),
      prisma.refreshToken.updateMany({
        where: { userId: params.userId, farmId: auth.farmId, revokedAt: null },
        data: { revokedAt: new Date() },
      }),
    ]);
  },
);
