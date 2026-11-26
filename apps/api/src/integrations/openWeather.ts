import type { ForecastHour } from '@fermapp/domain';
import { env } from '../config/env';
import { cached } from '../lib/cache';
import { fetchJson } from '../lib/http';

interface OwmEntry {
  dt: number;
  main: { temp: number; humidity: number };
  wind: { speed: number };
  pop?: number;
  rain?: { '3h'?: number };
  snow?: { '3h'?: number };
  weather: { id: number }[];
}

const HOUR_S = 3600;

/** OpenWeatherMap codes: group 2xx is thunderstorm, 202/212/232 are the most intense. */
function toWmoCode(id: number | undefined): number | null {
  if (id === undefined) return null;
  if (id === 906) return 96;
  if (id >= 200 && id < 300) return 95;
  return null;
}

/** Converts the 3-hour steps into hours, starting from the current hour, so the "in N hours" message is exact. */
export function expandOwmForecast(list: OwmEntry[], timezoneOffsetS: number, nowMs = Date.now()): ForecastHour[] {
  const hours: ForecastHour[] = [];
  const currentHourS = Math.floor(nowMs / 1000 / HOUR_S) * HOUR_S;

  for (const entry of list) {
    const precipitation = ((entry.rain?.['3h'] ?? 0) + (entry.snow?.['3h'] ?? 0)) / 3;
    for (let step = 0; step < 3; step += 1) {
      const timestamp = entry.dt + step * HOUR_S;
      if (timestamp < currentHourS) continue;
      hours.push({
        time: new Date((timestamp + timezoneOffsetS) * 1000).toISOString().slice(0, 16),
        temperatureC: entry.main.temp,
        humidityPct: entry.main.humidity,
        precipitationMm: precipitation,
        precipitationProbabilityPct: Math.round((entry.pop ?? 0) * 100),
        windKmh: entry.wind.speed * 3.6,
        weatherCode: toWmoCode(entry.weather[0]?.id),
      });
    }
  }

  // The first step may start in the future, and missing hours are filled in without rain.
  const first = list[0];
  if (first && first.dt > currentHourS) {
    const missing = Math.round((first.dt - currentHourS) / HOUR_S);
    const padding = Array.from({ length: missing }, (_, i): ForecastHour => ({
      time: new Date((currentHourS + i * HOUR_S + timezoneOffsetS) * 1000).toISOString().slice(0, 16),
      temperatureC: first.main.temp,
      humidityPct: first.main.humidity,
      precipitationMm: 0,
      precipitationProbabilityPct: 0,
      windKmh: first.wind.speed * 3.6,
      weatherCode: null,
    }));
    hours.unshift(...padding);
  }
  return hours;
}

export const isOpenWeatherConfigured = () => Boolean(env.OPENWEATHER_API_KEY);

export function getOwmHours(lat: number, lng: number): Promise<ForecastHour[]> {
  return cached(`owm:${lat.toFixed(2)}:${lng.toFixed(2)}`, 1800, async () => {
    const params = new URLSearchParams({
      lat: lat.toFixed(4),
      lon: lng.toFixed(4),
      units: 'metric',
      appid: env.OPENWEATHER_API_KEY ?? '',
    });
    const data = await fetchJson<{ list: OwmEntry[]; city: { timezone: number } }>(
      `https://api.openweathermap.org/data/2.5/forecast?${params}`,
      { service: 'OpenWeatherMap' },
    );
    return expandOwmForecast(data.list, data.city.timezone);
  });
}
