type Level = 'info' | 'warn' | 'error';

function write(level: Level, message: string, meta?: unknown) {
  if (process.env.NODE_ENV === 'test' && level !== 'error') return;
  const detail =
    meta instanceof Error ? { error: meta.message, stack: meta.stack } : meta === undefined ? {} : { meta };
  const line = JSON.stringify({ level, time: new Date().toISOString(), message, ...detail });
  (level === 'error' ? console.error : console.log)(line);
}

export const logger = {
  info: (message: string, meta?: unknown) => write('info', message, meta),
  warn: (message: string, meta?: unknown) => write('warn', message, meta),
  error: (message: string, meta?: unknown) => write('error', message, meta),
};
