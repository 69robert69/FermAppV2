import {
  analyzeField,
  compareHistory,
  cropProgress,
  CROPS,
  fieldHealth,
  fieldZones,
  type FieldContext,
  type FieldHealth,
  type FieldObservation,
  type FieldZone,
  type HistoryComparison,
  type HistoryPoint,
  type LngLat,
  type SatelliteFinding,
  type ZoneObservation,
} from '@fermapp/domain';
import { usesFirebase } from '../../firebase/config';
import { readSnapshot, writeSnapshot } from '../../firebase/outbox';
import { outlineKey, remember, writeSatelliteSummary } from './cache';
import { bboxOf, polygonStats, searchScenes, type BBox, type PolygonStats, type Scene } from './planetary';

const HOUR = 3_600_000;
const DAY = 24 * HOUR;
/** A field image counts as clear when clouds cover at most this share of the field. */
const CLEAR_PCT = 20;
const HISTORY_CLEAR_PCT = 30;
const MAX_ATTEMPTS = 6;
const RECENT_DAYS = 130;
const HISTORY_TARGETS = [7, 14, 30, 45, 60, 90, 120];
const REANALYSE_MS = 12 * HOUR;

export interface MonitoredField {
  id: string;
  name: string;
  areaHa: number;
  /** Outer ring, [longitude, latitude]. */
  ring: LngLat[];
}

export interface FieldMonitoring {
  parcelId: string;
  analysedAt: string;
  outline: string;
  /** The field outline, for drawing. */
  ring: LngLat[];
  bbox: BBox;
  current: FieldObservation;
  zones: FieldZone[];
  history: HistoryPoint[];
  comparison: HistoryComparison;
  health: FieldHealth;
  findings: SatelliteFinding[];
  /** Recent clear images, newest first, for picking another date. */
  clearScenes: { id: string; date: string; cloudPct: number }[];
  context: FieldContext;
}

/** Acquisition time as the local calendar day. */
function localDay(iso: string) {
  const date = new Date(iso);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}
const isoDay = (ms: number) => new Date(ms).toISOString().slice(0, 10);

async function mapLimit<T, R>(items: readonly T[], limit: number, run: (item: T) => Promise<R>): Promise<R[]> {
  const results: R[] = new Array(items.length);
  let next = 0;
  await Promise.all(
    Array.from({ length: Math.min(limit, items.length) }, async () => {
      while (next < items.length) {
        const index = next++;
        results[index] = await run(items[index]!);
      }
    }),
  );
  return results;
}

const statsFor = (scene: Scene, ring: LngLat[], outline: string, withCloud = true) =>
  remember<PolygonStats>(`sat:stats:${scene.id}:${outline}:${withCloud ? 'c' : 'n'}`, null, () => polygonStats(scene.id, ring, withCloud));

/** The crop in the field and its growth stage, from the farm data on the phone. */
async function fieldContext(field: MonitoredField): Promise<FieldContext> {
  const base: FieldContext = { name: field.name, crop: null, stagePct: null, irrigated: false };
  if (!usesFirebase) return base;
  const [{ liveItems }] = await Promise.all([import('../../firebase/liveStore')]);
  const [seasons, parcels] = await Promise.all([liveItems<import('../../firebase/types').CropHistoryDoc>('cropHistory'), liveItems<import('../../firebase/types').ParcelDoc>('parcels')]);
  const parcel = parcels.find((item) => item.id === field.id);
  const year = new Date().getFullYear();
  const season =
    seasons.find((item) => item.parcelId === field.id && item.year === year) ??
    seasons.find((item) => item.parcelId === field.id && item.sowingDate && !item.harvestDate && item.year >= year - 1);
  const progress = season?.sowingDate ? cropProgress({ crop: season.crop, sowingDate: new Date(season.sowingDate), harvestDate: season.harvestDate ? new Date(season.harvestDate) : null }) : null;
  const growing = progress && (progress.status === 'GROWING' || progress.status === 'READY');
  return {
    name: field.name,
    crop: season && growing ? CROPS[season.crop].name.toLowerCase() : null,
    stagePct: growing ? progress!.percent : null,
    irrigated: (parcel?.irrigatedAreaHa ?? 0) > 0,
  };
}

const observation = (scene: Scene, stats: PolygonStats, zones: ZoneObservation[] = []): FieldObservation | null =>
  stats.ndvi ? { sceneId: scene.id, date: localDay(scene.datetime), cloudPct: stats.cloudPct, ndvi: stats.ndvi, ndre: stats.ndre, ndmi: stats.ndmi, zones } : null;

/**
 * Analyses a field: the latest clear image with zone statistics, a history for
 * comparisons, the health score and the findings. Everything is cached on the
 * phone; a scene over an outline is fetched only once.
 */
export async function monitorField(field: MonitoredField, options: { force?: boolean; sceneId?: string } = {}): Promise<FieldMonitoring> {
  const key = `sat:monitor:${field.id}:${outlineKey(field.ring)}`;
  const cached = await readSnapshot<FieldMonitoring>(key).catch(() => null);
  if (cached && !options.force && !options.sceneId && Date.now() - Date.parse(cached.analysedAt) < REANALYSE_MS) return cached;
  try {
    const result = await analyse(field, options);
    if (!options.sceneId) await writeSnapshot(key, result).catch(() => undefined);
    return result;
  } catch (error) {
    // Offline or a service hiccup: the last analysis is still useful.
    if (cached && !options.sceneId) return cached;
    throw error;
  }
}

