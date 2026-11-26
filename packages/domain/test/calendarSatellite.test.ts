import { describe, expect, it } from 'vitest';
import {
  analyzeField,
  bestDayFor,
  calendarDashboard,
  categoryOf,
  compareHistory,
  detectConflicts,
  fieldHealth,
  fieldTimeline,
  fieldZones,
  monthGrid,
  ndviColor,
  seasonRange,
  weatherWarnings,
  weekDays,
  type CalendarDay,
  type CalendarTask,
  type FieldObservation,
} from '../src';

const task = (id: string, day: string, extra: Partial<CalendarTask> = {}): CalendarTask => ({ id, title: id, type: 'HERBICIDE', parcelId: 'p1', priority: 'MEDIUM', status: 'TODO', day, assigneeId: null, machineId: null, ...extra });

describe('calendar', () => {
  it('builds week, month and season ranges', () => {
    expect(weekDays('2026-06-10')).toEqual(['2026-06-08', '2026-06-09', '2026-06-10', '2026-06-11', '2026-06-12', '2026-06-13', '2026-06-14']);
    const grid = monthGrid('2026-06-10');
    expect(grid[0]![0]).toBe('2026-06-01');
    expect(grid.every((week) => week.length === 7)).toBe(true);
    expect(grid.flat()).toContain('2026-06-30');
    expect(seasonRange('2026-06-10')).toEqual({ start: '2025-09-01', end: '2026-08-31', label: '2025/26' });
  });

  it('colours operations by category', () => {
    expect(categoryOf('PLOWING')).toBe('TILLAGE');
    expect(categoryOf('SOWING')).toBe('PLANTING');
    expect(categoryOf('FUNGICIDE')).toBe('CROP_PROTECTION');
    expect(categoryOf('HARVESTING')).toBe('HARVEST');
    expect(categoryOf('PIVOT')).toBe('IRRIGATION');
  });

  it('detects double-booked machines and operators and missing machine types', () => {
    const conflicts = detectConflicts({
      tasks: [
        task('a', '2026-06-10', { machineId: 'm1', assigneeId: 'u1', parcelId: 'p1' }),
        task('b', '2026-06-10', { machineId: 'm1', assigneeId: 'u1', parcelId: 'p2' }),
        task('c', '2026-06-11', { type: 'SOWING', parcelId: 'p1' }),
        task('d', '2026-06-11', { type: 'SOWING', parcelId: 'p2' }),
      ],
      machines: [{ id: 'm1', name: 'Fendt', type: 'SPRAYER' }, { id: 'm2', name: 'Seeder 1', type: 'SEEDER' }],
      members: [{ id: 'u1', name: 'Ion', type: 'OPERATOR' }],
    });
    expect(conflicts.map((item) => item.kind)).toEqual(['MACHINE', 'OPERATOR', 'CAPACITY']);
    expect(conflicts[2]!.reason).toContain('2 fields need a seeder');
  });

  it('warns about weather for the operation', () => {
    const days: CalendarDay[] = [
      { date: '2026-06-10', tMinC: 12, tMaxC: 24, precipitationMm: 0, rainProbabilityPct: 5, windMaxKmh: 30 },
      { date: '2026-06-11', tMinC: 12, tMaxC: 24, precipitationMm: 6, rainProbabilityPct: 80, windMaxKmh: 8 },
      { date: '2026-06-12', tMinC: 12, tMaxC: 24, precipitationMm: 0, rainProbabilityPct: 5, windMaxKmh: 8 },
      { date: '2026-06-13', tMinC: 12, tMaxC: 24, precipitationMm: 14, rainProbabilityPct: 90, windMaxKmh: 8 },
    ];
    expect(weatherWarnings({ type: 'HERBICIDE', day: '2026-06-10' }, days)[0]!.title).toBe('Spraying not recommended');
    expect(weatherWarnings({ type: 'HERBICIDE', day: '2026-06-11' }, days)[0]!.title).toBe('Delay herbicide application');
    expect(weatherWarnings({ type: 'HARVEST', day: '2026-06-13' }, days)[0]!.title).toBe('Harvest risk');
    expect(bestDayFor('HERBICIDE', '2026-06-10', days)).toBe('2026-06-12');
  });

  it('summarises the planning dashboard and the field timeline', () => {
    const tasks = [task('late', '2026-06-08'), task('now', '2026-06-10', { machineId: 'm1', priority: 'HIGH' }), task('later', '2026-06-12')];
    const dashboard = calendarDashboard({ tasks, runs: [], machines: [{ id: 'm1', name: 'A', type: 'TRACTOR' }, { id: 'm2', name: 'B', type: 'TRACTOR' }], operators: [], today: '2026-06-10' });
    expect(dashboard.delayed.map((item) => item.id)).toEqual(['late']);
    expect(dashboard.today.map((item) => item.id)).toEqual(['now']);
    expect(dashboard.availableMachines.map((item) => item.id)).toEqual(['m2']);
    expect(dashboard.critical.map((item) => item.id)).toEqual(['now']);
    const timeline = fieldTimeline({ parcelId: 'p1', tasks, runs: [], seasons: [{ year: 2026, cropName: 'Corn', sowingDate: '2026-04-20', harvestDate: null }], from: '2026-01-01', to: '2026-12-31', today: '2026-06-10' });
    expect(timeline.map((item) => item.state)).toEqual(['DONE', 'OVERDUE', 'PLANNED', 'PLANNED']);
  });
});

