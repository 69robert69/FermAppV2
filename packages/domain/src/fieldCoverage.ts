import { closeRing, type Position } from './geo';

/**
 * A machine's working plan on a parcel: straight passes, line after line,
 * joined by U-turns on the headland, and the area covered at any progress.
 *
 * Everything is computed in a local plane rotated so that the passes run
 * along x; strips are stacked along y, one working width each.
 */

const METERS_PER_DEG_LAT = 110_540;
const METERS_PER_DEG_LNG = 111_320;
/** Enough passes to look real on large parcels while keeping the geometry light on a phone. */
const DEFAULT_MAX_PASSES = 120;
const TURN_SEGMENTS = 8;

type Point = [x: number, y: number];

interface Frame {
  originLng: number;
  originLat: number;
  cosLat: number;
  angle: number;
}

const toAligned = (frame: Frame, [lng, lat]: Position): Point => {
  const x = (lng - frame.originLng) * METERS_PER_DEG_LNG * frame.cosLat;
  const y = (lat - frame.originLat) * METERS_PER_DEG_LAT;
  const c = Math.cos(-frame.angle);
  const s = Math.sin(-frame.angle);
  return [x * c - y * s, x * s + y * c];
};

const toGeo = (frame: Frame, [ax, ay]: Point): Position => {
  const c = Math.cos(frame.angle);
  const s = Math.sin(frame.angle);
  const x = ax * c - ay * s;
  const y = ax * s + ay * c;
  return [frame.originLng + x / (METERS_PER_DEG_LNG * frame.cosLat), frame.originLat + y / METERS_PER_DEG_LAT];
};

/** Passes run parallel to the longest side of the parcel, as most operators drive. */
function longestSideAngle(ring: Position[], frame: Omit<Frame, 'angle'>): number {
  let longest = 0;
  let angle = 0;
  const plane = ring.map(([lng, lat]) => [(lng - frame.originLng) * METERS_PER_DEG_LNG * frame.cosLat, (lat - frame.originLat) * METERS_PER_DEG_LAT] as Point);
  for (let i = 1; i < plane.length; i += 1) {
    const dx = plane[i]![0] - plane[i - 1]![0];
    const dy = plane[i]![1] - plane[i - 1]![1];
    const length = Math.hypot(dx, dy);
    if (length > longest) {
      longest = length;
      angle = Math.atan2(dy, dx);
    }
  }
  return angle;
}

/** Longest stretch of the line y = value inside the polygon. */
function passSpan(points: Point[], y: number): [number, number] | null {
  const crossings: number[] = [];
  for (let i = 1; i < points.length; i += 1) {
    const [x0, y0] = points[i - 1]!;
    const [x1, y1] = points[i]!;
    if (y0 <= y !== y1 <= y) crossings.push(x0 + ((y - y0) / (y1 - y0)) * (x1 - x0));
  }
  crossings.sort((a, b) => a - b);
  let best: [number, number] | null = null;
  for (let i = 0; i + 1 < crossings.length; i += 2) {
    const span: [number, number] = [crossings[i]!, crossings[i + 1]!];
    if (!best || span[1] - span[0] > best[1] - best[0]) best = span;
  }
  return best;
}

/** Sutherland-Hodgman clip of a polygon against one axis-aligned half-plane. */
function clip(points: Point[], axis: 0 | 1, value: number, keepBelow: boolean): Point[] {
  const inside = (point: Point) => (keepBelow ? point[axis] <= value : point[axis] >= value);
  const out: Point[] = [];
  for (let i = 0; i < points.length; i += 1) {
    const current = points[i]!;
    const previous = points[(i + points.length - 1) % points.length]!;
    const currentIn = inside(current);
    const previousIn = inside(previous);
    if (currentIn !== previousIn) {
      const t = (value - previous[axis]) / (current[axis] - previous[axis]);
      out.push([previous[0] + (current[0] - previous[0]) * t, previous[1] + (current[1] - previous[1]) * t]);
    }
    if (currentIn) out.push(current);
  }
  return out;
}

export function planarArea(points: readonly Point[]): number {
  let sum = 0;
  for (let i = 0; i < points.length; i += 1) {
    const [x0, y0] = points[i]!;
    const [x1, y1] = points[(i + 1) % points.length]!;
    sum += x0 * y1 - x1 * y0;
  }
  return Math.abs(sum) / 2;
}

export interface FieldPass {
  /** Aligned-plane y of the pass centre line. */
  y: number;
  fromX: number;
  toX: number;
  /** Route distance where the pass starts and ends, in metres. */
  startM: number;
  endM: number;
}

