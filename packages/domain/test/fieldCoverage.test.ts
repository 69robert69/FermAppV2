import { describe, expect, it } from 'vitest';
import { buildFieldPlan, fieldCoverageAt, fieldPositionAt, pointInRing, workTiming, type Position } from '../src';

/** A rectangle of about widthM x heightM around a point in Romania. */
function rectangle(widthM: number, heightM: number): Position[] {
  const lat = 45;
  const lng = 25;
  const dLat = heightM / 110_540;
  const dLng = widthM / (111_320 * Math.cos((lat * Math.PI) / 180));
  return [[lng, lat], [lng + dLng, lat], [lng + dLng, lat + dLat], [lng, lat + dLat], [lng, lat]];
}

/** An L-shaped (concave) parcel. */
const lShape: Position[] = [[25, 45], [25.006, 45], [25.006, 45.002], [25.003, 45.002], [25.003, 45.004], [25, 45.004], [25, 45]];

describe('field operation plan', () => {
  const field = rectangle(500, 400);
  const plan = buildFieldPlan(field, { workingWidthM: 6 })!;

  it('lays out one pass per working width, line after line', () => {
    expect(plan.passes).toHaveLength(67);
    expect(plan.stripM).toBeCloseTo(400 / 67, 1);
    // Passes alternate direction, as a machine drives back and forth.
    expect(Math.sign(plan.passes[0]!.toX - plan.passes[0]!.fromX)).toBe(-Math.sign(plan.passes[1]!.toX - plan.passes[1]!.fromX));
    expect(plan.workingM).toBeCloseTo(67 * 500, -2);
  });

  it('covers the parcel area in step with the progress', () => {
    for (const percent of [0, 25, 50, 75]) {
      expect(fieldCoverageAt(plan, percent).coveredPct).toBeCloseTo(percent, -0.5);
    }
    expect(fieldCoverageAt(plan, 100).coveredPct).toBeGreaterThan(99.5);
    expect(fieldCoverageAt(plan, 0).worked).toHaveLength(0);
  });

  it('keeps the machine inside the parcel while working and turns it at the headland', () => {
    const working = fieldPositionAt(plan, 37);
    if (!working.turning) expect(pointInRing([working.lng, working.lat], field)).toBe(true);
    const headings = new Set([10, 11, 12, 13].map((percent) => Math.round(fieldPositionAt(plan, percent).headingDeg / 90) % 4));
    expect(headings.size).toBeGreaterThan(1);
    expect(fieldPositionAt(plan, 100).finished).toBe(true);
  });

  it('clips the worked area to concave outlines', () => {
    const lPlan = buildFieldPlan(lShape, { workingWidthM: 12 })!;
    const half = fieldCoverageAt(lPlan, 50);
    expect(half.coveredPct).toBeGreaterThan(40);
    expect(half.coveredPct).toBeLessThan(60);
    expect(fieldCoverageAt(lPlan, 100).coveredPct).toBeGreaterThan(98);
  });

  it('draws a short trail behind the machine', () => {
    const trail = fieldCoverageAt(plan, 30, 80).trail;
    expect(trail.length).toBeGreaterThanOrEqual(2);
  });

  it('estimates the duration from area, speed and working width', () => {
    const timing = workTiming(20, 10, 6);
    expect(timing.capacityHaH).toBe(6);
    expect(timing.totalHours).toBeCloseTo(3.33, 2);
  });
});
