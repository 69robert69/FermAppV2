import { describe, expect, it } from 'vitest';
import {
  filterSeeds,
  fuelLitersPerHour,
  parseCsv,
  rankSeedsForParcel,
  recommendMachine,
  SEED_CATALOG,
  seedFromRow,
  TRACTOR_CATALOG,
  tractorFromRow,
  workCost,
  buildFieldPath,
  buildParcelPlan,
  nextSeasonYear,
  planCalendar,
  pointInRing,
  positionOnPath,
  rankCropsForParcel,
  bestWindowMessage,
  classifySoil,
  computeGeoMetrics,
  cropDevelopment,
  cropProgress,
  nextRecommendedAction,
  parcelEconomics,
  soilIndices,
  sprayingAlerts,
  dailyGdd,
  estimateIrrigation,
  estimateWork,
  evaluateRotation,
  evaluateWeatherAlerts,
  findWorkWindows,
  isSelfIntersecting,
  recommendCrops,
  summarizeCosts,
  timedProgress,
  validatePolygon,
  vegetationHealth,
  waterBalance,
  workCapacityHaH,
  type ForecastHour,
  type PolygonGeometry,
  type Position,
} from '../src';

const hour = (time: string, overrides: Partial<ForecastHour> = {}): ForecastHour => ({
  time,
  temperatureC: 16,
  humidityPct: 65,
  precipitationMm: 0,
  precipitationProbabilityPct: 0,
  windKmh: 6,
  ...overrides,
});

describe('geo', () => {
  // Pătrat de aproximativ 1 km × 1 km la latitudinea 45°.
  const dLat = 1000 / 111_195;
  const dLng = dLat / Math.cos((45 * Math.PI) / 180);
  const square: PolygonGeometry = {
    type: 'Polygon',
    coordinates: [[[25, 45], [25 + dLng, 45], [25 + dLng, 45 + dLat], [25, 45 + dLat], [25, 45]]],
  };

  it('calculează hectarele, perimetrul și centrul', () => {
    const metrics = computeGeoMetrics(square);
    expect(metrics.areaHa).toBeGreaterThan(99);
    expect(metrics.areaHa).toBeLessThan(101);
    expect(metrics.perimeterM).toBeGreaterThan(3980);
    expect(metrics.perimeterM).toBeLessThan(4020);
    expect(metrics.center.lat).toBeCloseTo(45 + dLat / 2, 5);
    expect(metrics.center.lng).toBeCloseTo(25 + dLng / 2, 5);
  });

  it('scade golurile din suprafață', () => {
    const c = 0.25;
    const hole: Position[] = [
      [25 + dLng * c, 45 + dLat * c],
      [25 + dLng * c, 45 + dLat * 0.75],
      [25 + dLng * 0.75, 45 + dLat * 0.75],
      [25 + dLng * 0.75, 45 + dLat * c],
      [25 + dLng * c, 45 + dLat * c],
    ];
    const withHole = computeGeoMetrics({ type: 'Polygon', coordinates: [square.coordinates[0]!, hole] });
    expect(withHole.areaHa).toBeGreaterThan(74);
    expect(withHole.areaHa).toBeLessThan(76);
  });

  it('respinge conturul în formă de fundă', () => {
    const bowtie: Position[] = [[25, 45], [25.01, 45.01], [25.01, 45], [25, 45.01]];
    expect(isSelfIntersecting(bowtie)).toBe(true);
    expect(validatePolygon({ type: 'Polygon', coordinates: [bowtie] })).toMatch(/intersectează/);
    expect(validatePolygon(square)).toBeNull();
  });
});

