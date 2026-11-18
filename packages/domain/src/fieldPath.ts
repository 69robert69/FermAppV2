import { closeRing, type Position } from './geo';

const METERS_PER_DEG_LAT = 110_540;
const METERS_PER_DEG_LNG = 111_320;
const MIN_PASSES = 5;
const MAX_PASSES = 41;

type Point = readonly [x: number, y: number];

/** Proiecție plană locală, suficient de exactă la dimensiunea unei parcele. */
function projection(ring: Position[]) {
  const origin = ring[0]!;
  const cosLat = Math.cos((origin[1] * Math.PI) / 180);
  return {
    toPlane: ([lng, lat]: Position): Point => [
      (lng - origin[0]) * METERS_PER_DEG_LNG * cosLat,
      (lat - origin[1]) * METERS_PER_DEG_LAT,
    ],
    toGeo: ([x, y]: Point): Position => [
      origin[0] + x / (METERS_PER_DEG_LNG * cosLat),
      origin[1] + y / METERS_PER_DEG_LAT,
    ],
  };
}

const rotate = ([x, y]: Point, angle: number): Point => [
  x * Math.cos(angle) - y * Math.sin(angle),
  x * Math.sin(angle) + y * Math.cos(angle),
];

/** Utilajele lucrează de obicei paralel cu latura cea mai lungă a parcelei. */
function workAngle(points: Point[]): number {
  let longest = 0;
  let angle = 0;
  for (let i = 1; i < points.length; i += 1) {
    const dx = points[i]![0] - points[i - 1]![0];
    const dy = points[i]![1] - points[i - 1]![1];
    const length = Math.hypot(dx, dy);
    if (length > longest) {
      longest = length;
      angle = Math.atan2(dy, dx);
    }
  }
  return angle;
}

/** Cel mai lung segment al liniei orizontale y aflat în interiorul poligonului. */
function passSegment(points: Point[], y: number): [number, number] | null {
  const crossings: number[] = [];
  for (let i = 1; i < points.length; i += 1) {
    const [x0, y0] = points[i - 1]!;
    const [x1, y1] = points[i]!;
    if (y0 <= y !== y1 <= y) crossings.push(x0 + ((y - y0) / (y1 - y0)) * (x1 - x0));
  }
  crossings.sort((a, b) => a - b);
  let best: [number, number] | null = null;
  for (let i = 0; i + 1 < crossings.length; i += 2) {
    const segment: [number, number] = [crossings[i]!, crossings[i + 1]!];
    if (!best || segment[1] - segment[0] > best[1] - best[0]) best = segment;
  }
  return best;
}

export interface FieldPathOptions {
  /** Lățimea de lucru, din care rezultă numărul de treceri. */
  workingWidthM?: number;
  /** Număr fix de treceri. O singură trecere traversează parcela prin mijloc. */
  passes?: number;
}

/**
 * Traseul dus-întors al unui utilaj pe parcelă.
 * Numărul de treceri este impar, astfel încât la 50% utilajul să fie în centrul parcelei.
 */
export function buildFieldPath(outerRing: Position[], options: FieldPathOptions = {}): Position[] {
  const ring = closeRing(outerRing);
  if (ring.length < 4) return [];
  const { toPlane, toGeo } = projection(ring);
  const plane = ring.map(toPlane);
  const angle = workAngle(plane);
  const aligned = plane.map((point) => rotate(point, -angle));

  const ys = aligned.map(([, y]) => y);
  const minY = Math.min(...ys);
  const acrossM = Math.max(...ys) - minY;
  if (acrossM <= 0) return [];

  const wanted = options.passes ?? (options.workingWidthM ? Math.round(acrossM / options.workingWidthM) : MIN_PASSES);
  const bounded = options.passes ? Math.max(1, wanted) : Math.min(MAX_PASSES, Math.max(MIN_PASSES, wanted));
  const passes = bounded % 2 === 0 ? bounded + 1 : bounded;

  const path: Position[] = [];
  for (let pass = 0; pass < passes; pass += 1) {
    const y = minY + ((pass + 0.5) / passes) * acrossM;
    const segment = passSegment(aligned, y);
    if (!segment) continue;
    const [from, to] = pass % 2 === 0 ? segment : ([segment[1], segment[0]] as const);
    path.push(toGeo(rotate([from, y], angle)), toGeo(rotate([to, y], angle)));
  }
  return path;
}

export interface FieldPosition {
  lng: number;
  lat: number;
  /** Direcția de deplasare, în grade față de nord. */
  headingDeg: number;
  /** Porțiunea de traseu deja parcursă. */
  worked: Position[];
}

/** Poziția pe traseu corespunzătoare procentului de progres. */
export function positionOnPath(path: Position[], percent: number): FieldPosition | null {
  const start = path[0];
  if (!start) return null;
  const { toPlane } = projection(path);
  const plane = path.map(toPlane);
  const lengths = plane.slice(1).map((point, i) => Math.hypot(point[0] - plane[i]![0], point[1] - plane[i]![1]));
  const total = lengths.reduce((sum, length) => sum + length, 0);
  let remaining = (Math.max(0, Math.min(100, percent)) / 100) * total;

  const worked: Position[] = [start];
  for (let i = 0; i < lengths.length; i += 1) {
    const length = lengths[i]!;
    const from = path[i]!;
    const to = path[i + 1]!;
    const dx = plane[i + 1]![0] - plane[i]![0];
    const dy = plane[i + 1]![1] - plane[i]![1];
    const headingDeg = ((Math.atan2(dx, dy) * 180) / Math.PI + 360) % 360;
    if (remaining <= length || i === lengths.length - 1) {
      const ratio = length > 0 ? Math.min(1, remaining / length) : 0;
      const lng = from[0] + (to[0] - from[0]) * ratio;
      const lat = from[1] + (to[1] - from[1]) * ratio;
      worked.push([lng, lat]);
      return { lng, lat, headingDeg, worked };
    }
    remaining -= length;
    worked.push(to);
  }
  return { lng: start[0], lat: start[1], headingDeg: 0, worked };
}

/** Verifică dacă un punct se află în interiorul conturului. */
export function pointInRing(point: Position, ring: Position[]): boolean {
  let inside = false;
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i, i += 1) {
    const [xi, yi] = ring[i]!;
    const [xj, yj] = ring[j]!;
    if (yi > point[1] !== yj > point[1] && point[0] < ((xj - xi) * (point[1] - yi)) / (yj - yi) + xi) {
      inside = !inside;
    }
  }
  return inside;
}
