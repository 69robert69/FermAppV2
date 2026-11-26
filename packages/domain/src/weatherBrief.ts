import type { ForecastHour } from './alerts';
import { SPRAY_WIND_LIMIT_KMH } from './intelligence';

/**
 * The weather summary shown on the map: current conditions, a five-day outlook,
 * farming advice and alerts, all derived from an hourly forecast in local parcel time.
 */

export type ConditionKind =
  | 'CLEAR'
  | 'PARTLY_CLOUDY'
  | 'CLOUDY'
  | 'FOG'
  | 'DRIZZLE'
  | 'RAIN'
  | 'HEAVY_RAIN'
  | 'SNOW'
  | 'SLEET'
  | 'STORM'
  | 'HAIL';

export interface WeatherCondition {
  kind: ConditionKind;
  label: string;
}

/** WMO weather interpretation codes, as returned by Open-Meteo. */
export function weatherCondition(code: number | null | undefined): WeatherCondition {
  switch (code) {
    case 0: return { kind: 'CLEAR', label: 'Clear' };
    case 1: return { kind: 'CLEAR', label: 'Mainly clear' };
    case 2: return { kind: 'PARTLY_CLOUDY', label: 'Partly cloudy' };
    case 3: return { kind: 'CLOUDY', label: 'Overcast' };
    case 45: case 48: return { kind: 'FOG', label: 'Fog' };
    case 51: case 53: case 55: return { kind: 'DRIZZLE', label: 'Drizzle' };
    case 56: case 57: return { kind: 'SLEET', label: 'Freezing drizzle' };
    case 61: return { kind: 'RAIN', label: 'Light rain' };
    case 63: return { kind: 'RAIN', label: 'Rain' };
    case 65: return { kind: 'HEAVY_RAIN', label: 'Heavy rain' };
    case 66: case 67: return { kind: 'SLEET', label: 'Freezing rain' };
    case 71: case 73: case 75: case 77: return { kind: 'SNOW', label: 'Snow' };
    case 80: case 81: return { kind: 'RAIN', label: 'Showers' };
    case 82: return { kind: 'HEAVY_RAIN', label: 'Violent showers' };
    case 85: case 86: return { kind: 'SNOW', label: 'Snow showers' };
    case 95: return { kind: 'STORM', label: 'Thunderstorm' };
    case 96: case 99: return { kind: 'HAIL', label: 'Thunderstorm with hail' };
    default: return { kind: 'CLOUDY', label: 'Cloudy' };
  }
}

export interface BriefHour extends ForecastHour {
  gustKmh?: number | null;
  /** Reference evapotranspiration (FAO-56) for the hour. */
  et0Mm?: number | null;
  radiationWm2?: number | null;
}

export interface BriefDay {
  date: string;
  tMaxC: number;
  tMinC: number;
  precipitationMm: number;
}

/** Measured conditions, which are more precise than the forecast for the current hour. */
export interface BriefCurrent {
  time: string;
  temperatureC: number;
  humidityPct: number;
  windKmh: number;
  gustKmh?: number | null;
  weatherCode?: number | null;
  isDay?: boolean | null;
}

export interface DayOutlook {
  date: string;
  /** Short weekday name, for example "Mon". */
  weekday: string;
  tMaxC: number;
  tMinC: number;
  precipitationMm: number;
  rainProbabilityPct: number | null;
  condition: WeatherCondition;
}

export type InsightTone = 'GOOD' | 'INFO' | 'CAUTION' | 'WARNING';

export interface FarmInsight {
  code: 'SPRAY_WIND' | 'RAIN_SOON' | 'SPRAY_GOOD' | 'SPRAY_HOT' | 'SPRAY_COLD' | 'SPRAY_DRY_AIR' | 'WET_SOIL' | 'SOIL_DRYING' | 'STABLE';
  tone: InsightTone;
  message: string;
}

export type FieldAlertType = 'STORM' | 'FROST' | 'WIND' | 'HEAT' | 'HEAVY_RAIN';

export interface FieldAlert {
  type: FieldAlertType;
  severity: 'WARNING' | 'SEVERE';
  title: string;
  message: string;
  startsAt: string;
}

export interface WeatherBrief {
  current: {
    time: string;
    temperatureC: number;
    humidityPct: number;
    windKmh: number;
    gustKmh: number | null;
    precipitationProbabilityPct: number | null;
    condition: WeatherCondition;
    isDay: boolean;
  };
  today: { tMinC: number; tMaxC: number; rainProbabilityPct: number | null } | null;
  days: DayOutlook[];
  insights: FarmInsight[];
  alerts: FieldAlert[];
}

export interface WeatherBriefInput {
  /** Hourly forecast in local time; may include past hours. */
  hours: readonly BriefHour[];
  days: readonly BriefDay[];
  current?: BriefCurrent | null;
  /** The current local hour, YYYY-MM-DDTHH:00. */
  now: string;
  /** Number of days in the outlook, starting tomorrow. */
  outlookDays?: number;
}