describe('rotație', () => {
  it('reproduce exemplul pentru floarea-soarelui cultivată acum 2 ani', () => {
    const result = evaluateRotation('SUNFLOWER', 2026, [
      { crop: 'WHEAT', year: 2025 },
      { crop: 'SUNFLOWER', year: 2024 },
    ]);
    expect(result.verdict).toBe('NOT_RECOMMENDED');
    expect(result.explanation).toBe(
      'Floarea-soarelui a fost cultivată acum 2 ani pe această parcelă. Se recomandă o pauză minimă de 4 ani. Riscul de boli și scăderea producției este ridicat.',
    );
  });

  it('aplică pragul exact al pauzei minime', () => {
    const tooSoon = evaluateRotation('SUNFLOWER', 2026, [{ crop: 'SUNFLOWER', year: 2022 }]);
    const allowed = evaluateRotation('SUNFLOWER', 2027, [{ crop: 'SUNFLOWER', year: 2022 }]);
    expect(tooSoon.verdict).toBe('NOT_RECOMMENDED');
    expect(allowed.verdict).not.toBe('NOT_RECOMMENDED');
  });

  it('respectă pauza de 3 ani la rapiță', () => {
    expect(evaluateRotation('RAPESEED', 2026, [{ crop: 'RAPESEED', year: 2023 }]).verdict).toBe('NOT_RECOMMENDED');
    expect(evaluateRotation('RAPESEED', 2026, [{ crop: 'RAPESEED', year: 2022 }]).verdict).not.toBe('NOT_RECOMMENDED');
  });

  it('favorizează porumbul după leguminoase și soia după cereale', () => {
    const cornAfterSoy = evaluateRotation('CORN', 2026, [{ crop: 'SOY', year: 2025 }]);
    const soyAfterWheat = evaluateRotation('SOY', 2026, [{ crop: 'WHEAT', year: 2025 }]);
    expect(cornAfterSoy.verdict).toBe('RECOMMENDED');
    expect(cornAfterSoy.factors.map((f) => f.code)).toContain('CORN_AFTER_LEGUME');
    expect(soyAfterWheat.factors.map((f) => f.code)).toContain('SOY_AFTER_CEREAL');
  });

  it('recunoaște aportul de azot al lucernei', () => {
    const result = evaluateRotation('WHEAT', 2026, [{ crop: 'ALFALFA', year: 2025 }]);
    expect(result.factors.map((f) => f.code)).toContain('ALFALFA_NITROGEN');
    expect(result.score).toBeLessThanOrEqual(100);
  });

  it('păstrează scorul în intervalul 0-100', () => {
    const history = [2025, 2024, 2023, 2022, 2021].map((year) => ({ crop: 'SUNFLOWER' as const, year }));
    const { score } = evaluateRotation('SUNFLOWER', 2026, history);
    expect(score).toBeGreaterThanOrEqual(0);
    expect(score).toBeLessThan(40);
  });
});

describe('lucrări și irigații', () => {
  it('aplică formula de capacitate', () => {
    expect(workCapacityHaH({ workingWidthM: 6, speedKmh: 8, efficiencyPct: 80 })).toBeCloseTo(3.84);
  });

  it('estimează durata și ETA', () => {
    const start = new Date('2026-05-01T08:00:00Z');
    const result = estimateWork(38.4, { workingWidthM: 6, speedKmh: 8, efficiencyPct: 80 }, start);
    expect(result.durationHours).toBe(10);
    expect(result.eta.toISOString()).toBe('2026-05-01T18:00:00.000Z');
  });

  it('oprește progresul în pauză', () => {
    const startedAt = new Date('2026-05-01T08:00:00Z');
    const progress = timedProgress({
      startedAt,
      durationMs: 4 * 3_600_000,
      pausedAt: new Date('2026-05-01T09:00:00Z'),
      now: new Date('2026-05-01T11:00:00Z'),
    });
    expect(progress.percent).toBe(25);
    expect(progress.finished).toBe(false);
  });

  it('calculează irigarea după traseu și viteză', () => {
    const result = estimateIrrigation(400, 25, new Date('2026-06-01T06:00:00Z'));
    expect(result.durationHours).toBe(16);
    expect(result.eta.toISOString()).toBe('2026-06-01T22:00:00.000Z');
  });
});

describe('apă', () => {
  it('calculează deficitul pentru porumb', () => {
    const balance = waterBalance({ crop: 'CORN', precipitationMm: 325 });
    expect(balance.requiredMm).toBe(625);
    expect(balance.deficitMm).toBe(300);
    expect(balance.irrigationPasses).toBe(10);
  });

  it('nu cere irigare când necesarul e acoperit', () => {
    const balance = waterBalance({ crop: 'WHEAT', precipitationMm: 600 });
    expect(balance.deficitMm).toBe(0);
    expect(balance.irrigationPasses).toBe(0);
  });
});

