import {
  bestWindowMessage,
  evaluateWeatherAlerts,
  findWorkWindows,
  sprayingAlerts,
  type FieldOperation,
  type ForecastHour,
  type WeatherAlertDraft,
} from '@fermapp/domain';
import { fetchJson } from './http';
import { readSnapshot, writeSnapshot } from './outbox';

const FORECAST_TTL_MS = 30 * 60_000;
const HISTORY_TTL_MS = 12 * 3_600_000;
/** Panoul parcelei nu așteaptă mult după prognoză când semnalul este slab. */
const FORECAST_TIMEOUT_MS = 12_000;
const OPERATIONS: FieldOperation[] = ['SPRAYING', 'FERTILIZING', 'SOWING', 'HARVESTING'];

const HOURLY = 'temperature_2m,relative_humidity_2m,precipitation,precipitation_probability,wind_speed_10m,shortwave_radiation,evapotranspiration,et0_fao_evapotranspiration,weather_code';
const DAILY = 'temperature_2m_max,temperature_2m_min,temperature_2m_mean,precipitation_sum,shortwave_radiation_sum,et0_fao_evapotranspiration,wind_speed_10m_max,relative_humidity_2m_mean';

export interface WeatherDay {
  date: string;
  tMaxC: number;
  tMinC: number;
  tMeanC: number | null;
  precipitationMm: number;
  radiationMjM2: number | null;
  et0Mm: number | null;
  windMaxKmh: number | null;
  humidityPct: number | null;
}

interface FullHour extends ForecastHour {
  radiationWm2: number | null;
  evapotranspirationMm: number | null;
  et0Mm: number | null;
}

export interface Forecast {
  timezone: string;
  utcOffsetSeconds: number;
  hours: FullHour[];
  days: WeatherDay[];
}

type Series = Record<string, (number | null)[]> & { time: string[] };

const coord = (value: number) => value.toFixed(2);

function mapDays(daily: Series): WeatherDay[] {
  return daily.time.flatMap((date, i) => {
    const tMaxC = daily.temperature_2m_max?.[i];
    const tMinC = daily.temperature_2m_min?.[i];
    if (tMaxC == null || tMinC == null) return [];
    return [{
      date,
      tMaxC,
      tMinC,
      tMeanC: daily.temperature_2m_mean?.[i] ?? null,
      precipitationMm: daily.precipitation_sum?.[i] ?? 0,
      radiationMjM2: daily.shortwave_radiation_sum?.[i] ?? null,
      et0Mm: daily.et0_fao_evapotranspiration?.[i] ?? null,
      windMaxKmh: daily.wind_speed_10m_max?.[i] ?? null,
      humidityPct: daily.relative_humidity_2m_mean?.[i] ?? null,
    }];
  });
}

/** Ultimul rezultat rămâne disponibil și fără conexiune. */
async function remembered<T>(key: string, ttlMs: number, load: () => Promise<T>) {
  const saved = await readSnapshot<{ savedAt: number; value: T }>(key);
  if (saved && Date.now() - saved.savedAt < ttlMs) return saved.value;
  try {
    const value = await load();
    await writeSnapshot(key, { savedAt: Date.now(), value });
    return value;
  } catch (error) {
    if (saved) return saved.value;
    throw error;
  }
}

export function getForecast(lat: number, lng: number) {
  return remembered<Forecast>(`weather:${coord(lat)}:${coord(lng)}`, FORECAST_TTL_MS, async () => {
    const data = await fetchJson<{ timezone: string; utc_offset_seconds: number; hourly: Series; daily: Series }>(
      `https://api.open-meteo.com/v1/forecast?latitude=${coord(lat)}&longitude=${coord(lng)}&hourly=${HOURLY}&daily=${DAILY}&forecast_days=15&past_days=1&timezone=auto&wind_speed_unit=kmh`,
      FORECAST_TIMEOUT_MS,
    );
    const hours = data.hourly.time.flatMap((time, i): FullHour[] => {
      const temperatureC = data.hourly.temperature_2m?.[i];
      if (temperatureC == null) return [];
      return [{
        time,
        temperatureC,
        humidityPct: data.hourly.relative_humidity_2m?.[i] ?? 0,
        precipitationMm: data.hourly.precipitation?.[i] ?? 0,
        precipitationProbabilityPct: data.hourly.precipitation_probability?.[i] ?? null,
        windKmh: data.hourly.wind_speed_10m?.[i] ?? 0,
        weatherCode: data.hourly.weather_code?.[i] ?? null,
        radiationWm2: data.hourly.shortwave_radiation?.[i] ?? null,
        evapotranspirationMm: data.hourly.evapotranspiration?.[i] ?? null,
        et0Mm: data.hourly.et0_fao_evapotranspiration?.[i] ?? null,
      }];
    });
    return { timezone: data.timezone, utcOffsetSeconds: data.utc_offset_seconds, hours, days: mapDays(data.daily) };
  });
}

