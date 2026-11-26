import type { CopilotSatellite } from '@fermapp/domain';
import { readSnapshot, writeSnapshot } from '../../firebase/outbox';

/** The latest analysis of each field, read by the copilot without any network request. */
export const summaryKey = (parcelId: string) => `sat:summary:${parcelId}`;

export interface StoredSummary {
  analysedAt: string;
  summary: CopilotSatellite;
}

export const readSatelliteSummary = (parcelId: string) => readSnapshot<StoredSummary>(summaryKey(parcelId)).catch(() => null);
export const writeSatelliteSummary = (parcelId: string, summary: CopilotSatellite) =>
  writeSnapshot(summaryKey(parcelId), { analysedAt: new Date().toISOString(), summary } satisfies StoredSummary);

/** Results that never change (one scene over one outline) are kept for good; lists expire. */
export async function remember<T>(key: string, maxAgeMs: number | null, load: () => Promise<T>): Promise<T> {
  const saved = await readSnapshot<{ savedAt: number; value: T }>(key).catch(() => null);
  if (saved && (maxAgeMs == null || Date.now() - saved.savedAt < maxAgeMs)) return saved.value;
  try {
    const value = await load();
    await writeSnapshot(key, { savedAt: Date.now(), value }).catch(() => undefined);
    return value;
  } catch (error) {
    if (saved) return saved.value;
    throw error;
  }
}

/** A short, stable fingerprint of a field outline, so a redrawn field is analysed again. */
export function outlineKey(ring: readonly [number, number][]): string {
  let hash = 5381;
  for (const [lng, lat] of ring) {
    const text = `${lng.toFixed(5)},${lat.toFixed(5)};`;
    for (let i = 0; i < text.length; i++) hash = ((hash << 5) + hash + text.charCodeAt(i)) | 0;
  }
  return (hash >>> 0).toString(36);
}
