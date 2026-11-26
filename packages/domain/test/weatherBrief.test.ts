import { describe, expect, it } from 'vitest';
import { buildWeatherBrief, relativeTime, weatherCondition, type BriefDay, type BriefHour } from '../src';

const NOW = '2026-06-10T10:00';

/** Hours from 24 h before NOW to 5 days after, with calm, mild weather unless overridden. */
function series(override: (hour: BriefHour, offset: number) => Partial<BriefHour> = () => ({})): BriefHour[] {
  const start = Date.UTC(2026, 5, 9, 10);
  return Array.from({ length: 24 * 7 }, (_, i) => {
    const time = new Date(start + i * 3_600_000).toISOString().slice(0, 16);
    const base: BriefHour = {
      time,
      temperatureC: 18,
      humidityPct: 60,
      precipitationMm: 0,
      precipitationProbabilityPct: 5,
      windKmh: 8,
      gustKmh: 15,
      weatherCode: 1,
      et0Mm: 0.05,
    };
    return { ...base, ...override(base, i - 24) };
  });
}

const days: BriefDay[] = ['2026-06-10', '2026-06-11', '2026-06-12', '2026-06-13', '2026-06-14', '2026-06-15'].map((date) => ({
  date,
  tMaxC: 24,
  tMinC: 11,
  precipitationMm: 0,
}));

describe('weather conditions', () => {
  it('maps WMO codes', () => {
    expect(weatherCondition(0).kind).toBe('CLEAR');
    expect(weatherCondition(65).kind).toBe('HEAVY_RAIN');
    expect(weatherCondition(96).kind).toBe('HAIL');
    expect(weatherCondition(null).label).toBe('Cloudy');
  });

  it('describes times relative to now', () => {
    expect(relativeTime('2026-06-10T10:00', NOW)).toBe('now');
    expect(relativeTime('2026-06-10T15:00', NOW)).toBe('today at 15:00');
    expect(relativeTime('2026-06-11T03:00', NOW)).toBe('tomorrow at 03:00');
    expect(relativeTime('2026-06-13T12:00', NOW)).toBe('on Sat');
  });
});

describe('weather brief', () => {
  it('summarises today and the next five days', () => {
    const brief = buildWeatherBrief({ hours: series(), days, now: NOW })!;
    expect(brief.current.temperatureC).toBe(18);
    expect(brief.today).toMatchObject({ tMinC: 11, tMaxC: 24 });
    expect(brief.days.map((day) => day.weekday)).toEqual(['Thu', 'Fri', 'Sat', 'Sun', 'Mon']);
    expect(brief.alerts).toEqual([]);
  });

  it('recommends spraying in calm, dry weather', () => {
    const brief = buildWeatherBrief({ hours: series(), days, now: NOW })!;
    expect(brief.insights.map((item) => item.code)).toContain('SPRAY_GOOD');
    expect(brief.insights[0]!.message).toBe('Low rain probability. Good conditions for spraying.');
  });

  it('advises against spraying in strong wind', () => {
    const brief = buildWeatherBrief({
      hours: series(),
      days,
      now: NOW,
      current: { time: NOW, temperatureC: 18, humidityPct: 60, windKmh: 24, gustKmh: 35, weatherCode: 2, isDay: true },
    })!;
    expect(brief.insights[0]).toMatchObject({ code: 'SPRAY_WIND', tone: 'WARNING' });
    expect(brief.insights.some((item) => item.code === 'SPRAY_GOOD')).toBe(false);
  });

  it('announces rain within 24 hours', () => {
    const brief = buildWeatherBrief({ hours: series((_, offset) => (offset === 12 ? { precipitationMm: 3, precipitationProbabilityPct: 80, weatherCode: 63 } : {})), days, now: NOW })!;
    expect(brief.insights.find((item) => item.code === 'RAIN_SOON')?.message).toBe('Rain expected within 24 hours (about 3 mm).');
  });

  it('flags wet soils after heavy rain', () => {
    const brief = buildWeatherBrief({ hours: series((_, offset) => (offset < 0 && offset >= -6 ? { precipitationMm: 3 } : { humidityPct: 90, et0Mm: 0.02 })), days, now: NOW })!;
    expect(brief.insights.some((item) => item.code === 'WET_SOIL')).toBe(true);
  });

  it('reports good soil drying conditions', () => {
    const brief = buildWeatherBrief({ hours: series(() => ({ et0Mm: 0.2 })), days, now: NOW })!;
    expect(brief.insights.some((item) => item.code === 'SOIL_DRYING')).toBe(true);
  });

  it('raises storm, frost, wind, heat and heavy rain alerts', () => {
    const storm = buildWeatherBrief({ hours: series((_, offset) => (offset === 30 ? { weatherCode: 95 } : {})), days, now: NOW })!;
    expect(storm.alerts[0]).toMatchObject({ type: 'STORM', severity: 'SEVERE', message: 'Thunderstorm expected tomorrow at 16:00.' });

    const frost = buildWeatherBrief({ hours: series((_, offset) => (offset === 18 ? { temperatureC: -3 } : {})), days, now: NOW })!;
    expect(frost.alerts[0]).toMatchObject({ type: 'FROST', severity: 'SEVERE' });

    const wind = buildWeatherBrief({ hours: series((_, offset) => (offset === 5 ? { windKmh: 35, gustKmh: 65 } : {})), days, now: NOW })!;
    expect(wind.alerts[0]).toMatchObject({ type: 'WIND', severity: 'WARNING', message: 'Gusts up to 65 km/h today at 15:00.' });

    const heat = buildWeatherBrief({ hours: series((_, offset) => (offset === 50 ? { temperatureC: 36 } : {})), days, now: NOW })!;
    expect(heat.alerts[0]).toMatchObject({ type: 'HEAT', severity: 'SEVERE' });

    const rain = buildWeatherBrief({ hours: series((_, offset) => (offset >= 20 && offset < 26 ? { precipitationMm: 5 } : {})), days, now: NOW })!;
    expect(rain.alerts[0]).toMatchObject({ type: 'HEAVY_RAIN', severity: 'WARNING' });
    expect(rain.alerts[0]!.message).toContain('30 mm');
  });

  it('does not paint a whole day stormy for one thunder hour', () => {
    const brief = buildWeatherBrief({ hours: series((_, offset) => (offset === 28 ? { weatherCode: 95 } : offset === 29 ? { weatherCode: 61 } : {})), days, now: NOW })!;
    expect(brief.days[0]!.condition.kind).toBe('RAIN');
  });
});
