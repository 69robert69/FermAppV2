import { Redis } from 'ioredis';
import { env } from '../config/env';
import { logger } from './logger';

export const redis = env.REDIS_URL
  ? new Redis(env.REDIS_URL, { maxRetriesPerRequest: 2, lazyConnect: false })
  : null;

redis?.on('error', (error) => logger.warn('Redis unavailable', error.message));

const MAX_MEMORY_ENTRIES = 5000;
const memory = new Map<string, { value: string; expiresAt: number }>();

function memorySet(key: string, value: string, ttlSeconds: number) {
  if (memory.size >= MAX_MEMORY_ENTRIES) {
    const oldest = memory.keys().next().value;
    if (oldest !== undefined) memory.delete(oldest);
  }
  memory.set(key, { value, expiresAt: Date.now() + ttlSeconds * 1000 });
}

function memoryGet(key: string): string | null {
  const entry = memory.get(key);
  if (!entry) return null;
  if (entry.expiresAt < Date.now()) {
    memory.delete(key);
    return null;
  }
  return entry.value;
}

async function read(key: string): Promise<string | null> {
  if (redis?.status === 'ready') {
    try {
      return await redis.get(key);
    } catch (error) {
      logger.warn('Redis read failed', (error as Error).message);
    }
  }
  return memoryGet(key);
}

async function write(key: string, value: string, ttlSeconds: number) {
  if (redis?.status === 'ready') {
    try {
      await redis.set(key, value, 'EX', ttlSeconds);
      return;
    } catch (error) {
      logger.warn('Redis write failed', (error as Error).message);
    }
  }
  memorySet(key, value, ttlSeconds);
}

/** Returns the cached value or computes it; simultaneous calls share the same result. */
const inFlight = new Map<string, Promise<unknown>>();

export async function cached<T>(key: string, ttlSeconds: number, load: () => Promise<T>): Promise<T> {
  const hit = await read(key);
  if (hit !== null) return JSON.parse(hit) as T;

  const running = inFlight.get(key);
  if (running) return running as Promise<T>;

  const promise = load()
    .then(async (value) => {
      await write(key, JSON.stringify(value), ttlSeconds);
      return value;
    })
    .finally(() => inFlight.delete(key));
  inFlight.set(key, promise);
  return promise;
}

/** Short lock, so a scheduled job runs on a single instance. */
export async function acquireLock(key: string, ttlSeconds: number): Promise<boolean> {
  if (redis?.status === 'ready') {
    return (await redis.set(`lock:${key}`, '1', 'EX', ttlSeconds, 'NX')) === 'OK';
  }
  if (memoryGet(`lock:${key}`)) return false;
  memorySet(`lock:${key}`, '1', ttlSeconds);
  return true;
}
