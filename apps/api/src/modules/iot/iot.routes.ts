import { IotDeviceKind, IotProtocol, type IotDevice } from '@prisma/client';
import { rateLimit } from 'express-rate-limit';
import { z } from 'zod';
import { env } from '../../config/env';
import { requireParcel } from '../../lib/access';
import { notFound, unauthorized } from '../../lib/errors';
import { prisma } from '../../lib/prisma';
import { ApiRouter, idParams, ROLES } from '../../lib/route';
import { authenticateDeviceKey, generateDeviceKey, ingestTelemetry, telemetrySchema } from './iot.service';

export const iotRoutes = new ApiRouter('/iot', 'IoT');

const publicDevice = ({ keyHash: _keyHash, ...device }: IotDevice) => device;

iotRoutes.get('/devices', { summary: 'List IoT devices', roles: ROLES.all }, async ({ auth }) => {
  const devices = await prisma.iotDevice.findMany({ where: { farmId: auth.farmId }, orderBy: { name: 'asc' } });
  return devices.map(publicDevice);
});

iotRoutes.post(
  '/devices',
  {
    summary: 'Register a device. The key is shown only once.',
    roles: ROLES.managers,
    body: z.object({
      name: z.string().trim().min(1).max(120),
      kind: z.enum(IotDeviceKind),
      protocol: z.enum(IotProtocol),
      vendor: z.string().trim().max(60).optional(),
      parcelId: z.uuid().nullable().optional(),
    }),
  },
  async ({ auth, body }) => {
    if (body.parcelId) await requireParcel(auth.farmId, body.parcelId);
    const { key, keyHash } = generateDeviceKey();
    const device = await prisma.iotDevice.create({ data: { ...body, farmId: auth.farmId, keyHash } });
    return { ...publicDevice(device), deviceKey: key };
  },
);

iotRoutes.delete(
  '/devices/:id',
  { summary: 'Delete a device and its readings', roles: ROLES.managers, params: idParams },
  async ({ auth, params }) => {
    const { count } = await prisma.iotDevice.deleteMany({ where: { id: params.id, farmId: auth.farmId } });
    if (count === 0) throw notFound('Device');
  },
);

iotRoutes.get(
  '/devices/:id/readings',
  {
    summary: 'Latest readings of a device',
    roles: ROLES.all,
    params: idParams,
    query: z.object({
      metric: z.string().trim().max(60).optional(),
      limit: z.coerce.number().int().min(1).max(1000).default(100),
    }),
  },
  async ({ auth, params, query }) => {
    const device = await prisma.iotDevice.findFirst({ where: { id: params.id, farmId: auth.farmId } });
    if (!device) throw notFound('Device');
    const readings = await prisma.iotReading.findMany({
      where: { deviceId: device.id, metric: query.metric },
      orderBy: { recordedAt: 'desc' },
      take: query.limit,
    });
    return readings.map((reading) => ({ ...reading, id: reading.id.toString() }));
  },
);

const ingestLimiter = rateLimit({
  windowMs: 60_000,
  limit: env.NODE_ENV === 'test' ? 10_000 : 120,
  standardHeaders: 'draft-7',
  legacyHeaders: false,
  keyGenerator: (req) => String(req.headers['x-device-key'] ?? 'anonim').slice(0, 64),
});

iotRoutes.post(
  '/ingest',
  {
    summary: 'Receive sensor readings, with the X-Device-Key header',
    roles: 'public',
    status: 202,
    limiter: ingestLimiter,
    body: telemetrySchema,
  },
  async ({ req, body }) => {
    const device = await authenticateDeviceKey(String(req.headers['x-device-key'] ?? ''));
    if (!device) throw unauthorized('Invalid device key.');
    return { accepted: await ingestTelemetry(device, body) };
  },
);