describe('ferestre de lucru', () => {
  it('găsește intervalul de erbicidare și îl formulează în română', () => {
    // 18 noiembrie 2026 este miercuri.
    const hours = Array.from({ length: 12 }, (_, i) => {
      const h = String(i + 4).padStart(2, '0');
      const windy = i < 2 || i >= 7;
      return hour(`2026-11-18T${h}:00`, { windKmh: windy ? 24 : 6 });
    });
    const windows = findWorkWindows('SPRAYING', hours);
    expect(windows).toHaveLength(1);
    expect(windows[0]!.label).toBe('Miercuri 06:00-11:00');
    expect(bestWindowMessage('SPRAYING', windows)).toBe('Interval optim pentru erbicidare: Miercuri 06:00-11:00');
  });

  it('exclude orele imediat după ploaie', () => {
    const hours = [
      hour('2026-11-18T06:00', { precipitationMm: 4, precipitationProbabilityPct: 90 }),
      hour('2026-11-18T07:00'),
      hour('2026-11-18T08:00'),
      hour('2026-11-18T09:00'),
      hour('2026-11-18T10:00'),
      hour('2026-11-18T11:00'),
    ];
    expect(findWorkWindows('SPRAYING', hours)[0]!.start).toBe('2026-11-18T10:00');
  });

  it('trece corect peste miezul nopții', () => {
    const hours = [hour('2026-11-18T22:00'), hour('2026-11-18T23:00')];
    expect(findWorkWindows('SPRAYING', hours)[0]!.end).toBe('2026-11-19T00:00');
    expect(findWorkWindows('SPRAYING', hours)[0]!.label).toBe('Miercuri 22:00-00:00');
  });

  it('afișează ambele zile pentru un interval de peste o zi', () => {
    const hours = Array.from({ length: 24 }, (_, i) => {
      const date = new Date(Date.UTC(2026, 10, 18, 22 + i)).toISOString().slice(0, 16);
      return hour(date);
    });
    expect(findWorkWindows('SPRAYING', hours)[0]!.label).toBe('Miercuri 22:00 - Joi 22:00');
  });
});

describe('sol', () => {
  it('clasifică un cernoziom argilos', () => {
    const soil = classifySoil({ ph: 6.8, clayPct: 42, sandPct: 20, organicCarbonGKg: 22.6 });
    expect(soil.label).toBe('Cernoziom argilos');
    expect(soil.organicMatterPct).toBeCloseTo(3.9, 1);
  });

  it('calculează apa utilă din valorile de retenție', () => {
    const soil = classifySoil({ ph: 7, clayPct: 30, sandPct: 30, organicCarbonGKg: 15, fieldCapacityPct: 34, wiltingPointPct: 18 });
    expect(soil.waterHoldingMmPerM).toBe(160);
  });
});

describe('recomandări', () => {
  it('penalizează cultura care încalcă rotația', () => {
    const ranked = recommendCrops({
      year: 2026,
      history: [{ crop: 'SUNFLOWER', year: 2024 }, { crop: 'WHEAT', year: 2025 }],
      soil: { ph: 6.8, texture: 'CLAY_LOAM' },
      climate: { seasonTempC: 19, annualPrecipitationMm: 560 },
    });
    const sunflower = ranked.find((r) => r.crop === 'SUNFLOWER')!;
    const soy = ranked.find((r) => r.crop === 'SOY')!;
    expect(soy.score).toBeGreaterThan(sunflower.score);
    expect(sunflower.reasons[0]).toContain('pauză minimă');
  });

  it('marchează criteriile fără date', () => {
    const [first] = recommendCrops({ year: 2026, history: [], crops: ['WHEAT'] });
    expect(first!.missingData).toEqual(['sol', 'climă']);
  });
});