export interface FieldPlan {
  frame: Frame;
  /** The parcel outline in the aligned plane, without the closing point. */
  outline: Point[];
  minY: number;
  stripM: number;
  passes: FieldPass[];
  /** The full route, passes and turns, in the aligned plane. */
  route: Point[];
  /** Distance from the start of the route to each route point. */
  distances: number[];
  totalM: number;
  /** Driven distance spent working (without turns). */
  workingM: number;
  areaM2: number;
}

export interface FieldPlanOptions {
  workingWidthM: number;
  maxPasses?: number;
}

/** Plans the passes and headland turns of a machine with the given working width. */
export function buildFieldPlan(outerRing: Position[], { workingWidthM, maxPasses = DEFAULT_MAX_PASSES }: FieldPlanOptions): FieldPlan | null {
  const ring = closeRing(outerRing);
  if (ring.length < 4 || !(workingWidthM > 0)) return null;
  const base = { originLng: ring[0]![0], originLat: ring[0]![1], cosLat: Math.cos((ring[0]![1] * Math.PI) / 180) };
  const frame: Frame = { ...base, angle: longestSideAngle(ring, base) };
  const closed = ring.map((point) => toAligned(frame, point));
  const outline = closed.slice(0, -1);
  const ys = outline.map(([, y]) => y);
  const minY = Math.min(...ys);
  const acrossM = Math.max(...ys) - minY;
  if (acrossM <= 0) return null;

  const count = Math.min(maxPasses, Math.max(1, Math.round(acrossM / workingWidthM)));
  const stripM = acrossM / count;
  const passes: FieldPass[] = [];
  const route: Point[] = [];
  const distances: number[] = [];
  let distance = 0;
  const push = (point: Point) => {
    const last = route[route.length - 1];
    if (last) distance += Math.hypot(point[0] - last[0], point[1] - last[1]);
    route.push(point);
    distances.push(distance);
  };

  for (let index = 0; index < count; index += 1) {
    const y = minY + (index + 0.5) * stripM;
    const span = passSpan(closed, y);
    if (!span) continue;
    const forward = passes.length % 2 === 0;
    const [fromX, toX] = forward ? span : [span[1], span[0]];

    const previous = passes[passes.length - 1];
    if (previous) {
      // U-turn on the headland: run out past the longer of the two pass ends, then a half circle.
      const outward = previous.toX > previous.fromX ? 1 : -1;
      const turnX = outward > 0 ? Math.max(previous.toX, fromX) : Math.min(previous.toX, fromX);
      const radius = (y - previous.y) / 2;
      if (Math.abs(turnX - previous.toX) > 0.01) push([turnX, previous.y]);
      for (let step = 1; step < TURN_SEGMENTS; step += 1) {
        const theta = -Math.PI / 2 + (Math.PI * step) / TURN_SEGMENTS;
        push([turnX + outward * radius * Math.cos(theta), previous.y + radius + radius * Math.sin(theta)]);
      }
      if (Math.abs(turnX - fromX) > 0.01) push([turnX, y]);
    }
    push([fromX, y]);
    const startM = distance;
    push([toX, y]);
    passes.push({ y, fromX, toX, startM, endM: distance });
  }
  if (passes.length === 0) return null;
  const workingM = passes.reduce((sum, pass) => sum + Math.abs(pass.toX - pass.fromX), 0);
  return { frame, outline, minY, stripM, passes, route, distances, totalM: distance, workingM, areaM2: planarArea(outline) };
}

export interface FieldProgress {
  lng: number;
  lat: number;
  /** Heading in degrees clockwise from north, for rotating the machine icon. */
  headingDeg: number;
  /** Zero-based pass being worked, or the last pass when finished. */
  passIndex: number;
  passCount: number;
  turning: boolean;
  finished: boolean;
}

/** Index of the route segment that contains the given distance. */
function segmentAt(distances: number[], meters: number): number {
  let lo = 0;
  let hi = distances.length - 1;
  while (hi - lo > 1) {
    const mid = (lo + hi) >> 1;
    if (distances[mid]! <= meters) lo = mid;
    else hi = mid;
  }
  return lo;
}

const clampPercent = (percent: number) => Math.max(0, Math.min(100, percent));

/**
 * Where the machine is at a progress percentage. Cheap enough to run on every
 * animation frame: one binary search and an interpolation.
 */
