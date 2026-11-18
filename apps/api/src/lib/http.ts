import { upstreamFailed } from './errors';
import { logger } from './logger';

interface FetchOptions extends RequestInit {
  service: string;
  timeoutMs?: number;
  retries?: number;
}

const RETRYABLE = new Set([408, 425, 429, 500, 502, 503, 504]);
const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

/** Apel extern cu limită de timp și reîncercări; erorile devin un 502 clar pentru client. */
export async function fetchUpstream(url: string, options: FetchOptions): Promise<Response> {
  const { service, timeoutMs = 15_000, retries = 2, ...init } = options;
  let lastProblem = 'necunoscut';

  for (let attempt = 0; attempt <= retries; attempt += 1) {
    if (attempt > 0) await wait(400 * 2 ** (attempt - 1));
    try {
      const response = await fetch(url, { ...init, signal: AbortSignal.timeout(timeoutMs) });
      if (response.ok) return response;
      lastProblem = `HTTP ${response.status}`;
      if (!RETRYABLE.has(response.status)) break;
    } catch (error) {
      lastProblem = (error as Error).message;
    }
  }

  logger.warn(`${service}: apel eșuat`, lastProblem);
  throw upstreamFailed(service);
}

export async function fetchJson<T>(url: string, options: FetchOptions): Promise<T> {
  const response = await fetchUpstream(url, options);
  return (await response.json()) as T;
}

/** Rulează sarcinile cu un număr limitat de apeluri simultane. */
export async function mapLimit<T>(items: readonly T[], limit: number, task: (item: T) => Promise<void>) {
  let next = 0;
  const worker = async () => {
    while (next < items.length) {
      const item = items[next]!;
      next += 1;
      await task(item);
    }
  };
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker));
}