describe('GDD', () => {
  it('limitează temperaturile la prag', () => {
    expect(dailyGdd(35, 5, 10, 30)).toBe(10);
    expect(dailyGdd(8, 2, 10)).toBe(0);
  });

  it('estimează stadiul culturii', () => {
    const days = Array.from({ length: 60 }, (_, i) => ({ date: `d${i}`, tMaxC: 30, tMinC: 18 }));
    const development = cropDevelopment('CORN', days);
    expect(development.accumulatedGdd).toBe(840);
    expect(development.stage).toBe('Formarea boabelor');
  });
});

describe('vegetație', () => {
  it('colorează parcelele după starea culturii', () => {
    expect(vegetationHealth(0.75, 0.4).color).toBe('green');
    expect(vegetationHealth(0.42).color).toBe('yellow');
    expect(vegetationHealth(0.18).color).toBe('red');
  });
});

describe('alerte meteo', () => {
  it('reproduce mesajele cerute', () => {
    const hours = [
      hour('2026-11-18T09:00', { windKmh: 22 }),
      hour('2026-11-18T10:00'),
      hour('2026-11-18T11:00'),
      hour('2026-11-18T12:00', { precipitationMm: 2 }),
    ];
    const messages = evaluateWeatherAlerts('Sud-1', hours).map((a) => a.message);
    expect(messages).toContain('Ploaie estimată în 3 ore pe parcela Sud-1.');
    expect(messages).toContain('Vânt 22 km/h. Erbicidarea nu este recomandată.');
  });

  it('detectează grindina și înghețul', () => {
    const types = evaluateWeatherAlerts('Nord', [
      hour('2026-04-18T03:00', { temperatureC: -2 }),
      hour('2026-04-18T04:00', { weatherCode: 96 }),
    ]).map((a) => a.type);
    expect(types).toEqual(expect.arrayContaining(['HAIL', 'FROST']));
  });
});

describe('Parcel Intelligence', () => {
  const now = new Date('2027-07-16T12:00:00Z');
  const weather = { meanHumidityPct: 60, meanTempC: 20, minTempC: 12, rainyDays: 0, precipitationMm: 15, et0Mm: 20 };

  it('calculează ziua de vegetație și progresul', () => {
    const progress = cropProgress({
      crop: 'CORN',
      sowingDate: new Date('2027-04-12T12:00:00Z'),
      expectedHarvestDate: new Date('2027-09-29T12:00:00Z'),
      now,
    });
    expect(progress).toMatchObject({ status: 'GROWING', vegetationDay: 96, totalDays: 170, percent: 56 });
  });

  it('marchează cultura nesemănată și cea recoltată', () => {
    expect(cropProgress({ crop: 'WHEAT', now }).status).toBe('NOT_SOWN');
    const done = cropProgress({
      crop: 'WHEAT',
      sowingDate: new Date('2026-10-10T00:00:00Z'),
      harvestDate: new Date('2027-07-01T00:00:00Z'),
      now,
    });
    expect(done.status).toBe('HARVESTED');
    expect(done.percent).toBe(100);
  });

  it('calculează indicii de sol', () => {
    const good = soilIndices({ ph: 6.8, organicMatterPct: 4, clayPct: 30, sandPct: 30, waterHoldingMmPerM: 180 });
    const poor = soilIndices({ ph: 4.8, organicMatterPct: 0.8, clayPct: 5, sandPct: 85, waterHoldingMmPerM: 60 });
    expect(good.fertilityIndex).toBe(100);
    expect(good.soilScore).toBeGreaterThan(poor.soilScore);
  });

  it('aplică pragurile de stropire', () => {
    expect(sprayingAlerts([{ windKmh: 15, precipitationProbabilityPct: 60 }])).toEqual([]);
    expect(sprayingAlerts([{ windKmh: 22, precipitationProbabilityPct: 75 }]).map((alert) => alert.message)).toEqual([
      'Vânt 22 km/h. Nu stropi.',
      'Probabilitate de ploaie 75%. Amână stropirea.',
    ]);
  });

  it('recomandă fungicid la umiditate ridicată', () => {
    const action = nextRecommendedAction({
      crop: 'WHEAT',
      progress: { status: 'GROWING', percent: 55 },
      weather: { ...weather, meanHumidityPct: 84, rainyDays: 3 },
      now,
    });
    expect(action).toMatchObject({ code: 'FUNGICIDE', priority: 'HIGH', dueInDays: 4 });
  });

  it('nu repetă tratamentul aplicat recent', () => {
    const action = nextRecommendedAction({
      crop: 'SOY',
      progress: { status: 'GROWING', percent: 55 },
      weather: { ...weather, meanHumidityPct: 84, rainyDays: 3 },
      recentWork: [{ type: 'SPRAYING', date: new Date('2027-07-12T00:00:00Z') }],
      now,
    });
    expect(action.code).toBe('MONITOR');
  });

  it('prioritizează irigarea când solul este uscat', () => {
    const action = nextRecommendedAction({
      crop: 'CORN',
      progress: { status: 'GROWING', percent: 50 },
      weather,
      soilMoisturePct: 22,
      now,
    });
    expect(action.code).toBe('IRRIGATE');
    expect(action.confidencePct).toBe(90);
  });

  it('calculează marja și ROI', () => {
    const economics = parcelEconomics({
      areaHa: 10,
      costs: [
        { category: 'SEED', amount: 10000 },
        { category: 'LABOR', amount: 10000 },
      ],
      expectedYieldTHa: 5,
      expectedPricePerT: 1000,
    });
    expect(economics).toMatchObject({ laborCost: 10000, expectedIncome: 50000, expectedProfit: 30000, profitMarginPct: 60, roiPct: 150 });
  });
});

