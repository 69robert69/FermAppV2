import { vegetationHealth } from './vegetation';

/**
 * Satellite field monitoring from Sentinel-2 indices: NDVI (biomass), NDRE
 * (chlorophyll and nitrogen) and NDMI (canopy water). Pure analysis; the app
 * fetches the statistics.
 */

export type LngLat = [number, number];

export interface FieldZone {
  id: string;
  /** "north-west", "central", ... */
  name: string;
  ring: LngLat[];
  /** Share of the field area, 0-1. */
  share: number;
}

export interface IndexStats {
  mean: number;
  std: number;
  p2: number;
  p98: number;
}

export interface ZoneObservation {
  zoneId: string;
  ndvi: number | null;
  ndre: number | null;
  ndmi: number | null;
}

export interface FieldObservation {
  sceneId: string;
  /** Local acquisition day, YYYY-MM-DD. */
  date: string;
  /** Share of cloudy or snowy pixels over the field, 0-100. */
  cloudPct: number;
  ndvi: IndexStats;
  ndre: number | null;
  ndmi: number | null;
  zones: ZoneObservation[];
}

// ------------------------------------------------------------------ zones

const area = (ring: readonly LngLat[]) => {
  let sum = 0;
  for (let i = 0; i < ring.length; i++) {
    const [x1, y1] = ring[i]!;
    const [x2, y2] = ring[(i + 1) % ring.length]!;
    sum += x1 * y2 - x2 * y1;
  }
  return Math.abs(sum) / 2;
};

/** Sutherland–Hodgman: the part of a polygon inside an axis-aligned rectangle. */
function clipToRect(ring: readonly LngLat[], [minX, minY, maxX, maxY]: [number, number, number, number]): LngLat[] {
  const edges: [(p: LngLat) => boolean, (a: LngLat, b: LngLat) => LngLat][] = [
    [(p) => p[0] >= minX, (a, b) => [minX, a[1] + ((b[1] - a[1]) * (minX - a[0])) / (b[0] - a[0])]],
    [(p) => p[0] <= maxX, (a, b) => [maxX, a[1] + ((b[1] - a[1]) * (maxX - a[0])) / (b[0] - a[0])]],
    [(p) => p[1] >= minY, (a, b) => [a[0] + ((b[0] - a[0]) * (minY - a[1])) / (b[1] - a[1]), minY]],
    [(p) => p[1] <= maxY, (a, b) => [a[0] + ((b[0] - a[0]) * (maxY - a[1])) / (b[1] - a[1]), maxY]],
  ];
  let output = ring.slice();
  for (const [inside, cross] of edges) {
    const input = output;
    output = [];
    for (let i = 0; i < input.length; i++) {
      const current = input[i]!;
      const previous = input[(i + input.length - 1) % input.length]!;
      if (inside(current)) {
        if (!inside(previous)) output.push(cross(previous, current));
        output.push(current);
      } else if (inside(previous)) {
        output.push(cross(previous, current));
      }
    }
    if (!output.length) break;
  }
  return output;
}

const ROWS = ['north', '', 'south'];
const COLS = ['west', '', 'east'];

/** Splits a field into a 3×3 grid (2×2 for small fields), each cell clipped to the outline. */
export function fieldZones(outer: readonly LngLat[], areaHa: number): FieldZone[] {
  const ring = outer.length > 1 && outer[0]![0] === outer[outer.length - 1]![0] && outer[0]![1] === outer[outer.length - 1]![1] ? outer.slice(0, -1) : outer.slice();
  if (ring.length < 3 || areaHa < 1) return [];
  const n = areaHa >= 5 ? 3 : 2;
  const xs = ring.map((p) => p[0]);
  const ys = ring.map((p) => p[1]);
  const [minX, maxX, minY, maxY] = [Math.min(...xs), Math.max(...xs), Math.min(...ys), Math.max(...ys)];
  const total = area(ring);
  const zones: FieldZone[] = [];
  for (let row = 0; row < n; row++) {
    for (let col = 0; col < n; col++) {
      const rect: [number, number, number, number] = [
        minX + ((maxX - minX) * col) / n,
        maxY - ((maxY - minY) * (row + 1)) / n,
        minX + ((maxX - minX) * (col + 1)) / n,
        maxY - ((maxY - minY) * row) / n,
      ];
      const clipped = clipToRect(ring, rect);
      const share = clipped.length >= 3 ? area(clipped) / total : 0;
      if (share < 0.04) continue;
      const vertical = n === 3 ? ROWS[row]! : row === 0 ? 'north' : 'south';
      const horizontal = n === 3 ? COLS[col]! : col === 0 ? 'west' : 'east';
      const name = vertical && horizontal ? `${vertical}-${horizontal}` : vertical || horizontal || 'central';
      zones.push({ id: `z${row}${col}`, name, ring: [...clipped, clipped[0]!], share: Math.round(share * 1000) / 1000 });
    }
  }
  return zones;
}

