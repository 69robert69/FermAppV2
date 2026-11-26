import bcrypt from 'bcryptjs';
import { rateLimit } from 'express-rate-limit';
import { z } from 'zod';
import { env } from '../../config/env';
import { requireMember } from '../../lib/access';
import { conflict, forbidden, unauthorized } from '../../lib/errors';
import { prisma } from '../../lib/prisma';
import { ApiRouter, ROLES } from '../../lib/route';
import { issueSession, sha256 } from '../../lib/tokens';

export const authRoutes = new ApiRouter('/auth', 'Authentication');

const BCRYPT_ROUNDS = 12;
/** Comparing against a fake hash keeps the same response time for non-existent accounts. */
const DUMMY_HASH = bcrypt.hashSync('fermapp-dummy-password', BCRYPT_ROUNDS);

const credentialLimiter = rateLimit({
  windowMs: 15 * 60_000,
  limit: env.NODE_ENV === 'test' ? 10_000 : 20,
  standardHeaders: 'draft-7',
  legacyHeaders: false,
  message: { error: { code: 'RATE_LIMITED', message: 'Too many attempts. Try again in a few minutes.' } },
});

export const email = z.email().transform((value) => value.trim().toLowerCase());
export const password = z.string().min(10, 'The password must be at least 10 characters long.').max(72);

const publicUser = (user: { id: string; email: string; fullName: string }) => ({
  id: user.id,
  email: user.email,
  fullName: user.fullName,
});

export const hashPassword = (value: string) => bcrypt.hash(value, BCRYPT_ROUNDS);

authRoutes.post(
  '/register',
  {
    summary: 'Create an account and a new farm',
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
    if (existing) throw conflict('An account with this email address already exists.');
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
    summary: 'Sign in with email and password',
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
    if (!user || !valid) throw unauthorized('Incorrect email or password.');
    const membership = user.memberships[0];
    if (!membership) throw forbidden('The account is not associated with any farm.');
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
    summary: 'Renew the session and rotate the refresh token',
    roles: 'public',
    status: 200,
    limiter: credentialLimiter,
    body: z.object({ refreshToken: z.string().min(20).max(200) }),
  },
  async ({ body }) => {
    const stored = await prisma.refreshToken.findUnique({ where: { tokenHash: sha256(body.refreshToken) } });
    if (!stored) throw unauthorized('Invalid session.');

    // A token that was already used indicates a possible leak, so its whole family is revoked.
    if (stored.revokedAt) {
      await prisma.refreshToken.updateMany({
        where: { familyId: stored.familyId, revokedAt: null },
        data: { revokedAt: new Date() },
      });
      throw unauthorized('Invalid session.');
    }
    if (stored.expiresAt < new Date()) throw unauthorized('Your session has expired.');

    const revoked = await prisma.refreshToken.updateMany({
      where: { id: stored.id, revokedAt: null },
      data: { revokedAt: new Date() },
    });
    if (revoked.count === 0) throw unauthorized('Invalid session.');

    const membership = await prisma.farmMembership.findUnique({
      where: { farmId_userId: { farmId: stored.farmId, userId: stored.userId } },
    });
    if (!membership) throw unauthorized('Access to the farm has been revoked.');

    return issueSession({ userId: stored.userId, farmId: stored.farmId, role: membership.role }, stored.familyId);
  },
);

authRoutes.post(
  '/logout',
  {
    summary: 'Close the current session',
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

authRoutes.get('/me', { summary: 'Current user profile', roles: ROLES.all }, async ({ auth }) => {
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
    summary: 'Switch the active farm',
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
    summary: 'Register the device for push notifications',
    roles: ROLES.all,
    status: 204,
    body: z.object({
      token: z.string().regex(/^Expo(nent)?PushToken\[.+\]$/, 'Invalid Expo token.').max(200),
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