describe('costuri', () => {
  it('calculează costul pe hectar și profitul', () => {
    const summary = summarizeCosts({
      areaHa: 10,
      costs: [
        { category: 'SEED', amount: 5000 },
        { category: 'FUEL', amount: 3000 },
      ],
      expectedYieldTHa: 6,
      expectedPricePerT: 1000,
      actualYieldT: 55,
      actualPricePerT: 1100,
    });
    expect(summary.costPerHa).toBe(800);
    expect(summary.estimatedProfit).toBe(52000);
    expect(summary.actualProfit).toBe(52500);
  });

  it('lasă profitul necompletat fără date de vânzare', () => {
    const summary = summarizeCosts({ areaHa: 5, costs: [] });
    expect(summary.estimatedProfit).toBeNull();
    expect(summary.actualProfit).toBeNull();
  });
});

describe('traseul utilajului pe parcelă', () => {
  const ring: [number, number][] = [[25, 45], [25.01, 45], [25.01, 45.004], [25, 45.004], [25, 45]];
  const path = buildFieldPath(ring, { workingWidthM: 6 });

  it('pornește de la o margine, trece prin centru și ajunge la capătul opus', () => {
    const start = positionOnPath(path, 0)!;
    const middle = positionOnPath(path, 50)!;
    const end = positionOnPath(path, 100)!;
    expect(start.lat).toBeLessThan(45.0005);
    expect(middle.lng).toBeCloseTo(25.005, 3);
    expect(middle.lat).toBeCloseTo(45.002, 3);
    expect(end.lat).toBeGreaterThan(45.0035);
  });

  it('rămâne în interiorul parcelei', () => {
    for (const percent of [5, 25, 50, 75, 95]) {
      const point = positionOnPath(path, percent)!;
      expect(pointInRing([point.lng, point.lat], ring)).toBe(true);
    }
  });

  it('recunoaște un punct din afara parcelei', () => {
    expect(pointInRing([25.02, 45.002], ring)).toBe(false);
  });
});