// ------------------------------------------------------------------ health

export type HealthLevel = 'EXCELLENT' | 'MODERATE' | 'CRITICAL' | 'NO_CROP';

export const HEALTH_COLORS = { healthy: '#5FB35A', moderate: '#E2C23D', issues: '#E9883B', critical: '#E0584A' } as const;

/** Green healthy, yellow moderate stress, orange potential issues, red critical. */
export function ndviColor(ndvi: number | null | undefined): string {
  if (ndvi == null) return '#7A7F6E';
  if (ndvi >= 0.6) return HEALTH_COLORS.healthy;
  if (ndvi >= 0.4) return HEALTH_COLORS.moderate;
  if (ndvi >= 0.25) return HEALTH_COLORS.issues;
  return HEALTH_COLORS.critical;
}

export interface FieldHealth {
  score: number;
  level: HealthLevel;
  label: string;
  color: string;
}

/**
 * 0-100 from vigour (NDVI), chlorophyll (NDRE), canopy water (NDMI) and uniformity.
 * Without a crop in the field, low values are expected and no score is given.
 */
export function fieldHealth(observation: Pick<FieldObservation, 'ndvi' | 'ndre' | 'ndmi'>, cropGrowing: boolean): FieldHealth {
  if (!cropGrowing && observation.ndvi.mean < 0.35) return { score: 0, level: 'NO_CROP', label: 'No crop in the field', color: '#7A7F6E' };
  let score = vegetationHealth(observation.ndvi.mean, observation.ndre).healthIndex;
  if (observation.ndmi != null && observation.ndvi.mean > 0.25) {
    if (observation.ndmi < -0.2) score -= 20;
    else if (observation.ndmi < -0.1) score -= 10;
    else if (observation.ndmi > 0.2) score += 5;
  }
  if (observation.ndvi.std > 0.15) score -= 8;
  else if (observation.ndvi.std > 0.1) score -= 4;
  score = Math.max(0, Math.min(100, Math.round(score)));
  if (score >= 70) return { score, level: 'EXCELLENT', label: 'Excellent', color: HEALTH_COLORS.healthy };
  if (score >= 40) return { score, level: 'MODERATE', label: 'Moderate', color: HEALTH_COLORS.moderate };
  return { score, level: 'CRITICAL', label: 'Critical', color: HEALTH_COLORS.critical };
}

// ------------------------------------------------------------------ findings

export type FindingKind = 'LOW_VIGOR' | 'NUTRIENT' | 'WATER_STRESS' | 'UNEVEN_EMERGENCE' | 'DECLINE' | 'NO_CROP' | 'RIPENING';

export interface SatelliteFinding {
  key: string;
  kind: FindingKind;
  severity: 'INFO' | 'WARNING' | 'CRITICAL';
  zoneId: string | null;
  title: string;
  message: string;
  /** Why the satellite data points to this. */
  explanation: string;
  task: { type: string; title: string; reason: string; priority: 'LOW' | 'MEDIUM' | 'HIGH' } | null;
}

export interface FieldContext {
  name: string;
  /** Crop name in lower case, when one is growing. */
  crop: string | null;
  /** Season progress of the crop, 0-100. */
  stagePct: number | null;
  irrigated: boolean;
}

const r2 = (value: number) => Math.round(value * 100) / 100;
const zoneLabel = (zone: FieldZone | undefined) => (zone ? (zone.name === 'central' ? 'central part' : `${zone.name} section`) : 'field');
const capital = (text: string) => text.charAt(0).toUpperCase() + text.slice(1);