export function getHistory(lat: number, lng: number, start: string, end: string) {
  return remembered<WeatherDay[]>(`history:${coord(lat)}:${coord(lng)}:${start}:${end}`, HISTORY_TTL_MS, async () => {
    const data = await fetchJson<{ daily: Series }>(
      `https://archive-api.open-meteo.com/v1/archive?latitude=${coord(lat)}&longitude=${coord(lng)}&start_date=${start}&end_date=${end}&daily=${DAILY}&timezone=auto&wind_speed_unit=kmh`,
    );
    return mapDays(data.daily);
  });
}

const localHour = (forecast: Forecast, now = new Date()) =>
  `${new Date(now.getTime() + forecast.utcOffsetSeconds * 1000).toISOString().slice(0, 13)}:00`;

export const upcomingHours = (forecast: Forecast, now = new Date()) => {
  const current = localHour(forecast, now);
  return forecast.hours.filter((hour) => hour.time >= current);
};

export async function forecastView(lat: number, lng: number, range: string) {
  const forecast = await getForecast(lat, lng);
  const hours = upcomingHours(forecast);
  const today = localHour(forecast).slice(0, 10);
  const days = forecast.days.filter((day) => day.date >= today);
  return {
    timezone: forecast.timezone,
    range,
    current: hours[0] ?? null,
    hours: range === '24h' ? hours.slice(0, 24) : [],
    days: range === '24h' ? [] : days.slice(0, range === '14d' ? 14 : 7),
  };
}

export async function historyView(lat: number, lng: number, range: string) {
  const isoDate = (date: Date) => date.toISOString().slice(0, 10);
  const end = new Date(Date.now() - 2 * 86_400_000);
  const start = new Date(end);
  if (range === '12m') start.setUTCFullYear(start.getUTCFullYear() - 1);
  else start.setUTCDate(start.getUTCDate() - 29);
  const days = await getHistory(lat, lng, isoDate(start), isoDate(end));
  const total = (pick: (day: WeatherDay) => number) => Math.round(days.reduce((sum, day) => sum + pick(day), 0) * 10) / 10;
  return { range, totals: { precipitationMm: total((day) => day.precipitationMm), et0Mm: total((day) => day.et0Mm ?? 0) }, days };
}

export async function workWindows(lat: number, lng: number) {
  const forecast = await getForecast(lat, lng);
  const from = localHour(forecast);
  const horizon = upcomingHours(forecast)[168]?.time;
  const hours = forecast.hours.filter((hour) => !horizon || hour.time < horizon);
  return OPERATIONS.map((operation) => {
    const windows = findWorkWindows(operation, hours, 2, from);
    return { operation, message: bestWindowMessage(operation, windows), windows: windows.slice(0, 5) };
  });
}

/** Secțiunea meteo din panoul parcelei. */
export async function parcelWeather(lat: number, lng: number) {
  const forecast = await getForecast(lat, lng);
  const hours = upcomingHours(forecast);
  const today = localHour(forecast).slice(0, 10);
  const rainChance = new Map<string, number>();
  for (const hour of forecast.hours) {
    const date = hour.time.slice(0, 10);
    rainChance.set(date, Math.max(rainChance.get(date) ?? 0, hour.precipitationProbabilityPct ?? 0));
  }
  const days = forecast.days.filter((day) => day.date >= today).map((day) => ({ ...day, rainProbabilityPct: rainChance.get(day.date) ?? null }));
  return {
    timezone: forecast.timezone,
    current: hours[0] ?? null,
    today: days[0] ?? null,
    next7: days.slice(1, 8),
    next14: days.slice(1, 15),
    alerts: sprayingAlerts(hours.slice(0, 6)),
  };
}

export async function parcelAlerts(name: string, lat: number, lng: number): Promise<WeatherAlertDraft[]> {
  return evaluateWeatherAlerts(name, upcomingHours(await getForecast(lat, lng)));
}