// Thresholds. Spraying limits follow the work-window rules (windows.ts).
const SPRAY_MAX_RAIN_PCT = 30;
const SPRAY_TEMP_C: readonly [number, number] = [8, 25];
const SPRAY_MIN_HUMIDITY_PCT = 45;
const RAIN_HOUR_MM = 0.5;
const RAIN_LIKELY_PCT = 60;
const WET_SOIL_MM = 10;
const DRYING_MAX_RAIN_MM = 1;
const DRYING_ET0_MM = 2.5;
const FROST_C = 0;
const HARD_FROST_C = -2;
const HEAT_C = 32;
const EXTREME_HEAT_C = 35;
const WIND_SUSTAINED_KMH = 40;
const WIND_GUST_KMH = 60;
const WIND_GUST_SEVERE_KMH = 80;
const HEAVY_RAIN_HOUR_MM = 7.6;
const HEAVY_RAIN_DAY_MM = 20;
const HEAVY_RAIN_SEVERE_MM = 40;
const MAX_INSIGHTS = 3;

const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

const weekdayOf = (date: string) => {
  const [year, month, day] = date.split('-').map(Number);
  return WEEKDAYS[new Date(Date.UTC(year!, month! - 1, day!)).getUTCDay()]!;
};

const nextDate = (date: string) => {
  const [year, month, day] = date.split('-').map(Number);
  return new Date(Date.UTC(year!, month! - 1, day! + 1)).toISOString().slice(0, 10);
};

/** "now", "today at 15:00", "tomorrow at 03:00" or "on Thu". */
export function relativeTime(time: string, now: string): string {
  if (time.slice(0, 13) <= now.slice(0, 13)) return 'now';
  const date = time.slice(0, 10);
  const clock = time.slice(11, 16);
  const today = now.slice(0, 10);
  if (date === today) return `today at ${clock}`;
  if (date === nextDate(today)) return `tomorrow at ${clock}`;
  return `on ${weekdayOf(date)}`;
}

/** Codes are ordered by severity, so the day's icon shows its worst daytime weather. */
const DAYTIME = (time: string) => {
  const hour = Number(time.slice(11, 13));
  return hour >= 6 && hour <= 21;
};

function dailyCode(hours: readonly BriefHour[]): number | null {
  const daytime = hours.filter((hour) => DAYTIME(hour.time) && hour.weatherCode != null);
  const pool = daytime.length ? daytime : hours.filter((hour) => hour.weatherCode != null);
  if (!pool.length) return null;
  // A single stormy hour should not paint the whole day: storms need two hours unless nothing else is wet.
  const codes = pool.map((hour) => hour.weatherCode!);
  const worst = Math.max(...codes);
  if (worst >= 95 && codes.filter((code) => code >= 95).length < 2) {
    const rest = codes.filter((code) => code < 95);
    return rest.length ? Math.max(...rest) : worst;
  }
  return worst;
}

const maxOf = (values: readonly number[]) => (values.length ? Math.max(...values) : null);
const sum = (values: readonly number[]) => values.reduce((total, value) => total + value, 0);
const round = (value: number) => Math.round(value);

