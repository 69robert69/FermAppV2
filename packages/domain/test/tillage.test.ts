import { describe, expect, it } from 'vitest';
import {
  analyzeTillage,
  checkMachineCompatibility,
  checkTaskCompatibility,
  checkWorkCompatibility,
  estimateParcelTillage,
  machineTillageSystems,
  parseTillageHistory,
  planTillageOperations,
  tillageInYear,
  tillageRecommendations,
  tillageTimeline,
  TILLAGE_LIST,
  withTillageYear,
} from '../src';

describe('tillage technologies', () => {
  it('estimates cost, fuel and savings against conventional tillage', () => {
    const estimate = estimateParcelTillage('NO_TILL', 10);
    expect(estimate.totalCost).toBe(2500);
    expect(estimate.costSavings).toBe(2700);
    expect(estimate.seasonFuelL).toBe(120);
    expect(estimate.annualFuelSavingsL).toBe(480);
    expect(estimate.passesSaved).toBe(3);
  });

  it('uses the user’s own figures when they are set', () => {
    const estimate = estimateParcelTillage('MINIMUM_TILL', 10, { MINIMUM_TILL: { costPerHa: 400 }, CONVENTIONAL: { fuelLPerHa: 70 } });
    expect(estimate.totalCost).toBe(4000);
    expect(estimate.annualFuelSavingsL).toBe(350);
  });

  it('counts every season of the year in the annual figures', () => {
    const estimate = estimateParcelTillage('STRIP_TILL', 10, {}, 2);
    expect(estimate.seasonFuelL).toBe(250);
    expect(estimate.annualFuelL).toBe(500);
  });

  it('warns about operations that contradict the technology', () => {
    expect(checkWorkCompatibility('NO_TILL', 'PLOWING').compatible).toBe(false);
    expect(checkWorkCompatibility('MINIMUM_TILL', 'PLOWING').compatible).toBe(false);
    expect(checkWorkCompatibility('MINIMUM_TILL', 'DISCING').compatible).toBe(true);
    expect(checkWorkCompatibility('CONVENTIONAL', 'PLOWING').compatible).toBe(true);
    expect(checkWorkCompatibility(null, 'PLOWING').compatible).toBe(true);
    expect(checkTaskCompatibility('NO_TILL', 'SOIL_PREP').message).toContain('No Till');
  });

  it('checks machines against their own list first, then the type defaults', () => {
    expect(checkMachineCompatibility({ name: 'Drill', type: 'SEEDER' }, 'NO_TILL')).toEqual({
      compatible: false,
      message: 'Drill is not recommended for No Till.',
    });
    expect(checkMachineCompatibility({ type: 'SEEDER', tillageSystems: ['NO_TILL'] }, 'NO_TILL').compatible).toBe(true);
    expect(machineTillageSystems({ type: 'TRACTOR' })).toHaveLength(TILLAGE_LIST.length);
  });

  it('dates the recommended operations from the first one', () => {
    const plan = planTillageOperations('CONVENTIONAL', new Date('2026-03-01T12:00:00Z'));
    expect(plan.map((item) => item.key)).toEqual(['plowing', 'discing', 'seedbed', 'sowing']);
    expect(plan[3]!.dueDate.toISOString().slice(0, 10)).toBe('2026-03-22');
    expect(planTillageOperations('NO_TILL', new Date())).toHaveLength(1);
  });

  it('gives advice for each technology', () => {
    expect(tillageRecommendations('CONVENTIONAL')[0]!.message).toContain('compaction');
    expect(tillageRecommendations('STRIP_TILL', { crop: 'WHEAT' }).some((item) => item.tone === 'warning')).toBe(true);
  });

  it('analyses the farm by technology', () => {
    const analysis = analyzeTillage([
      { areaHa: 10, system: 'CONVENTIONAL' },
      { areaHa: 30, system: 'NO_TILL' },
      { areaHa: 5, system: null },
    ]);
    expect(analysis.totalAreaHa).toBe(45);
    expect(analysis.unassignedAreaHa).toBe(5);
    expect(analysis.bySystem.find((row) => row.system === 'NO_TILL')!.areaHa).toBe(30);
    expect(analysis.fuelSavingsL).toBe(30 * 48);
    expect(analysis.avgSoilHealth).toBe(83);
    expect(analysis.passReductionPct).toBe(56);
  });

  it('keeps one history entry per year and reads the technology of any year', () => {
    let history = withTillageYear([], { year: 2025, system: 'MINIMUM_TILL' });
    history = withTillageYear(history, { year: 2024, system: 'CONVENTIONAL' });
    history = withTillageYear(history, { year: 2025, system: 'STRIP_TILL' });
    expect(history).toEqual([
      { year: 2024, system: 'CONVENTIONAL' },
      { year: 2025, system: 'STRIP_TILL' },
    ]);
    const parcel = { areaHa: 10, system: 'STRIP_TILL' as const, history };
    expect(tillageInYear(parcel, 2023)).toBeNull();
    expect(tillageInYear(parcel, 2027)).toBe('STRIP_TILL');
    const timeline = tillageTimeline([parcel], [2024, 2025]);
    expect(timeline[0]!.avgSoilHealth).toBe(60);
    expect(timeline[1]!.avgSoilHealth).toBe(82);
    expect(parseTillageHistory([{ year: 2024, system: 'UNKNOWN' }, { year: 2023, system: 'NO_TILL' }])).toEqual([{ year: 2023, system: 'NO_TILL' }]);
  });
});
