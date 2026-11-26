import { describe, expect, it } from 'vitest';
import {
  chemicalCost,
  computeParcelEconomics,
  defaultSettings,
  economicInsights,
  emptySheet,
  fertilizerCost,
  formatEuro,
  formatEuroPerHa,
  fuelPerHaFor,
  laborHourlyRate,
  machineHourlyCost,
  parseSettings,
  parseSheet,
  seedCost,
  summarizeFarmEconomics,
  toEuro,
  type EconomicSheet,
  type OperationInput,
} from '../src';

describe('euro formatting', () => {
  it('uses dots for thousands and a comma for decimals', () => {
    expect(formatEuro(1250)).toBe('€1.250');
    expect(formatEuro(12450.5)).toBe('€12.450,50');
    expect(formatEuroPerHa(120)).toBe('€120/ha');
    expect(formatEuro(366.666, 2)).toBe('€366,67');
    expect(formatEuro(-40)).toBe('-€40');
    expect(formatEuro(null)).toBe('-');
  });

  it('reads amounts recorded in lei as euro', () => {
    expect(toEuro(497, 'RON')).toBe(100);
    expect(toEuro(100, 'EUR')).toBe(100);
  });
});

describe('input costs', () => {
  it('prices seed sold by count and by weight', () => {
    const corn = seedCost({ id: 'a', supplier: 'Pioneer', hybrid: 'P9911', unit: 'SEEDS', bagSize: 80_000, ratePerHa: 70_000, pricePerBag: 180 }, 10);
    expect(corn.costPerHa).toBe(157.5);
    expect(corn.bagsNeeded).toBe(8.8);
    expect(corn.costPer1000Seeds).toBe(2.25);
    const wheat = seedCost({ id: 'b', supplier: 'Own', hybrid: 'Glosa', unit: 'KG', bagSize: 25, ratePerHa: 220, pricePerBag: 18, bagsUsed: 90 }, 10);
    expect(wheat.costPerKg).toBe(0.72);
    expect(wheat.total).toBe(1620);
  });

  it('prices fertiliser per tonne and crop protection per litre', () => {
    const urea = fertilizerCost({ id: 'u', product: 'Urea', ratePerHaKg: 150, pricePerT: 430, n: 46, applications: 2 }, 10);
    expect(urea.costPerHa).toBe(129);
    expect(urea.nutrients.n).toBe(138);
    const herbicide = chemicalCost({ id: 'h', product: 'Adengo', kind: 'HERBICIDE', dosePerHa: 0.44, unit: 'L', pricePerUnit: 95 }, 10);
    expect(herbicide.costPerHa).toBe(41.8);
    expect(herbicide.total).toBe(418);
  });
});

describe('machines and labour', () => {
  it('turns ownership and upkeep into a cost per hour', () => {
    const cost = machineHourlyCost({ purchasePrice: 150_000, residualPct: 20, depreciationYears: 10, annualHours: 600, serviceCostPerYear: 3000, maintenanceCostPerYear: 1800 });
    expect(cost.depreciation).toBe(20);
    expect(cost.perHour).toBe(28);
    expect(machineHourlyCost(null, 'COMBINE')).toMatchObject({ perHour: 120, estimated: true });
  });

  it('derives the hourly rate from the monthly salary', () => {
    expect(laborHourlyRate({ monthlySalary: 1680 })).toBe(10);
    expect(laborHourlyRate({ hourlyRate: 14, monthlySalary: 1680 })).toBe(14);
    expect(laborHourlyRate(null, 9)).toBe(9);
  });

  it('shares the technology’s diesel between its operations', () => {
    expect(fuelPerHaFor('PLOWING', 'CONVENTIONAL')).toBeGreaterThan(fuelPerHaFor('SOWING', 'CONVENTIONAL'));
    expect(fuelPerHaFor('SOWING', 'NO_TILL')).toBe(12);
  });
});

const sheet: EconomicSheet = {
  ...emptySheet(),
  seeds: [{ id: 's', supplier: 'Pioneer', hybrid: 'P9911', unit: 'SEEDS', bagSize: 80_000, ratePerHa: 70_000, pricePerBag: 180 }],
  fertilizers: [{ id: 'f', product: 'Urea', ratePerHaKg: 150, pricePerT: 430 }],
  chemicals: [{ id: 'c', product: 'Adengo', kind: 'HERBICIDE', dosePerHa: 0.44, unit: 'L', pricePerUnit: 95 }],
  misc: [{ id: 'm', label: 'Insurance', amount: 15, basis: 'PER_HA' }],
  revenue: { expectedYieldTHa: 9, pricePerT: 220 },
};