async function analyse(field: MonitoredField, options: { sceneId?: string }): Promise<FieldMonitoring> {
  const ring = field.ring;
  const outline = outlineKey(ring);
  const bbox = bboxOf(ring);
  const now = Date.now();
  const today = isoDay(now);
  const scenes = await remember(`sat:scenes:${outline}:${today}`, 6 * HOUR, () => searchScenes(bbox, isoDay(now - RECENT_DAYS * DAY), today));
  if (!scenes.length) throw new Error('No Sentinel-2 image covers this field yet.');

  // The newest image where the field itself is clear of clouds.
  const clearScenes: FieldMonitoring['clearScenes'] = [];
  let current: FieldObservation | null = null;
  const candidates = options.sceneId ? scenes.filter((scene) => scene.id === options.sceneId) : scenes;
  for (const scene of candidates.slice(0, options.sceneId ? 1 : MAX_ATTEMPTS)) {
    const stats = await statsFor(scene, ring, outline);
    const item = observation(scene, stats);
    if (!item) continue;
    if (item.cloudPct <= CLEAR_PCT || options.sceneId) {
      clearScenes.push({ id: scene.id, date: item.date, cloudPct: item.cloudPct });
      current ??= item;
      if (!options.sceneId) break;
    }
  }
  if (!current) throw new Error('Clouds have covered this field on every recent image. Try again after the next clear day.');

  const zones = fieldZones(ring, field.areaHa);
  const scene = scenes.find((item) => item.id === current!.sceneId)!;
  current.zones = await mapLimit(zones, 3, async (zone) => {
    const stats = await statsFor(scene, zone.ring as LngLat[], outlineKey(zone.ring as LngLat[]), false).catch(() => null);
    return { zoneId: zone.id, ndvi: stats?.ndvi?.mean ?? null, ndre: stats?.ndre ?? null, ndmi: stats?.ndmi ?? null };
  });

  // History: the clearest image near each target age, plus the same time last year.
  const currentMs = Date.parse(`${current.date}T12:00:00Z`);
  const history: FieldObservation[] = [current];
  const used = new Set([current.sceneId]);
  for (const days of HISTORY_TARGETS) {
    const tolerance = days > 45 ? 12 : 6;
    const near = scenes
      .filter((item) => !used.has(item.id) && Math.abs((currentMs - Date.parse(item.datetime)) / DAY - days) <= tolerance)
      .sort((a, b) => Math.abs((currentMs - Date.parse(a.datetime)) / DAY - days) - Math.abs((currentMs - Date.parse(b.datetime)) / DAY - days));
    for (const candidate of near.slice(0, 3)) {
      const item = observation(candidate, await statsFor(candidate, ring, outline).catch(() => ({ ndvi: null, ndre: null, ndmi: null, cloudPct: 100 })));
      used.add(candidate.id);
      if (item && item.cloudPct <= HISTORY_CLEAR_PCT) {
        history.push(item);
        clearScenes.push({ id: candidate.id, date: item.date, cloudPct: item.cloudPct });
        break;
      }
    }
  }
  const yearAgoMs = currentMs - 365 * DAY;
  const lastYear = await remember(`sat:scenes:${outline}:${isoDay(yearAgoMs)}:y`, null, () => searchScenes(bbox, isoDay(yearAgoMs - 20 * DAY), isoDay(yearAgoMs + 20 * DAY))).catch(() => [] as Scene[]);
  for (const candidate of [...lastYear].sort((a, b) => Math.abs(Date.parse(a.datetime) - yearAgoMs) - Math.abs(Date.parse(b.datetime) - yearAgoMs)).slice(0, 3)) {
    const item = observation(candidate, await statsFor(candidate, ring, outline).catch(() => ({ ndvi: null, ndre: null, ndmi: null, cloudPct: 100 })));
    if (item && item.cloudPct <= HISTORY_CLEAR_PCT) {
      history.push(item);
      break;
    }
  }

  const context = await fieldContext(field);
  const points = history.map((item) => ({ date: item.date, ndvi: item.ndvi.mean })).sort((a, b) => a.date.localeCompare(b.date));
  const result: FieldMonitoring = {
    parcelId: field.id,
    analysedAt: new Date().toISOString(),
    outline,
    ring,
    bbox,
    current,
    zones,
    history: points,
    comparison: compareHistory(points),
    health: fieldHealth(current, context.crop != null),
    findings: analyzeField({ field: context, current, earlier: history.slice(1), zones }),
    clearScenes: clearScenes.sort((a, b) => b.date.localeCompare(a.date)),
    context,
  };
  if (!options.sceneId) {
    await writeSatelliteSummary(field.id, {
      date: current.date,
      ndvi: current.ndvi.mean,
      ndre: current.ndre,
      ndmi: current.ndmi,
      healthScore: result.health.level === 'NO_CROP' ? null : result.health.score,
      healthLabel: result.health.label,
      change7d: result.comparison.d7?.delta ?? null,
      change30d: result.comparison.d30?.delta ?? null,
      findings: result.findings.map(({ severity, title, message, explanation, task }) => ({ severity, title, message, explanation, task })),
    });
  }
  return result;
}

/** The outer ring of a parcel's GeoJSON polygon. */
export const ringOf = (geometry: { coordinates: number[][][] }): LngLat[] => (geometry.coordinates[0] ?? []).map(([lng, lat]) => [lng!, lat!] as LngLat);