describe('planul tehnologic', () => {
  const now = new Date('2026-11-18T10:00:00Z');
  const soil = { ph: 6.8, texture: 'CLAY_LOAM' as const, label: 'Cernoziom lutos-argilos' };
  const climate = { seasonTempC: 20, annualPrecipitationMm: 520 };

  it('generează calendarul porumbului pentru sezonul următor', () => {
    const { steps, seasonYear } = planCalendar('CORN', { now });
    const dateOf = (key: string) => steps.find((step) => step.key === key)!.date.slice(0, 10);
    expect(seasonYear).toBe(2027);
    expect(dateOf('SOIL_PREP')).toBe('2027-03-10');
    expect(dateOf('SEED_PURCHASE')).toBe('2027-03-25');
    expect(dateOf('SOWING')).toBe('2027-04-05');
    expect(dateOf('TOP_DRESSING')).toBe('2027-05-15');
    expect(dateOf('PEST_MONITORING')).toBe('2027-08-01');
    expect(dateOf('HARVEST')).toBe('2027-10-15');
  });

  it('mută recolta culturilor de toamnă în anul următor semănatului', () => {
    const { sowingDate, seasonYear } = planCalendar('WHEAT', { now });
    expect(sowingDate.slice(0, 10)).toBe('2027-10-05');
    expect(seasonYear).toBe(2028);
  });

  it('nu programează pași în trecut la un plan nou', () => {
    const { steps } = planCalendar('CORN', { now: new Date('2027-03-20T10:00:00Z') });
    expect(steps.every((step) => !step.past)).toBe(true);
    expect(steps[0]!.date.slice(0, 10)).toBe('2027-03-20');
  });

  it('nu cere azot fazial la leguminoase', () => {
    expect(planCalendar('SOY', { now }).steps.some((step) => step.key === 'TOP_DRESSING')).toBe(false);
  });

  it('explică scorul prin ultima cultură', () => {
    const ranked = rankCropsForParcel({ year: 2027, history: [{ crop: 'WHEAT', year: 2026 }], soil, climate });
    const soy = ranked.find((item) => item.crop === 'SOY')!;
    expect(soy.reason).toContain('grâu anul trecut');
    expect(ranked.find((item) => item.crop === 'WHEAT')!.score).toBeLessThan(soy.score);
  });

  it('raportează secetă și ciclurile de irigare când apa nu ajunge', () => {
    const plan = buildParcelPlan({ crop: 'CORN', parcelName: 'Sud-12', areaHa: 42.7, history: [{ crop: 'WHEAT', year: 2026 }], soil, climate, now });
    expect(plan.irrigation.deficitMm).toBeGreaterThan(0);
    expect(plan.report.risks[0]).toContain('iulie-august');
    expect(plan.report.recommendation).toContain('cicluri de irigare');
    expect(plan.report.lastCropName).toBe('Grâu');
  });

  it('marchează datele lipsă în loc să inventeze valori', () => {
    const plan = buildParcelPlan({ crop: 'SOY', parcelName: 'Nord', areaHa: 5, history: [], soil: null, climate: null, now });
    expect(plan.irrigation.deficitMm).toBeNull();
    expect(plan.report.missingData).toEqual(['sol', 'climă']);
  });

  it('stabilește anul agricol următor', () => {
    expect(nextSeasonYear(new Date('2026-11-18T10:00:00Z'))).toBe(2027);
    expect(nextSeasonYear(new Date('2026-03-01T10:00:00Z'))).toBe(2026);
  });
});