const square: [number, number][] = [[26.3, 44.55], [26.31, 44.55], [26.31, 44.56], [26.3, 44.56], [26.3, 44.55]];
const obs = (date: string, ndvi: number, extra: Partial<FieldObservation> = {}): FieldObservation => ({ sceneId: date, date, cloudPct: 0, ndvi: { mean: ndvi, std: 0.05, p2: ndvi - 0.1, p98: ndvi + 0.1 }, ndre: 0.4, ndmi: 0.1, zones: [], ...extra });

describe('satellite monitoring', () => {
  it('splits a field into named zones clipped to its outline', () => {
    const zones = fieldZones(square, 80);
    expect(zones).toHaveLength(9);
    expect(zones.map((zone) => zone.name)).toContain('north-west');
    expect(zones.reduce((sum, zone) => sum + zone.share, 0)).toBeCloseTo(1, 2);
    const triangle = fieldZones([[26.3, 44.55], [26.31, 44.55], [26.3, 44.56], [26.3, 44.55]], 40);
    expect(triangle.some((zone) => zone.name === 'north-east')).toBe(false);
  });

  it('scores field health and colours NDVI', () => {
    expect(fieldHealth(obs('2026-06-10', 0.8), true).level).toBe('EXCELLENT');
    expect(fieldHealth(obs('2026-06-10', 0.45, { ndmi: -0.25 }), true).level).toBe('CRITICAL');
    expect(fieldHealth(obs('2026-06-10', 0.15), false).level).toBe('NO_CROP');
    expect([ndviColor(0.7), ndviColor(0.5), ndviColor(0.3), ndviColor(0.1)]).toEqual(['#5FB35A', '#E2C23D', '#E9883B', '#E0584A']);
  });

  it('explains weak zones, nutrient and water stress, and proposes tasks', () => {
    const zones = fieldZones(square, 80);
    const north = zones.find((zone) => zone.name === 'north')!;
    const west = zones.find((zone) => zone.name === 'west')!;
    const east = zones.find((zone) => zone.name === 'east')!;
    const current = obs('2026-06-10', 0.7, {
      zones: [
        { zoneId: north.id, ndvi: 0.5, ndre: 0.35, ndmi: 0.1 },
        { zoneId: west.id, ndvi: 0.69, ndre: 0.3, ndmi: 0.1 },
        { zoneId: east.id, ndvi: 0.7, ndre: 0.4, ndmi: -0.05 },
      ],
    });
    const findings = analyzeField({ field: { name: 'Field 12', crop: 'corn', stagePct: 50, irrigated: true }, current, earlier: [], zones });
    expect(findings.find((item) => item.kind === 'LOW_VIGOR')!.message).toContain('North section of Field 12 shows reduced vegetation vigor');
    expect(findings.find((item) => item.kind === 'NUTRIENT')!.task!.type).toBe('NITROGEN');
    expect(findings.find((item) => item.kind === 'WATER_STRESS')!.task!.type).toBe('IRRIGATION_CHECK');
  });

  it('separates decline from ripening and reports bare fields', () => {
    const earlier = [obs('2026-06-01', 0.75)];
    const decline = analyzeField({ field: { name: 'F', crop: 'corn', stagePct: 50, irrigated: false }, current: obs('2026-06-12', 0.55), earlier, zones: [] });
    expect(decline[0]!.kind).toBe('DECLINE');
    const ripe = analyzeField({ field: { name: 'F', crop: 'wheat', stagePct: 92, irrigated: false }, current: obs('2026-06-12', 0.55), earlier, zones: [] });
    expect(ripe.map((item) => item.kind)).toEqual(['RIPENING']);
    expect(analyzeField({ field: { name: 'F', crop: null, stagePct: null, irrigated: false }, current: obs('2026-06-12', 0.18), earlier: [], zones: [] })[0]!.kind).toBe('NO_CROP');
  });

  it('compares with 7, 30, 90 days and last year', () => {
    const comparison = compareHistory([
      { date: '2026-06-10', ndvi: 0.7 },
      { date: '2026-06-03', ndvi: 0.65 },
      { date: '2026-05-12', ndvi: 0.5 },
      { date: '2026-03-15', ndvi: 0.3 },
      { date: '2025-06-08', ndvi: 0.6 },
    ]);
    expect(comparison.d7).toMatchObject({ date: '2026-06-03', delta: 0.05 });
    expect(comparison.d30!.date).toBe('2026-05-12');
    expect(comparison.d90!.date).toBe('2026-03-15');
    expect(comparison.yearAgo).toMatchObject({ date: '2025-06-08', delta: 0.1 });
  });
});
