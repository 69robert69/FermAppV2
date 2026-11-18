/** Poziție GeoJSON: [longitudine, latitudine]. */
export type Position = [lng: number, lat: number];

export interface PolygonGeometry {
  type: 'Polygon';
  coordinates: Position[][];
}

export interface GeoMetrics {
  areaHa: number;
  perimeterM: number;
  center: { lat: number; lng: number };
}

const EARTH_RADIUS_M = 6_371_008.8;
const toRad = (deg: number) => (deg * Math.PI) / 180;
const round = (value: number, digits: number) => {
  const factor = 10 ** digits;
  return Math.round(value * factor) / factor;
};

const samePoint = (a: Position, b: Position) => a[0] === b[0] && a[1] === b[1];

/** Închide inelul dacă ultimul punct diferă de primul. */
export function closeRing(ring: Position[]): Position[] {
  const first = ring[0];
  const last = ring[ring.length - 1];
  if (!first || !last) return [];
  return samePoint(first, last) ? ring : [...ring, first];
}

export function polygonFromPoints(points: Position[]): PolygonGeometry {
  return { type: 'Polygon', coordinates: [closeRing(points)] };
}

export function haversineM(a: Position, b: Position): number {
  const dLat = toRad(b[1] - a[1]);
  const dLng = toRad(b[0] - a[0]);
  const h =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRad(a[1])) * Math.cos(toRad(b[1])) * Math.sin(dLng / 2) ** 2;
  return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(h)));
}

/** Aria pe sferă a unui inel, în m² (metoda Chamberlain-Duquette). */
function ringAreaM2(ring: Position[]): number {
  const n = ring.length - 1;
  if (n < 3) return 0;
  let total = 0;
  for (let i = 0; i < n; i += 1) {
    const prev = ring[(i + n - 1) % n]!;
    const current = ring[i]!;
    const next = ring[(i + 1) % n]!;
    total += toRad(next[0] - prev[0]) * Math.sin(toRad(current[1]));
  }
  return Math.abs((total * EARTH_RADIUS_M * EARTH_RADIUS_M) / 2);
}

function ringLengthM(ring: Position[]): number {
  let total = 0;
  for (let i = 1; i < ring.length; i += 1) total += haversineM(ring[i - 1]!, ring[i]!);
  return total;
}

function ringCentroid(ring: Position[]): { lat: number; lng: number } {
  const origin = ring[0]!;
  let doubledArea = 0;
  let cx = 0;
  let cy = 0;
  for (let i = 0; i < ring.length - 1; i += 1) {
    const x0 = ring[i]![0] - origin[0];
    const y0 = ring[i]![1] - origin[1];
    const x1 = ring[i + 1]![0] - origin[0];
    const y1 = ring[i + 1]![1] - origin[1];
    const cross = x0 * y1 - x1 * y0;
    doubledArea += cross;
    cx += (x0 + x1) * cross;
    cy += (y0 + y1) * cross;
  }
  if (Math.abs(doubledArea) < 1e-14) return { lat: origin[1], lng: origin[0] };
  return {
    lng: origin[0] + cx / (3 * doubledArea),
    lat: origin[1] + cy / (3 * doubledArea),
  };
}

/** Hectare, perimetru exterior și centrul GPS. Golurile se scad din suprafață. */
export function computeGeoMetrics(polygon: PolygonGeometry): GeoMetrics {
  const [outer, ...holes] = polygon.coordinates.map(closeRing);
  if (!outer || outer.length < 4) throw new Error('Poligonul are nevoie de minimum 3 puncte.');
  const areaM2 = holes.reduce((area, hole) => area - ringAreaM2(hole), ringAreaM2(outer));
  const center = ringCentroid(outer);
  return {
    areaHa: round(Math.max(0, areaM2) / 10_000, 4),
    perimeterM: round(ringLengthM(outer), 1),
    center: { lat: round(center.lat, 7), lng: round(center.lng, 7) },
  };
}

function orientation(a: Position, b: Position, c: Position): number {
  const value = (b[1] - a[1]) * (c[0] - b[0]) - (b[0] - a[0]) * (c[1] - b[1]);
  if (Math.abs(value) < 1e-16) return 0;
  return value > 0 ? 1 : 2;
}

function onSegment(a: Position, b: Position, c: Position): boolean {
  return (
    b[0] <= Math.max(a[0], c[0]) &&
    b[0] >= Math.min(a[0], c[0]) &&
    b[1] <= Math.max(a[1], c[1]) &&
    b[1] >= Math.min(a[1], c[1])
  );
}

function segmentsIntersect(p1: Position, q1: Position, p2: Position, q2: Position): boolean {
  const o1 = orientation(p1, q1, p2);
  const o2 = orientation(p1, q1, q2);
  const o3 = orientation(p2, q2, p1);
  const o4 = orientation(p2, q2, q1);
  if (o1 !== o2 && o3 !== o4) return true;
  return (
    (o1 === 0 && onSegment(p1, p2, q1)) ||
    (o2 === 0 && onSegment(p1, q2, q1)) ||
    (o3 === 0 && onSegment(p2, p1, q2)) ||
    (o4 === 0 && onSegment(p2, q1, q2))
  );
}

export function isSelfIntersecting(ring: Position[]): boolean {
  const closed = closeRing(ring);
  const segments = closed.length - 1;
  for (let i = 0; i < segments; i += 1) {
    for (let j = i + 1; j < segments; j += 1) {
      const adjacent = j === i + 1 || (i === 0 && j === segments - 1);
      if (adjacent) continue;
      if (segmentsIntersect(closed[i]!, closed[i + 1]!, closed[j]!, closed[j + 1]!)) return true;
    }
  }
  return false;
}

/** Returnează motivul pentru care poligonul e invalid sau null dacă e valid. */
export function validatePolygon(polygon: PolygonGeometry): string | null {
  const outer = polygon.coordinates[0];
  if (!outer) return 'Poligonul nu are contur.';
  const closed = closeRing(outer);
  if (closed.length < 4) return 'Conturul are nevoie de minimum 3 puncte distincte.';
  for (const ring of polygon.coordinates) {
    for (const [lng, lat] of ring) {
      if (!Number.isFinite(lng) || !Number.isFinite(lat) || Math.abs(lng) > 180 || Math.abs(lat) > 90) {
        return 'Coordonatele trebuie să fie longitudine și latitudine valide.';
      }
    }
  }
  if (isSelfIntersecting(closed)) return 'Conturul parcelei se intersectează cu el însuși.';
  if (computeGeoMetrics(polygon).areaHa <= 0) return 'Suprafața parcelei trebuie să fie mai mare ca zero.';
  return null;
}