describe('catalog agricol', () => {
  const tractors = TRACTOR_CATALOG.map((item) => ({ id: item.id, name: `${item.brand} ${item.model}`, horsePower: item.horsePower, fuelConsumptionMin: item.fuelConsumptionMin, fuelConsumptionMax: item.fuelConsumptionMax, owned: true }));

  it('conține produsele cerute cu datele furnizate', () => {
    const seed = SEED_CATALOG.find((item) => item.name === 'Pioneer P0710')!;
    expect(seed).toMatchObject({ fao: 410, vegetationDays: 160, droughtTolerance: 'HIGH', expectedYieldMin: 10, expectedYieldMax: 16 });
    expect(SEED_CATALOG).toHaveLength(23);
    expect(TRACTOR_CATALOG).toHaveLength(18);
  });

  it('nu inventează date lipsă și marchează consumul estimat', () => {
    expect(SEED_CATALOG.find((item) => item.name === 'Glosa')!.fao).toBeNull();
    const fendt = TRACTOR_CATALOG.find((item) => item.model === '942 Vario')!;
    expect(fendt.fuelEstimated).toBe(true);
    expect(fendt.tankCapacity).toBeNull();
    expect(TRACTOR_CATALOG.find((item) => item.model === '8R 340')!.fuelEstimated).toBe(false);
  });

  it('calculează costul din exemplu', () => {
    expect(fuelLitersPerHour(35, 55)).toBe(42);
    expect(workCost({ fuelLPerH: 42, durationHours: 5, fuelPricePerL: 7.2, areaHa: 40 })).toMatchObject({ fuelLiters: 210, fuelCost: 1512 });
  });

  it('recomandă un tractor suficient de puternic pentru lucrare', () => {
    const result = recommendMachine({ areaHa: 42.7, operation: 'PLOWING', tractors })!;
    expect(result.workingWidthM * 90).toBeLessThanOrEqual(tractors.find((item) => item.id === result.tractorId)!.horsePower);
    expect(result.durationHours).toBeGreaterThan(0);
    expect(result.fuelCost).toBeGreaterThan(0);
  });

  it('preferă tractoarele fermei și respinge puterea insuficientă', () => {
    const small = { id: 'mic', name: 'Mic', horsePower: 100, owned: true };
    const big = { id: 'mare', name: 'Mare', horsePower: 400, owned: false };
    expect(recommendMachine({ areaHa: 10, operation: 'SOWING', tractors: [small, big] })!.tractorId).toBe('mic');
    expect(recommendMachine({ areaHa: 10, operation: 'PLOWING', tractors: [{ ...small, horsePower: 50 }] })).toBeNull();
    expect(recommendMachine({ areaHa: 10, operation: 'HARVESTING', tractors })).toBeNull();
  });

  it('reduce puterea disponibilă pe pantă', () => {
    const tractor = [{ id: 't', name: 'T', horsePower: 300, owned: true }];
    const flat = recommendMachine({ areaHa: 10, operation: 'PLOWING', tractors: tractor })!;
    const steep = recommendMachine({ areaHa: 10, operation: 'PLOWING', tractors: tractor, slopePct: 15 })!;
    expect(steep.workingWidthM).toBeLessThan(flat.workingWidthM);
  });

  it('ordonează hibrizii și explică scorul', () => {
    const ranked = rankSeedsForParcel({
      seeds: SEED_CATALOG,
      crop: 'CORN',
      year: 2027,
      history: [{ crop: 'WHEAT', year: 2026 }],
      soil: { ph: 6.8, texture: 'CLAY_LOAM' },
      climate: { seasonTempC: 20, annualPrecipitationMm: 450, gddBase10: 1500 },
    });
    expect(ranked).toHaveLength(4);
    expect(ranked[0]!.seed.name).toBe('Pioneer P0710');
    expect(ranked[0]!.reason).toContain('tolerează bine seceta');
    expect(ranked[1]!.missingData).toContain('toleranța la secetă');
  });

  it('filtrează și sortează produsele', () => {
    const late = filterSeeds(SEED_CATALOG, { crop: 'CORN', faoMin: 400 }).map((item) => item.fao);
    expect(late).toHaveLength(2);
    expect(late).toEqual(expect.arrayContaining([410, 420]));
    expect(filterSeeds(SEED_CATALOG, { search: 'dekalb', crop: 'CORN' })).toHaveLength(1);
    expect(filterSeeds(SEED_CATALOG, { crop: 'CORN', sort: 'fao' })[0]!.fao).toBe(380);
  });

  it('importă rânduri CSV și raportează erorile', () => {
    const rows = parseCsv('Nume;Cultura;FAO;Toleranta\n"Hibrid; nou";Porumb;350;Ridicata\nFără cultură;;;\n');
    expect(seedFromRow(rows[0]!)).toMatchObject({ name: 'Hibrid; nou', crop: 'CORN', fao: 350, droughtTolerance: 'HIGH', type: 'HYBRID' });
    expect(seedFromRow(rows[1]!)).toContain('cultura nu este recunoscută');
    expect(tractorFromRow({ Marca: 'Zetor', Model: 'Forterra', CP: '140' })).toMatchObject({ brand: 'Zetor', horsePower: 140, fuelEstimated: true });
    expect(tractorFromRow({ Marca: 'Zetor', Model: 'X' })).toContain('puterea');
  });
});