/** Explains what the latest image shows, zone by zone, and what to do about it. */
export function analyzeField(input: { field: FieldContext; current: FieldObservation; earlier: readonly FieldObservation[]; zones: readonly FieldZone[] }): SatelliteFinding[] {
  const { field, current, zones } = input;
  const findings: SatelliteFinding[] = [];
  const ndvi = current.ndvi.mean;
  const growing = field.crop != null && (field.stagePct ?? 50) < 100;
  if (!growing && ndvi < 0.35) {
    return [{ key: 'no-crop', kind: 'NO_CROP', severity: 'INFO', zoneId: null, title: 'No crop in the field', message: `${field.name}: low vegetation (NDVI ${r2(ndvi)}) is expected without a growing crop.`, explanation: 'Bare soil and stubble reflect little near-infrared light, so NDVI stays below about 0.3.', task: null }];
  }
  const ripening = (field.stagePct ?? 0) >= 85;
  const name = field.name;
  const add = (finding: Omit<SatelliteFinding, 'key'>) => findings.push({ ...finding, key: `${finding.kind}-${finding.zoneId ?? 'field'}` });

  for (const zone of current.zones) {
    const shape = zones.find((item) => item.id === zone.zoneId);
    if (zone.ndvi == null || !shape) continue;
    const gap = ndvi - zone.ndvi;
    if (gap >= 0.08 && !ripening) {
      add({
        kind: 'LOW_VIGOR',
        severity: gap >= 0.15 ? 'WARNING' : 'INFO',
        zoneId: zone.zoneId,
        title: `Reduced vigor – ${shape.name}`,
        message: `${capital(zoneLabel(shape))} of ${name} shows reduced vegetation vigor (NDVI ${r2(zone.ndvi)} vs ${r2(ndvi)} for the field).`,
        explanation: `Lower NDVI means less green biomass. A patch ${Math.round(gap * 100)} points below the rest of the field often points to waterlogging, compaction, pests or a sowing problem.`,
        task: { type: 'FIELD_INSPECTION', title: `Inspect ${shape.name} zone – ${name}`, reason: `NDVI ${r2(zone.ndvi)} vs ${r2(ndvi)} field average on ${current.date}.`, priority: gap >= 0.15 ? 'HIGH' : 'MEDIUM' },
      });
      continue;
    }
    if (zone.ndre != null && current.ndre != null && current.ndre - zone.ndre >= 0.06 && Math.abs(gap) < 0.05 && !ripening) {
      add({
        kind: 'NUTRIENT',
        severity: 'WARNING',
        zoneId: zone.zoneId,
        title: `Possible nutrient deficiency – ${shape.name}`,
        message: `Potential nutrient deficiency detected in the ${zoneLabel(shape)} of ${name}: chlorophyll (NDRE ${r2(zone.ndre)}) is low while biomass is normal.`,
        explanation: 'NDRE reacts to chlorophyll, and therefore nitrogen, before NDVI does. Normal NDVI with low NDRE is a typical early sign of nitrogen shortage.',
        task: { type: 'NITROGEN', title: `Apply nitrogen – ${shape.name} zone, ${name}`, reason: `NDRE ${r2(zone.ndre)} vs ${r2(current.ndre)} for the field; confirm with a leaf check before applying.`, priority: 'MEDIUM' },
      });
    }
    if (zone.ndmi != null && current.ndmi != null && current.ndmi - zone.ndmi >= 0.08 && zone.ndvi > 0.3) {
      add({
        kind: 'WATER_STRESS',
        severity: 'WARNING',
        zoneId: zone.zoneId,
        title: `Water stress – ${shape.name}`,
        message: `Possible drought stress in the ${zoneLabel(shape)} of ${name} (moisture index ${r2(zone.ndmi)} vs ${r2(current.ndmi)}).`,
        explanation: 'The moisture index (NDMI) measures water in the leaves. A drier patch can mean lighter soil, poor irrigation coverage or root problems.',
        task: field.irrigated
          ? { type: 'IRRIGATION_CHECK', title: `Check irrigation – ${shape.name} zone, ${name}`, reason: `Drier canopy than the rest of the field on ${current.date}.`, priority: 'HIGH' }
          : { type: 'FIELD_INSPECTION', title: `Field visit – ${shape.name} zone, ${name}`, reason: `Drier canopy than the rest of the field on ${current.date}.`, priority: 'MEDIUM' },
      });
    }
  }

  if (current.ndre != null && ndvi >= 0.5 && current.ndre < 0.25 && !ripening && !findings.some((item) => item.kind === 'NUTRIENT')) {
    add({ kind: 'NUTRIENT', severity: 'WARNING', zoneId: null, title: 'Possible nutrient deficiency', message: `Potential nutrient deficiency detected on ${name}: NDRE ${r2(current.ndre)} is low for an NDVI of ${r2(ndvi)}.`, explanation: 'Good biomass with low chlorophyll usually means the crop is running short of nitrogen.', task: { type: 'NITROGEN', title: `Apply nitrogen – ${name}`, reason: `NDRE ${r2(current.ndre)} on ${current.date}; confirm with a leaf check.`, priority: 'MEDIUM' } });
  }
  if (current.ndmi != null && current.ndmi < -0.05 && ndvi >= 0.3 && !findings.some((item) => item.kind === 'WATER_STRESS')) {
    add({ kind: 'WATER_STRESS', severity: current.ndmi < -0.15 ? 'CRITICAL' : 'WARNING', zoneId: null, title: 'Possible drought stress', message: `Possible drought stress on ${name}: the canopy moisture index is ${r2(current.ndmi)}.`, explanation: 'Negative NDMI over a green crop means the leaves hold little water: the crop is closing its stomata to save it.', task: field.irrigated ? { type: 'IRRIGATION_CHECK', title: `Check irrigation – ${name}`, reason: `NDMI ${r2(current.ndmi)} on ${current.date}.`, priority: 'HIGH' } : null });
  }
  if ((field.stagePct ?? 100) <= 30 && (current.ndvi.std >= 0.1 || current.ndvi.p98 - current.ndvi.p2 >= 0.4)) {
    add({ kind: 'UNEVEN_EMERGENCE', severity: 'WARNING', zoneId: null, title: 'Uneven crop emergence', message: `Uneven crop emergence on ${name}: vegetation varies strongly across the field (NDVI ${r2(current.ndvi.p2)}–${r2(current.ndvi.p98)}).`, explanation: 'Early in the season a uniform stand gives uniform NDVI. Large differences point to gaps from seedbed, sowing depth or soil crusting.', task: { type: 'EMERGENCE_CHECK', title: `Check emergence – ${name}`, reason: `NDVI spread ${r2(current.ndvi.p2)}–${r2(current.ndvi.p98)} on ${current.date}.`, priority: 'HIGH' } });
  }

  const before = [...input.earlier].filter((item) => item.date < current.date).sort((a, b) => b.date.localeCompare(a.date)).find((item) => daysApart(item.date, current.date) >= 5 && daysApart(item.date, current.date) <= 25);
  if (before) {
    const drop = before.ndvi.mean - ndvi;
    if (ripening && drop > 0.05) {
      add({ kind: 'RIPENING', severity: 'INFO', zoneId: null, title: 'Crop ripening', message: `NDVI on ${name} is falling as ${field.crop ?? 'the crop'} ripens (${r2(before.ndvi.mean)} → ${r2(ndvi)}).`, explanation: 'Leaves lose chlorophyll as the crop matures, so a falling NDVI near harvest is normal.', task: null });
    } else if (drop >= 0.08) {
      add({ kind: 'DECLINE', severity: drop >= 0.15 ? 'CRITICAL' : 'WARNING', zoneId: null, title: 'Vegetation decline', message: `NDVI on ${name} fell from ${r2(before.ndvi.mean)} to ${r2(ndvi)} since ${before.date}.`, explanation: 'A drop in green biomass during the growing season points to stress: drought, disease, pests, hail or herbicide injury.', task: { type: 'FIELD_INSPECTION', title: `Field visit – ${name}`, reason: `NDVI −${Math.round(drop * 100)} points in ${daysApart(before.date, current.date)} days.`, priority: drop >= 0.15 ? 'HIGH' : 'MEDIUM' } });
    }
  }
  const rank = { CRITICAL: 0, WARNING: 1, INFO: 2 } as const;
  return findings.sort((a, b) => rank[a.severity] - rank[b.severity]);
}

