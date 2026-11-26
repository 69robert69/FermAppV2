import type { Prisma, Role } from '@prisma/client';
import { env } from '../../config/env';
import { logger } from '../../lib/logger';
import { prisma } from '../../lib/prisma';
import { emitToUser } from '../../realtime/socket';

const EXPO_PUSH_URL = 'https://exp.host/--/api/v2/push/send';
const EXPO_BATCH = 100;

interface NotificationInput {
  farmId: string;
  userIds?: string[];
  roles?: Role[];
  title: string;
  body: string;
  data?: Record<string, string>;
}

async function sendPush(tokens: string[], title: string, body: string, data?: Record<string, string>) {
  for (let i = 0; i < tokens.length; i += EXPO_BATCH) {
    const batch = tokens.slice(i, i + EXPO_BATCH);
    try {
      const response = await fetch(EXPO_PUSH_URL, {
        method: 'POST',
        signal: AbortSignal.timeout(10_000),
        headers: {
          'Content-Type': 'application/json',
          ...(env.EXPO_ACCESS_TOKEN ? { Authorization: `Bearer ${env.EXPO_ACCESS_TOKEN}` } : {}),
        },
        body: JSON.stringify(batch.map((to) => ({ to, title, body, data, sound: 'default', priority: 'high' }))),
      });
      if (!response.ok) {
        logger.warn('Expo Push rejected the batch', response.status);
        continue;
      }
      const result = (await response.json()) as { data?: { status: string; details?: { error?: string } }[] };
      const stale = batch.filter((_, index) => result.data?.[index]?.details?.error === 'DeviceNotRegistered');
      if (stale.length > 0) await prisma.pushDevice.deleteMany({ where: { token: { in: stale } } });
    } catch (error) {
      logger.warn('Push delivery failed', (error as Error).message);
    }
  }
}

/** Saves the notification and sends it live in the app and via push. */
export async function notify(input: NotificationInput) {
  const { farmId, userIds, roles, title, body, data } = input;
  const members = await prisma.farmMembership.findMany({
    where: {
      farmId,
      ...(userIds ? { userId: { in: userIds } } : {}),
      ...(roles ? { role: { in: roles } } : {}),
    },
    select: { userId: true },
  });
  const recipients = members.map((member) => member.userId);
  if (recipients.length === 0) return;

  const rows = await prisma.notification.createManyAndReturn({
    data: recipients.map((userId) => ({
      farmId,
      userId,
      title,
      body,
      data: data as Prisma.InputJsonValue | undefined,
    })),
  });
  for (const row of rows) emitToUser(row.userId, 'notification:new', row);

  const devices = await prisma.pushDevice.findMany({
    where: { userId: { in: recipients } },
    select: { token: true },
  });
  await sendPush(devices.map((device) => device.token), title, body, data);
}
