import bcrypt from 'bcryptjs';
import { rateLimit } from 'express-rate-limit';
import { z } from 'zod';
import { env } from '../../config/env';
import { requireMember } from '../../lib/access';
import { conflict, forbidden, unauthorized } from '../../lib/errors';
import { prisma } from '../../lib/prisma';
import { ApiRouter, ROLES } from '../../lib/route';
import { issueSession, sha256 } from '../../lib/tokens';

export const authRoutes = new ApiRouter('/auth', 'Autentificare');

const BCRYPT_ROUNDS = 12;
/** Compararea cu un hash fals păstrează același timp de răspuns pentru conturi inexistente. */
const DUMMY_HASH = bcrypt.hashSync('fermapp-dummy-password', BCRYPT_ROUNDS);

const credentialLimiter = rateLimit({
  windowMs: 15 * 60_000,
  limit: env.NODE_ENV === 'test' ? 10_000 : 20,
  standardHeaders: 'draft-7',
  legacyHeaders: false,
  message: { error: { code: 'RATE_LIMITED', message: 'Prea multe încercări. Reîncearcă peste câteva minute.' } },
});

export const email = z.email().transform((value) => value.trim().toLowerCase());
export const password = z.string().min(10, 'Parola trebuie să aibă minimum 10 caractere.').max(72);

const publicUser = (user: { id: string; email: string; fullName: string }) => ({
  id: user.id,
  email: user.email,
  fullName: user.fullName,
});

export const hashPassword = (value: string) => bcrypt.hash(value, BCRYPT_ROUNDS);

authRoutes.post(
  '/register',
  {
    summary: 'Creează un cont și o fermă nouă',
    roles: 'public',
    limiter: credentialLimiter,
    body: z.object({
      email,
      password,
      fullName: z.string().trim().min(2).max(120),
      farmName: z.string().trim().min(2).max(120),
      county: z.string().trim().max(60).optional(),
    }),
  },
  async ({ body }) => {
    const existing = await prisma.user.findUnique({ where: { email: body.email } });
    if (existing) throw conflict('Există deja un cont cu această adresă de email.');
    const passwordHash = await hashPassword(body.password);
    const { user, farm } = await prisma.$transaction(async (tx) => {
      const farm = await tx.farm.create({ data: { name: body.farmName, county: body.county } });
      const user = await tx.user.create({
        data: {
          email: body.email,
          fullName: body.fullName,
          passwordHash,
          memberships: { create: { farmId: farm.id, role: 'ADMIN' } },
        },
      });
      return { user, farm };
    });
    const session = await issueSession({ userId: user.id, farmId: farm.id, role: 'ADMIN' });
    return { user: publicUser(user), farm: { id: farm.id, name: farm.name }, role: 'ADMIN', ...session };
  },
);

authRoutes.post(
  '/login',
  {
    summary: 'Autentificare cu email și parolă',
    roles: 'public',
    status: 200,
    limiter: credentialLimiter,
    body: z.object({ email, password: z.string().min(1).max(72) }),
  },
  async ({ body }) => {
    const user = await prisma.user.findUnique({
      where: { email: body.email },
      include: { memberships: { include: { farm: true }, orderBy: { createdAt: 'asc' } } },
    });
    const valid = await bcrypt.compare(body.password, user?.passwordHash ?? DUMMY_HASH);
    if (!user || !valid) throw unauthorized('Email sau parolă incorecte.');
    const membership = user.memberships[0];
    if (!membership) throw forbidden('Contul nu este asociat niciunei ferme.');
    const session = await issueSession({ userId: user.id, farmId: membership.farmId, role: membership.role });
    return {
      user: publicUser(user),
      farm: { id: membership.farm.id, name: membership.farm.name },
      role: membership.role,
      farms: user.memberships.map((m) => ({ id: m.farm.id, name: m.farm.name, role: m.role })),
      ...session,
    };
  },
);

authRoutes.post(
  '/refresh',
  {
    summary: 'Reînnoiește sesiunea și rotește tokenul de refresh',
    roles: 'public',
    status: 200,
    limiter: credentialLimiter,
    body: z.object({ refreshToken: z.string().min(20).max(200) }),
  },
  async ({ body }) => {
    const stored = await prisma.refreshToken.findUnique({ where: { tokenHash: sha256(body.refreshToken) } });
    if (!stored) throw unauthorized('Sesiune invalidă.');

    // Un token deja folosit indică o posibilă scurgere, deci se revocă toată familia lui.
    if (stored.revokedAt) {
      await prisma.refreshToken.updateMany({
        where: { familyId: stored.familyId, revokedAt: null },
        data: { revokedAt: new Date() },
      });
      throw unauthorized('Sesiune invalidă.');
    }
    if (stored.expiresAt < new Date()) throw unauthorized('Sesiunea a expirat.');

    const revoked = await prisma.refreshToken.updateMany({
      where: { id: stored.id, revokedAt: null },
      data: { revokedAt: new Date() },
    });
    if (revoked.count === 0) throw unauthorized('Sesiune invalidă.');

    const membership = await prisma.farmMembership.findUnique({
      where: { farmId_userId: { farmId: stored.farmId, userId: stored.userId } },
    });
    if (!membership) throw unauthorized('Accesul la fermă a fost retras.');

    return issueSession({ userId: stored.userId, farmId: stored.farmId, role: membership.role }, stored.familyId);
  },
);

authRoutes.post(
  '/logout',
  {
    summary: 'Închide sesiunea curentă',
    roles: ROLES.all,
    status: 204,
    body: z.object({ refreshToken: z.string().min(20).max(200) }),
  },
  async ({ body, auth }) => {
    const stored = await prisma.refreshToken.findUnique({ where: { tokenHash: sha256(body.refreshToken) } });
    if (stored?.userId === auth.userId) {
      await prisma.refreshToken.updateMany({
        where: { familyId: stored.familyId, revokedAt: null },
        data: { revokedAt: new Date() },
      });
    }
  },
);

authRoutes.get('/me', { summary: 'Profilul utilizatorului curent', roles: ROLES.all }, async ({ auth }) => {
  const user = await prisma.user.findUniqueOrThrow({
    where: { id: auth.userId },
    include: { memberships: { include: { farm: true } } },
  });
  return {
    user: publicUser(user),
    role: auth.role,
    farmId: auth.farmId,
    farms: user.memberships.map((m) => ({ id: m.farm.id, name: m.farm.name, role: m.role })),
  };
});

authRoutes.post(
  '/switch-farm',
  {
    summary: 'Schimbă ferma activă',
    roles: ROLES.all,
    status: 200,
    body: z.object({ farmId: z.uuid() }),
  },
  async ({ body, auth }) => {
    const membership = await requireMember(body.farmId, auth.userId);
    const session = await issueSession({ userId: auth.userId, farmId: body.farmId, role: membership.role });
    return { farmId: body.farmId, role: membership.role, ...session };
  },
);

authRoutes.post(
  '/push-token',
  {
    summary: 'Înregistrează dispozitivul pentru notificări push',
    roles: ROLES.all,
    status: 204,
    body: z.object({
      token: z.string().regex(/^Expo(nent)?PushToken\[.+\]$/, 'Token Expo invalid.').max(200),
      platform: z.enum(['ios', 'android']).optional(),
    }),
  },
  async ({ body, auth }) => {
    await prisma.pushDevice.upsert({
      where: { token: body.token },
      create: { token: body.token, platform: body.platform, userId: auth.userId },
      update: { userId: auth.userId, platform: body.platform },
    });
  },
);