const ploughing: OperationInput = {
  id: 'w1',
  type: 'PLOWING',
  status: 'COMPLETED',
  areaHa: 12,
  progressPct: 100,
  hours: 10,
  fuelLiters: 240,
  machine: { id: 't1', name: 'JD 6155R', type: 'TRACTOR', fuelLPerH: 24 },
  operator: { id: 'u1', name: 'Ion' },
  startedAt: '2026-03-01T08:00:00Z',
};

describe('parcel economics', () => {
  const result = computeParcelEconomics({ areaHa: 12, sheet, operations: [ploughing], settings: defaultSettings(), tillage: 'CONVENTIONAL', crop: 'CORN' });

  it('adds inputs, logged work and the operations still to come', () => {
    expect(result.toDate.byCategory.SEEDS).toBe(1890);
    expect(result.toDate.byCategory.FUEL).toBe(348);
    expect(result.toDate.byCategory.LABOR).toBe(100);
    expect(result.toDate.byCategory.MACHINERY).toBe(350);
    // Ploughing is logged, so only the remaining discing, seedbed, sowing, spraying, fertilising and harvest are projected.
    expect(result.projected.map((op) => op.type)).not.toContain('PLOWING');
    expect(result.projected.map((op) => op.type)).toContain('HARVESTING');
    expect(result.season.total).toBeGreaterThan(result.toDate.total);
    expect(result.season.perHa).toBeCloseTo(result.season.total / 12, 1);
  });

  it('computes revenue, profit per hectare and margin', () => {
    expect(result.expected!.revenuePerHa).toBe(1980);
    expect(result.expected!.profitPerHa).toBeCloseTo(1980 - result.season.perHa, 1);
    expect(result.actual).toBeNull();
  });

  it('matches the profitability example: 9 t/ha at €220/t against €620/ha', () => {
    const simple = computeParcelEconomics({
      areaHa: 1,
      sheet: { ...emptySheet(), misc: [{ id: 'all', label: 'All costs', amount: 620, basis: 'TOTAL' }], revenue: { expectedYieldTHa: 9, pricePerT: 220 } },
      operations: [{ ...ploughing, type: 'SOWING', areaHa: 1, hours: 0, fuelLiters: 0, machine: null, operator: null }],
      settings: { ...defaultSettings(), defaultLaborRate: 0 },
    });
    const operationsCost = [...simple.operations, ...simple.projected].reduce((sum, op) => sum + op.total, 0);
    expect(simple.expected!.revenuePerHa).toBe(1980);
    // €620 of inputs plus the field operations: profit = 1.980 - 620 - operations.
    expect(simple.season.perHa).toBeCloseTo(620 + operationsCost, 1);
    expect(simple.expected!.profitPerHa).toBeCloseTo(1360 - operationsCost, 1);
  });

  it('explains where the money goes and what is cheaper', () => {
    const insights = economicInsights(result, { tillage: 'CONVENTIONAL' });
    expect(insights.some((item) => item.message.includes('Minimum Till') && item.message.includes('%'))).toBe(true);
    expect(insights.some((item) => item.message.includes('most expensive operation'))).toBe(true);
  });

  it('ranks parcels, crops and technologies across the farm', () => {
    const other = computeParcelEconomics({ areaHa: 20, sheet: { ...sheet, revenue: { expectedYieldTHa: 6, pricePerT: 210 } }, operations: [], settings: defaultSettings(), tillage: 'NO_TILL', crop: 'WHEAT' });
    const farm = summarizeFarmEconomics([
      { parcelId: 'a', name: 'North', crop: 'CORN', cropName: 'Corn', tillage: 'CONVENTIONAL', result },
      { parcelId: 'b', name: 'South', crop: 'WHEAT', cropName: 'Wheat', tillage: 'NO_TILL', result: other },
    ]);
    expect(farm.areaHa).toBe(32);
    expect(farm.byTillage).toHaveLength(2);
    expect(farm.mostProfitableParcel?.label).toBe('North');
    expect(farm.mostProfitableCrop?.label).toBe('Corn');
    expect(farm.mostEfficientTillage).not.toBeNull();
  });
});

describe('stored documents', () => {
  it('keeps only well-formed lines and fills the defaults', () => {
    const parsed = parseSheet({ seeds: [{ supplier: 'X', unit: 'KG', bagSize: 25, ratePerHa: 200, pricePerBag: 20 }, 'bad'], revenue: { pricePerT: 'x' } });
    expect(parsed.seeds).toHaveLength(1);
    expect(parsed.revenue.pricePerT).toBeNull();
    expect(parseSettings(null).fuelPricePerL).toBe(1.45);
  });
});
