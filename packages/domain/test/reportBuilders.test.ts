import { describe, expect, it } from 'vitest';
import { computeParcelEconomics, cropsReport, defaultSettings, emptySheet, farmReport, machinesReport, parcelsReport, profitabilityReport, reportChartPoints, summarizeFarmEconomics, type ReportParcel, type ReportRun } from '../src';

const now = new Date(2026, 4, 12, 10);
const parcels: ReportParcel[] = [
  { id: 'p1', name: 'North', code: 'P-1', areaHa: 12, locality: null, crop: 'CORN', tillage: 'NO_TILL' },
  { id: 'p2', name: 'South', code: 'P-2', areaHa: 8, locality: null, crop: 'WHEAT', tillage: null },
];
const runs: ReportRun[] = [
  { parcelId: 'p1', machineId: 'm1', type: 'SOWING', status: 'COMPLETED', areaHa: 12, percent: 100, hours: 4, fuelL: 70, startedAt: '2026-04-02T08:00:00Z' },
  { parcelId: 'p2', machineId: 'm1', type: 'SPRAYING', status: 'RUNNING', areaHa: 8, percent: 40, hours: 1, fuelL: 10, startedAt: '2026-05-12T07:00:00Z' },
];

describe('shared reports', () => {
  it('lists parcels with their plan status and completion', () => {
    const report = parcelsReport({ year: 2026, parcels, runs, now, tasks: [{ id: 't', title: 'Spray', status: 'TODO', dueDate: new Date(2026, 4, 10).toISOString(), parcelId: 'p1' }] });
    expect(report.rows[0]).toMatchObject({ parcel: 'North', status: 'Overdue work', overdue: 1, completion: 0 });
    expect(report.rows[1]).toMatchObject({ status: 'Work in progress' });
    expect(reportChartPoints(report)[0]).toEqual({ label: 'North', value: 12 });
  });

  it('shows which machines work and which are available', () => {
    const report = machinesReport({ year: 2026, runs, parcelName: (id) => (id === 'p2' ? 'South' : 'North'), machines: [{ id: 'm1', name: 'JD', type: 'TRACTOR', operatorName: 'Ion' }, { id: 'm2', name: 'Claas', type: 'COMBINE', operatorName: null }] });
    expect(report.rows[0]).toMatchObject({ operations: 2, hours: 5, area: 15.2, status: 'Working, South' });
    expect(report.rows[1]).toMatchObject({ status: 'Available' });
  });

  it('groups crops and computes the yield per hectare', () => {
    const report = cropsReport({ year: 2026, parcels, seasons: [{ parcelId: 'p1', crop: 'CORN', yieldT: 108 }, { parcelId: 'p2', crop: 'WHEAT', yieldT: null }] });
    expect(report.rows[0]).toMatchObject({ crop: 'Corn', area: 12, share: 60, perHa: 9 });
  });

  it('builds the profitability and farm overview from the economics', () => {
    const result = computeParcelEconomics({ areaHa: 12, sheet: { ...emptySheet(), misc: [{ id: 'a', label: 'All', amount: 620, basis: 'PER_HA' }], revenue: { expectedYieldTHa: 9, pricePerT: 220 } }, operations: [], settings: defaultSettings() });
    const item = { parcelId: 'p1', name: 'North', crop: 'CORN' as const, cropName: 'Corn', tillage: null, result };
    const summary = summarizeFarmEconomics([item]);
    expect(profitabilityReport({ year: 2026, parcels: [item], summary }).rows[0]).toMatchObject({ parcel: 'North', revenuePerHa: 1980, basis: 'Expected' });
    const farm = farmReport({ year: 2026, farmName: 'Test', parcels, runs, tasks: [], economics: summary, now });
    expect(farm.rows.find((row) => row.metric === 'Operations carried out')?.value).toBe('2');
    expect(reportChartPoints(farm).length).toBeGreaterThan(0);
  });
});