const daysApart = (from: string, to: string) => Math.round((Date.parse(`${to}T12:00:00Z`) - Date.parse(`${from}T12:00:00Z`)) / 86_400_000);

// ------------------------------------------------------------------ history

export interface HistoryPoint {
  date: string;
  ndvi: number;
}

export interface Comparison {
  date: string;
  ndvi: number;
  delta: number;
}

export interface HistoryComparison {
  latest: HistoryPoint | null;
  d7: Comparison | null;
  d30: Comparison | null;
  d90: Comparison | null;
  /** The same time last year, ±20 days. */
  yearAgo: Comparison | null;
}

/** NDVI now against 7, 30 and 90 days ago and the same time last year. */
export function compareHistory(points: readonly HistoryPoint[]): HistoryComparison {
  const sorted = [...points].sort((a, b) => b.date.localeCompare(a.date));
  const latest = sorted[0] ?? null;
  if (!latest) return { latest: null, d7: null, d30: null, d90: null, yearAgo: null };
  const closest = (days: number, tolerance: number): Comparison | null => {
    let best: HistoryPoint | null = null;
    let bestGap = Infinity;
    for (const point of sorted.slice(1)) {
      const gap = Math.abs(daysApart(point.date, latest.date) - days);
      if (gap <= tolerance && gap < bestGap) {
        best = point;
        bestGap = gap;
      }
    }
    return best ? { date: best.date, ndvi: best.ndvi, delta: r2(latest.ndvi - best.ndvi) } : null;
  };
  return { latest, d7: closest(7, 5), d30: closest(30, 10), d90: closest(90, 20), yearAgo: closest(365, 20) };
}
