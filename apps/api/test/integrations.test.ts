import { evaluateWeatherAlerts } from '@fermapp/domain';
import { describe, expect, it } from 'vitest';
import { getPowerDaily, summarizeClimateYears } from '../src/integrations/nasaPower';
import { expandOwmForecast } from '../src/integrations/openWeather';
import { parseStatistics } from '../src/integrations/sentinel';
import { parseSoilGrids } from '../src/integrations/soilGrids';

describe('OpenWeatherMap', () => {
  const now = Date.UTC(2026, 5, 1, 9, 20);
  const entry = (hour: number, rainMm = 0, id = 800) => ({
    dt: Date.UTC(2026, 5, 1, hour) / 1000,
    main: { temp: 20, humidity: 60 },
    wind: { speed: 5 },
    pop: rainMm ? 0.8 : 0,
    rain: rainMm ? { '3h': rainMm } : undefined,
    weather: [{ id }],
  });

  it('converts 3-hour steps into local hours, starting from the current hour', () => {
    const hours = expandOwmForecast([entry(12, 6)], 3 * 3600, now);
    expect(hours[0]!.time).toBe('2026-06-01T12:00');
    expect(hours[3]!.time).toBe('2026-06-01T15:00');
    expect(hours[3]!.precipitationMm).toBe(2);
    expect(hours[0]!.windKmh).toBe(18);
  });

  it('keeps the correct number of hours until rain', () => {
    const alerts = evaluateWeatherAlerts('South-1', expandOwmForecast([entry(12, 6)], 0, now));
    expect(alerts.find((alert) => alert.type === 'RAIN')?.message).toBe('Rain expected in 3 hours on parcel South-1.');
  });

  it('recognizes the thunderstorm', () => {
    const hours = expandOwmForecast([entry(9, 0, 211)], 0, now);
    expect(evaluateWeatherAlerts('North', hours).map((alert) => alert.type)).toContain('STORM');
  });
});

describe('Sentinel', () => {
  const stats = (mean: number | string, noDataCount: number) => ({
    bands: { B0: { stats: { mean, sampleCount: 100, noDataCount } } },
  });

  it('ignores days covered by clouds or without data', () => {
    const result = parseStatistics({
      data: [
        { interval: { from: '2026-06-01T00:00:00Z' }, outputs: { ndvi: stats(0.7123, 10), ndre: stats(0.31, 10) } },
        { interval: { from: '2026-06-06T00:00:00Z' }, outputs: { ndvi: stats(0.5, 80), ndre: stats(0.2, 80) } },
        { interval: { from: '2026-06-11T00:00:00Z' }, outputs: { ndvi: stats('NaN', 100), ndre: stats('NaN', 100) } },
      ],
    });
    expect(result).toEqual([{ date: '2026-06-01', ndvi: 0.712, ndre: 0.31, cloudPct: 10 }]);
  });
});

describe('SoilGrids', () => {
  it('returns null for points without data', () => {
    const layer = (name: string) => ({
      name,
      unit_measure: { d_factor: 10 },
      depths: [{ label: '0-5cm', values: { mean: null } }],
    });
    expect(parseSoilGrids({ properties: { layers: ['phh2o', 'clay', 'sand', 'soc'].map(layer) } })).toBeNull();
  });
});

describe('NASA POWER', () => {
  it('aggregates agricultural years', () => {
    const years = summarizeClimateYears([
      { date: '2024-07-01', tMeanC: 25, tMaxC: 32, tMinC: 18, precipitationMm: 4, radiationMjM2: 25 },
      { date: '2024-12-01', tMeanC: 1, tMaxC: 5, tMinC: -3, precipitationMm: 2, radiationMjM2: 5 },
    ]);
    expect(years[0]).toMatchObject({ year: 2024, precipitationMm: 6, seasonPrecipitationMm: 4, seasonTempC: 25, gddBase10: 14 });
    expect(getPowerDaily).toBeTypeOf('function');
  });
});
