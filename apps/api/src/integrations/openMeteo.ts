import type { ForecastHour } from '@fermapp/domain';
import { cached } from '../lib/cache';
import { fetchJson } from '../lib/http';

const SERVICE = 'Open-Meteo';
const coord = (value: number) => value.toFixed(2);

export interface ForecastHourFull extends ForecastHour {
  radiationWm2: number | null;
  evapotranspirationMm: number | null;
  et0Mm: number | null;
}

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

export interface Forecast {
  timezone: string;
  utcOffsetSeconds: number;
  hours: ForecastHourFull[];
  days: WeatherDay[];
}

const HOURLY = [
  'temperature_2m',
  'relative_humidity_2m',
  'precipitation',
  'precipitation_probability',
  'wind_speed_10m',
  'shortwave_radiation',
  'evapotranspiration',
  'et0_fao_evapotranspiration',
  'weather_code',
].join(',');

const DAILY = [
  'temperature_2m_max',
  'temperature_2m_min',
  'temperature_2m_mean',
  'precipitation_sum',
  'shortwave_radiation_sum',
  'et0_fao_evapotranspiration',
  'wind_speed_10m_max',
  'relative_humidity_2m_mean',
].join(',');

type Series = Record<string, (number | null)[]> & { time: string[] };

function mapDays(daily: Series): WeatherDay[] {
  return daily.time.flatMap((date, i) => {
    const tMaxC = daily.temperature_2m_max?.[i];
    const tMinC = daily.temperature_2m_min?.[i];
    if (tMaxC == null || tMinC == null) return [];
    return [
      {
        date,
        tMaxC,
        tMinC,
        tMeanC: daily.temperature_2m_mean?.[i] ?? null,
        precipitationMm: daily.precipitation_sum?.[i] ?? 0,
        radiationMjM2: daily.shortwave_radiation_sum?.[i] ?? null,
        et0Mm: daily.et0_fao_evapotranspiration?.[i] ?? null,
        windMaxKmh: daily.wind_speed_10m_max?.[i] ?? null,
        humidityPct: daily.relative_humidity_2m_mean?.[i] ?? null,
      },
    ];
  });
}

export function getForecast(lat: number, lng: number): Promise<Forecast> {
  return cached(`weather:forecast:${coord(lat)}:${coord(lng)}`, 1800, async () => {
    const params = new URLSearchParams({
      latitude: coord(lat),
      longitude: coord(lng),
      hourly: HOURLY,
      daily: DAILY,
      // Ziua curentă plus următoarele 14 zile complete.
      forecast_days: '15',
      past_days: '1',
      timezone: 'auto',
      wind_speed_unit: 'kmh',
    });
    const data = await fetchJson<{
      timezone: string;
      utc_offset_seconds: number;
      hourly: Series;
      daily: Series;
    }>(`https://api.open-meteo.com/v1/forecast?${params}`, { service: SERVICE });

    const hours = data.hourly.time.flatMap((time, i): ForecastHourFull[] => {
      const temperatureC = data.hourly.temperature_2m?.[i];
      if (temperatureC == null) return [];
      return [
        {
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
        },
      ];
    });

    return {
      timezone: data.timezone,
      utcOffsetSeconds: data.utc_offset_seconds,
      hours,
      days: mapDays(data.daily),
    };
  });
}

/** Ora curentă în fusul orar al parcelei, în formatul folosit de prognoză. */
export const localHour = (forecast: Pick<Forecast, 'utcOffsetSeconds'>, now = new Date()) =>
  `${new Date(now.getTime() + forecast.utcOffsetSeconds * 1000).toISOString().slice(0, 13)}:00`;

export function upcomingHours(forecast: Forecast, now = new Date()): ForecastHourFull[] {
  const current = localHour(forecast, now);
  return forecast.hours.filter((hour) => hour.time >= current);
}

export function getHistory(lat: number, lng: number, startDate: string, endDate: string): Promise<WeatherDay[]> {
  return cached(`weather:history:${coord(lat)}:${coord(lng)}:${startDate}:${endDate}`, 43_200, async () => {
    const params = new URLSearchParams({
      latitude: coord(lat),
      longitude: coord(lng),
      start_date: startDate,
      end_date: endDate,
      daily: DAILY,
      timezone: 'auto',
      wind_speed_unit: 'kmh',
    });
    const data = await fetchJson<{ daily: Series }>(`https://archive-api.open-meteo.com/v1/archive?${params}`, {
      service: SERVICE,
      timeoutMs: 25_000,
    });
    return mapDays(data.daily);
  });
}
