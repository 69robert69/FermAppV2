import { createHash, randomBytes, randomUUID } from 'node:crypto';
import type { Role } from '@prisma/client';
import jwt from 'jsonwebtoken';
import { env } from '../config/env';
import { unauthorized } from './errors';
import { prisma } from './prisma';

export interface AuthContext {
  userId: string;
  farmId: string;
  role: Role;
}

const ISSUER = 'fermapp';

export const sha256 = (value: string) => createHash('sha256').update(value).digest('hex');

export function signAccessToken(auth: AuthContext): string {
  return jwt.sign({ farmId: auth.farmId, role: auth.role }, env.JWT_ACCESS_SECRET, {
    subject: auth.userId,
    algorithm: 'HS256',
    issuer: ISSUER,
    expiresIn: env.JWT_ACCESS_TTL_SECONDS,
  });
}

export function verifyAccessToken(token: string): AuthContext {
  try {
    const payload = jwt.verify(token, env.JWT_ACCESS_SECRET, {
      algorithms: ['HS256'],
      issuer: ISSUER,
    }) as jwt.JwtPayload;
    return { userId: payload.sub!, farmId: payload.farmId as string, role: payload.role as Role };
  } catch {
    throw unauthorized('Sesiunea a expirat. Autentifică-te din nou.');
  }
}

export async function issueRefreshToken(userId: string, farmId: string, familyId: string = randomUUID()) {
  const token = randomBytes(48).toString('base64url');
  await prisma.refreshToken.create({
    data: {
      userId,
      farmId,
      familyId,
      tokenHash: sha256(token),
      expiresAt: new Date(Date.now() + env.REFRESH_TOKEN_TTL_DAYS * 86_400_000),
    },
  });
  return token;
}

export async function issueSession(auth: AuthContext, familyId?: string) {
  return {
    accessToken: signAccessToken(auth),
    refreshToken: await issueRefreshToken(auth.userId, auth.farmId, familyId),
    expiresIn: env.JWT_ACCESS_TTL_SECONDS,
  };
}
