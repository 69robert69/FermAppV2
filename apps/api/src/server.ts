import { createServer } from 'node:http';
import { createApp } from './app';
import { env } from './config/env';
import { startScheduler, stopScheduler } from './jobs/scheduler';
import { redis } from './lib/cache';
import { logger } from './lib/logger';
import { prisma } from './lib/prisma';
import { startMqttBridge, stopMqttBridge } from './modules/iot/mqtt.bridge';
import { closeRealtime, initRealtime } from './realtime/socket';

const server = createServer(createApp());
initRealtime(server);
startMqttBridge();
if (env.JOBS_ENABLED) startScheduler();

server.listen(env.PORT, () => logger.info(`API started on port ${env.PORT}`));

let closing = false;
async function shutdown(signal: string) {
  if (closing) return;
  closing = true;
  logger.info(`Shutting down on signal ${signal}`);
  stopScheduler();
  stopMqttBridge();
  closeRealtime();
  server.close();
  await prisma.$disconnect();
  redis?.disconnect();
  process.exit(0);
}

process.on('SIGTERM', () => void shutdown('SIGTERM'));
process.on('SIGINT', () => void shutdown('SIGINT'));
