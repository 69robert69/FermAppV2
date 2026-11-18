import { randomBytes } from 'node:crypto';
import type { IotDevice } from '@prisma/client';
import { z } from 'zod';
import { prisma } from '../../lib/prisma';
import { sha256 } from '../../lib/tokens';
import { emitToFarm } from '../../realtime/socket';

export const telemetrySchema = z.object({
  readings: z
    .array(
      z.object({
        metric: z.string().trim().min(1).max(60),
        value: z.number().finite(),
        unit: z.string().trim().max(20).optional(),
        recordedAt: z.coerce.date().optional(),
      }),
    )
    .min(1)
    .max(500),
});

export type Telemetry = z.output<typeof telemetrySchema>;

export function generateDeviceKey() {
  const key = `fad_${randomBytes(32).toString('base64url')}`;
  return { key, keyHash: sha256(key) };
}

export async function authenticateDeviceKey(key: string) {
  if (!key) return null;
  return prisma.iotDevice.findUnique({ where: { keyHash: sha256(key) } });
}

/** Punctul comun pentru REST, MQTT și WebSocket; citirile retrimise se ignoră. */
export async function ingestTelemetry(device: Pick<IotDevice, 'id' | 'farmId'>, telemetry: Telemetry) {
  const now = new Date();
  const rows = telemetry.readings.map((reading) => ({
    deviceId: device.id,
    metric: reading.metric,
    value: reading.value,
    unit: reading.unit,
    recordedAt: reading.recordedAt ?? now,
  }));
  const { count } = await prisma.iotReading.createMany({ data: rows, skipDuplicates: true });
  await prisma.iotDevice.update({ where: { id: device.id }, data: { lastSeenAt: now } });
  emitToFarm(device.farmId, 'iot:reading', { deviceId: device.id, readings: rows });
  return count;
}