function buildInsights(upcoming: readonly BriefHour[], past24: readonly BriefHour[], current: WeatherBrief['current']): FarmInsight[] {
  const insights: FarmInsight[] = [];
  const next6 = upcoming.slice(0, 6);
  const next24 = upcoming.slice(0, 24);
  const next48 = upcoming.slice(0, 48);

  const windy = current.windKmh > SPRAY_WIND_LIMIT_KMH;
  if (windy) {
    insights.push({
      code: 'SPRAY_WIND',
      tone: 'WARNING',
      message: `Strong winds detected (${round(current.windKmh)} km/h). Spraying not recommended.`,
    });
  }

  const rainIndex = next24.findIndex((hour) => hour.precipitationMm >= RAIN_HOUR_MM || (hour.precipitationProbabilityPct ?? 0) >= RAIN_LIKELY_PCT);
  if (rainIndex >= 0) {
    const rainTotal = sum(next24.map((hour) => hour.precipitationMm));
    const amount = rainTotal >= 1 ? ` (about ${round(rainTotal)} mm)` : '';
    insights.push({
      code: 'RAIN_SOON',
      tone: 'INFO',
      message: rainIndex <= 1
        ? `Rain expected within the hour${amount}.`
        : rainIndex < 6
          ? `Rain expected in the next ${rainIndex} hours${amount}. Postpone spraying and fertilizer spreading.`
          : `Rain expected within 24 hours${amount}.`,
    });
  }

  const rainRiskSoon = next6.some((hour) => hour.precipitationMm > 0 || (hour.precipitationProbabilityPct ?? 0) > SPRAY_MAX_RAIN_PCT);
  if (!windy && !rainRiskSoon) {
    if (current.temperatureC > SPRAY_TEMP_C[1]) {
      insights.push({ code: 'SPRAY_HOT', tone: 'CAUTION', message: 'Too warm for effective spraying now. Prefer early morning or evening.' });
    } else if (current.temperatureC < SPRAY_TEMP_C[0]) {
      insights.push({ code: 'SPRAY_COLD', tone: 'CAUTION', message: 'Too cold for most crop protection products to work well.' });
    } else if (current.humidityPct < SPRAY_MIN_HUMIDITY_PCT) {
      insights.push({ code: 'SPRAY_DRY_AIR', tone: 'CAUTION', message: 'Dry air: high drift and evaporation risk when spraying.' });
    } else {
      insights.push({ code: 'SPRAY_GOOD', tone: 'GOOD', message: 'Low rain probability. Good conditions for spraying.' });
    }
  }

  const recentRain = sum(past24.map((hour) => hour.precipitationMm));
  const rainAhead = sum(next48.map((hour) => hour.precipitationMm));
  const et0 = next24.some((hour) => hour.et0Mm != null) ? sum(next24.map((hour) => hour.et0Mm ?? 0)) : null;
  const meanHumidity = next24.length ? sum(next24.map((hour) => hour.humidityPct)) / next24.length : 100;
  const meanTemp = next24.length ? sum(next24.map((hour) => hour.temperatureC)) / next24.length : 0;
  const drying = rainAhead < DRYING_MAX_RAIN_MM && (et0 != null ? et0 >= DRYING_ET0_MM : meanHumidity < 65 && meanTemp >= 12);

  if (recentRain >= WET_SOIL_MM && !drying) {
    insights.push({
      code: 'WET_SOIL',
      tone: 'CAUTION',
      message: `${round(recentRain)} mm fell in the last 24 hours. Soils are likely wet; delay heavy machinery.`,
    });
  } else if (drying) {
    insights.push({ code: 'SOIL_DRYING', tone: 'GOOD', message: 'Good soil drying conditions.' });
  }

  if (!insights.length) insights.push({ code: 'STABLE', tone: 'INFO', message: 'Stable conditions. No weather constraints for field work.' });
  return insights.slice(0, MAX_INSIGHTS);
}

function buildAlerts(upcoming: readonly BriefHour[], now: string): FieldAlert[] {
  const alerts: FieldAlert[] = [];
  const next24 = upcoming.slice(0, 24);
  const next48 = upcoming.slice(0, 48);
  const next72 = upcoming.slice(0, 72);
  const when = (hour: BriefHour) => relativeTime(hour.time, now);

  const storm = next48.find((hour) => hour.weatherCode != null && hour.weatherCode >= 95);
  if (storm) {
    const hail = next48.some((hour) => hour.weatherCode === 96 || hour.weatherCode === 99);
    alerts.push({
      type: 'STORM',
      severity: 'SEVERE',
      title: hail ? 'Storm and hail' : 'Storm warning',
      message: `${hail ? 'Thunderstorm with hail' : 'Thunderstorm'} expected ${when(storm)}.`,
      startsAt: storm.time,
    });
  }

  const coldest = next48.reduce<BriefHour | null>((low, hour) => (!low || hour.temperatureC < low.temperatureC ? hour : low), null);
  if (coldest && coldest.temperatureC <= FROST_C) {
    const first = next48.find((hour) => hour.temperatureC <= FROST_C)!;
    alerts.push({
      type: 'FROST',
      severity: coldest.temperatureC <= HARD_FROST_C ? 'SEVERE' : 'WARNING',
      title: 'Frost warning',
      message: `Frost ${when(first)}, down to ${round(coldest.temperatureC)} °C.`,
      startsAt: first.time,
    });
  }

  const windy = next24.find((hour) => hour.windKmh >= WIND_SUSTAINED_KMH || (hour.gustKmh ?? 0) >= WIND_GUST_KMH);
  if (windy) {
    const peakGust = maxOf(next24.map((hour) => hour.gustKmh ?? hour.windKmh)) ?? windy.windKmh;
    alerts.push({
      type: 'WIND',
      severity: peakGust >= WIND_GUST_SEVERE_KMH ? 'SEVERE' : 'WARNING',
      title: 'Strong wind',
      message: `Gusts up to ${round(peakGust)} km/h ${when(windy)}.`,
      startsAt: windy.time,
    });
  }

  const hottest = next72.reduce<BriefHour | null>((high, hour) => (!high || hour.temperatureC > high.temperatureC ? hour : high), null);
  if (hottest && hottest.temperatureC >= HEAT_C) {
    const first = next72.find((hour) => hour.temperatureC >= HEAT_C)!;
    alerts.push({
      type: 'HEAT',
      severity: hottest.temperatureC >= EXTREME_HEAT_C ? 'SEVERE' : 'WARNING',
      title: 'Heat stress',
      message: `Up to ${round(hottest.temperatureC)} °C ${when(first)}. Risk of crop heat stress.`,
      startsAt: first.time,
    });
  }

  // The heaviest rolling 24-hour total in the next two days, or a single intense hour.
  let peakTotal = 0;
  let peakStart: BriefHour | null = null;
  for (let i = 0; i < next48.length; i++) {
    const total = sum(next48.slice(i, i + 24).map((hour) => hour.precipitationMm));
    if (total > peakTotal) {
      peakTotal = total;
      peakStart = next48.slice(i).find((hour) => hour.precipitationMm > 0) ?? next48[i]!;
    }
  }
  const intense = next48.find((hour) => hour.precipitationMm >= HEAVY_RAIN_HOUR_MM);
  if (peakTotal >= HEAVY_RAIN_DAY_MM || intense) {
    const start = intense && (!peakStart || intense.time < peakStart.time) ? intense : peakStart!;
    alerts.push({
      type: 'HEAVY_RAIN',
      severity: peakTotal >= HEAVY_RAIN_SEVERE_MM ? 'SEVERE' : 'WARNING',
      title: 'Heavy rain',
      message: `About ${round(peakTotal)} mm expected ${when(start)}. Risk of waterlogging and erosion.`,
      startsAt: start.time,
    });
  }

  return alerts.sort((a, b) => (a.severity === b.severity ? a.startsAt.localeCompare(b.startsAt) : a.severity === 'SEVERE' ? -1 : 1));
}

