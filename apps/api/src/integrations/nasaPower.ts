import { dailyGdd } from '@fermapp/domain';
import { cached } from '../lib/cache';
import { fetchJson } from '../lib/http';

export interface PowerDay {
  date: string;
  tMeanC: number;
  tMaxC: number;
  tMinC: number;
  precipitationMm: number;
  radiationMjM2: number | null;
}

const FILL_VALUE = -999;
const compact = (date: string) => date.replaceAll('-', '');

export function getPowerDaily(lat: number, lng: number, startDate: string, endDate: string): Promise<PowerDay[]> {
  return cached(`nasa:${lat.toFixed(2)}:${lng.toFixed(2)}:${startDate}:${endDate}`, 604_800, async () => {
    const params = new URLSearchParams({
      parameters: 'T2M,T2M_MAX,T2M_MIN,PRECTOTCORR,ALLSKY_SFC_SW_DWN',
      community: 'AG',
      latitude: lat.toFixed(4),
      longitude: lng.toFixed(4),
      start: compact(startDate),
      end: compact(endDate),
      format: 'JSON',
    });
    const data = await fetchJson<{ properties: { parameter: Record<string, Record<string, number>> } }>(
      `https://power.larc.nasa.gov/api/temporal/daily/point?${params}`,
      { service: 'NASA POWER', timeoutMs: 45_000 },
    );
    const p = data.properties.parameter;
    const value = (name: string, key: string) => {
      const v = p[name]?.[key];
      return v === undefined || v === FILL_VALUE ? null : v;
    };
    return Object.keys(p.T2M ?? {}).flatMap((key): PowerDay[] => {
      const tMeanC = value('T2M', key);
      const tMaxC = value('T2M_MAX', key);
      const tMinC = value('T2M_MIN', key);
      if (tMeanC === null || tMaxC === null || tMinC === null) return [];
      return [
        {
          date: `${key.slice(0, 4)}-${key.slice(4, 6)}-${key.slice(6, 8)}`,
          tMeanC,
          tMaxC,
          tMinC,
          precipitationMm: value('PRECTOTCORR', key) ?? 0,
          radiationMjM2: value('ALLSKY_SFC_SW_DWN', key),
        },
      ];
    });
  });
}

export interface ClimateYear {
  year: number;
  precipitationMm: number;
  seasonPrecipitationMm: number;
  meanTempC: number;
  seasonTempC: number;
  radiationMjM2: number;
  gddBase10: number;
  days: number;
}

const isSeason = (date: string) => {
  const month = Number(date.slice(5, 7));
  return month >= 4 && month <= 9;
};

/** Agregă pe ani calendaristici; sezonul de vegetație este aprilie-septembrie. */
export function summarizeClimateYears(days: readonly PowerDay[]): ClimateYear[] {
  const byYear = new Map<number, PowerDay[]>();
  for (const day of days) {
    const year = Number(day.date.slice(0, 4));
    byYear.set(year, [...(byYear.get(year) ?? []), day]);
  }
  const round = (value: number) => Math.round(value * 10) / 10;
  return [...byYear.entries()]
    .sort(([a], [b]) => a - b)
    .map(([year, items]) => {
      const season = items.filter((day) => isSeason(day.date));
      const sum = (list: PowerDay[], pick: (day: PowerDay) => number) => list.reduce((t, d) => t + pick(d), 0);
      return {
        year,
        precipitationMm: round(sum(items, (d) => d.precipitationMm)),
        seasonPrecipitationMm: round(sum(season, (d) => d.precipitationMm)),
        meanTempC: round(sum(items, (d) => d.tMeanC) / items.length),
        seasonTempC: season.length ? round(sum(season, (d) => d.tMeanC) / season.length) : 0,
        radiationMjM2: round(sum(items, (d) => d.radiationMjM2 ?? 0)),
        gddBase10: Math.round(sum(items, (d) => dailyGdd(d.tMaxC, d.tMinC, 10))),
        days: items.length,
      };
    });
}

/** Media ultimilor ani compleți, folosită la recomandarea culturilor. */
export async function getClimateNormals(lat: number, lng: number, years = 5) {
  const lastYear = new Date().getUTCFullYear() - 1;
  const days = await getPowerDaily(lat, lng, `${lastYear - years + 1}-01-01`, `${lastYear}-12-31`);
  const complete = summarizeClimateYears(days).filter((year) => year.days >= 360);
  if (complete.length === 0) return null;
  const mean = (pick: (year: ClimateYear) => number) =>
    complete.reduce((sum, year) => sum + pick(year), 0) / complete.length;
  return {
    seasonTempC: Math.round(mean((y) => y.seasonTempC) * 10) / 10,
    annualPrecipitationMm: Math.round(mean((y) => y.precipitationMm)),
    gddBase10: Math.round(mean((y) => y.gddBase10)),
  };
}
