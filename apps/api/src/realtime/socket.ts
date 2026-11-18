import type { Server as HttpServer } from 'node:http';
import { createAdapter } from '@socket.io/redis-adapter';
import { Server } from 'socket.io';
import { env } from '../config/env';
import { redis } from '../lib/cache';
import { logger } from '../lib/logger';
import { verifyAccessToken } from '../lib/tokens';
import { authenticateDeviceKey, ingestTelemetry, telemetrySchema } from '../modules/iot/iot.service';

let io: Server | null = null;

export type RealtimeEvent =
  | 'work:progress'
  | 'work:updated'
  | 'irrigation:progress'
  | 'irrigation:updated'
  | 'task:updated'
  | 'alert:new'
  | 'notification:new'
  | 'iot:reading'
  | 'machine:location'
  | 'parcel:updated';

const farmRoom = (farmId: string) => `farm:${farmId}`;
const userRoom = (userId: string) => `user:${userId}`;

export function initRealtime(server: HttpServer) {
  io = new Server(server, {
    cors: { origin: env.CORS_ORIGINS.split(',').map((origin) => origin.trim()) },
    serveClient: false,
  });

  if (redis) {
    const pub = redis.duplicate();
    const sub = redis.duplicate();
    io.adapter(createAdapter(pub, sub));
  }

  io.use((socket, next) => {
    try {
      socket.data.auth = verifyAccessToken(String(socket.handshake.auth.token ?? ''));
      next();
    } catch {
      next(new Error('UNAUTHORIZED'));
    }
  });

  io.on('connection', (socket) => {
    const { farmId, userId } = socket.data.auth as { farmId: string; userId: string };
    void socket.join([farmRoom(farmId), userRoom(userId)]);
  });

  // Spațiu separat pentru senzori, autentificați cu cheia dispozitivului.
  const iot = io.of('/iot');
  iot.use(async (socket, next) => {
    const device = await authenticateDeviceKey(String(socket.handshake.auth.deviceKey ?? '')).catch(() => null);
    if (!device) {
      next(new Error('UNAUTHORIZED'));
      return;
    }
    socket.data.device = device;
    next();
  });
  iot.on('connection', (socket) => {
    socket.on('telemetry', async (payload: unknown, ack?: (result: unknown) => void) => {
      const parsed = telemetrySchema.safeParse(payload);
      if (!parsed.success) {
        ack?.({ ok: false, error: 'INVALID_PAYLOAD' });
        return;
      }
      try {
        const accepted = await ingestTelemetry(socket.data.device, parsed.data);
        ack?.({ ok: true, accepted });
      } catch (error) {
        logger.error('Telemetrie WebSocket eșuată', error);
        ack?.({ ok: false, error: 'INGEST_FAILED' });
      }
    });
  });

  return io;
}

export const emitToFarm = (farmId: string, event: RealtimeEvent, payload: unknown) => {
  io?.to(farmRoom(farmId)).emit(event, payload);
};

export const emitToUser = (userId: string, event: RealtimeEvent, payload: unknown) => {
  io?.to(userRoom(userId)).emit(event, payload);
};

export const closeRealtime = () => io?.close();