export function fieldPositionAt(plan: FieldPlan, percent: number): FieldProgress {
  const meters = (clampPercent(percent) / 100) * plan.totalM;
  const index = Math.min(segmentAt(plan.distances, meters), plan.route.length - 2);
  const a = plan.route[index]!;
  const b = plan.route[index + 1] ?? a;
  const length = plan.distances[index + 1]! - plan.distances[index]!;
  const t = length > 0 ? Math.min(1, (meters - plan.distances[index]!) / length) : 0;
  const point: Point = [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t];
  const [lng, lat] = toGeo(plan.frame, point);

  // Heading from the segment direction, turned back from the aligned plane to the map.
  const [ga, gb] = [toGeo(plan.frame, a), toGeo(plan.frame, b)];
  const dx = (gb[0] - ga[0]) * plan.frame.cosLat;
  const dy = gb[1] - ga[1];
  const headingDeg = dx === 0 && dy === 0 ? 0 : ((Math.atan2(dx, dy) * 180) / Math.PI + 360) % 360;

  let passIndex = plan.passes.findIndex((pass) => meters < pass.endM);
  if (passIndex < 0) passIndex = plan.passes.length - 1;
  const pass = plan.passes[passIndex]!;
  return {
    lng,
    lat,
    headingDeg,
    passIndex,
    passCount: plan.passes.length,
    turning: meters < pass.startM,
    finished: clampPercent(percent) >= 100,
  };
}

export interface FieldCoverage {
  /** Worked area as map polygons (outer rings). Usually one or two pieces. */
  worked: Position[][];
  /** Share of the parcel area already worked, 0-100. */
  coveredPct: number;
  /** Centre lines of the finished passes plus the current one, for rows, furrows or stubble. */
  rows: Position[][];
  /** The last metres driven, for a discreet GPS trail. */
  trail: Position[];
}

/** The last metres driven, cheap enough for every animation frame. */
export function fieldTrailAt(plan: FieldPlan, percent: number, trailM = 80): Position[] {
  const meters = (clampPercent(percent) / 100) * plan.totalM;
  if (meters <= 0) return [];
  const at = (m: number, i: number): Point => {
    const a = plan.route[i]!;
    const b = plan.route[i + 1] ?? a;
    const length = plan.distances[i + 1]! - plan.distances[i]!;
    const t = length > 0 ? Math.min(1, Math.max(0, (m - plan.distances[i]!) / length)) : 0;
    return [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t];
  };
  const from = Math.max(0, meters - trailM);
  const start = segmentAt(plan.distances, from);
  const end = Math.min(segmentAt(plan.distances, meters), plan.route.length - 2);
  const trail: Point[] = [at(from, start)];
  for (let i = start + 1; i <= end; i += 1) trail.push(plan.route[i]!);
  trail.push(at(meters, end));
  return trail.map((point) => toGeo(plan.frame, point));
}

/** The area worked so far, clipped exactly to the parcel outline. */
export function fieldCoverageAt(plan: FieldPlan, percent: number, trailM = 80): FieldCoverage {
  const meters = (clampPercent(percent) / 100) * plan.totalM;
  const toMap = (points: Point[]) => points.map((point) => toGeo(plan.frame, point));
  const worked: Point[][] = [];
  const rows: Point[][] = [];

  let done = 0;
  while (done < plan.passes.length && plan.passes[done]!.endM <= meters) done += 1;
  if (done > 0) {
    // Finished passes are adjacent strips, so together they are one band of the parcel.
    const top = plan.passes[done - 1]!.y + plan.stripM / 2;
    const band = clip(plan.outline, 1, top, true);
    if (band.length >= 3) worked.push(band);
    for (let i = 0; i < done; i += 1) {
      const pass = plan.passes[i]!;
      rows.push([[pass.fromX, pass.y], [pass.toX, pass.y]]);
    }
  }
  const current = plan.passes[done];
  if (current && meters > current.startM) {
    const progress = Math.min(1, (meters - current.startM) / Math.max(current.endM - current.startM, 1e-9));
    const x = current.fromX + (current.toX - current.fromX) * progress;
    let strip = clip(plan.outline, 1, current.y - plan.stripM / 2, false);
    strip = clip(strip, 1, current.y + plan.stripM / 2, true);
    strip = current.toX > current.fromX ? clip(strip, 0, x, true) : clip(strip, 0, x, false);
    if (strip.length >= 3) worked.push(strip);
    rows.push([[current.fromX, current.y], [x, current.y]]);
  }

  const coveredM2 = worked.reduce((sum, piece) => sum + planarArea(piece), 0);
  return {
    worked: worked.map(toMap),
    coveredPct: plan.areaM2 > 0 ? Math.min(100, (coveredM2 / plan.areaM2) * 100) : 0,
    rows: rows.map(toMap),
    trail: fieldTrailAt(plan, percent, trailM),
  };
}

export interface WorkTiming {
  capacityHaH: number;
  totalHours: number;
}

/** Field capacity and duration from area, speed and working width, as on the Work screen. */
export function workTiming(areaHa: number, speedKmh: number, workingWidthM: number, efficiencyPct = 100): WorkTiming {
  const capacityHaH = (workingWidthM * speedKmh * (efficiencyPct / 100)) / 10;
  return { capacityHaH, totalHours: capacityHaH > 0 ? areaHa / capacityHaH : 0 };
}