export function buildWeatherBrief(input: WeatherBriefInput): WeatherBrief | null {
  const nowHour = input.now.slice(0, 13);
  const upcoming = input.hours.filter((hour) => hour.time.slice(0, 13) >= nowHour);
  const first = upcoming[0];
  if (!first) return null;
  const past24 = input.hours.filter((hour) => hour.time.slice(0, 13) < nowHour).slice(-24);
  const live = input.current;
  const today = input.now.slice(0, 10);

  const hoursByDate = new Map<string, BriefHour[]>();
  for (const hour of input.hours) {
    const date = hour.time.slice(0, 10);
    const list = hoursByDate.get(date) ?? [];
    list.push(hour);
    hoursByDate.set(date, list);
  }
  const rainChance = (date: string) => maxOf((hoursByDate.get(date) ?? []).map((hour) => hour.precipitationProbabilityPct ?? 0));

  const isDay = live?.isDay ?? (first.radiationWm2 != null ? first.radiationWm2 > 0 : DAYTIME(first.time));
  const current: WeatherBrief['current'] = {
    time: live?.time ?? first.time,
    temperatureC: live?.temperatureC ?? first.temperatureC,
    humidityPct: live?.humidityPct ?? first.humidityPct,
    windKmh: live?.windKmh ?? first.windKmh,
    gustKmh: live?.gustKmh ?? first.gustKmh ?? null,
    precipitationProbabilityPct: first.precipitationProbabilityPct ?? null,
    condition: weatherCondition(live?.weatherCode ?? first.weatherCode),
    isDay,
  };

  const todayRow = input.days.find((day) => day.date === today);
  const todayHours = hoursByDate.get(today) ?? [];
  const todaySummary = todayRow
    ? { tMinC: todayRow.tMinC, tMaxC: todayRow.tMaxC, rainProbabilityPct: rainChance(today) }
    : todayHours.length
      ? {
          tMinC: Math.min(...todayHours.map((hour) => hour.temperatureC)),
          tMaxC: Math.max(...todayHours.map((hour) => hour.temperatureC)),
          rainProbabilityPct: rainChance(today),
        }
      : null;

  const days = input.days
    .filter((day) => day.date > today)
    .slice(0, input.outlookDays ?? 5)
    .map((day): DayOutlook => ({
      date: day.date,
      weekday: weekdayOf(day.date),
      tMaxC: day.tMaxC,
      tMinC: day.tMinC,
      precipitationMm: day.precipitationMm,
      rainProbabilityPct: rainChance(day.date),
      condition: weatherCondition(dailyCode(hoursByDate.get(day.date) ?? [])),
    }));

  // Measured conditions replace the first forecast hour, so advice matches what the farmer sees outside.
  const adviceHours = live ? [{ ...first, temperatureC: current.temperatureC, humidityPct: current.humidityPct, windKmh: current.windKmh }, ...upcoming.slice(1)] : upcoming;

  return {
    current,
    today: todaySummary,
    days,
    insights: buildInsights(adviceHours, past24, current),
    alerts: buildAlerts(adviceHours, input.now),
  };
}
