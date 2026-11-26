import type { HourlyWeather } from './windows';

export type AlertType = 'RAIN' | 'STORM' | 'HAIL' | 'FROST' | 'HEAT' | 'WIND_SPRAYING';
export type AlertSeverity = 'INFO' | 'WARNING' | 'SEVERE';

export interface ForecastHour extends HourlyWeather {
  /** WMO code, used for thunderstorms (95) and hail (96, 99). */
  weatherCode?: number | null;
}

export interface WeatherAlertDraft {
  type: AlertType;
  severity: AlertSeverity;
  title: string;
  message: string;
  startsAt: string;
}

const RAIN_THRESHOLD_MM = 0.5;
const SPRAY_WIND_LIMIT_KMH = 15;
const FROST_C = 0;
const HEAT_C = 35;

const inHours = (n: number) => (n <= 1 ? 'within the hour' : `in ${n} hours`);

/** Generates at most one alert per type, for the first relevant moment in the horizon. */
export function evaluateWeatherAlerts(
  parcelName: string,
  hours: readonly ForecastHour[],
  horizonHours = 24,
): WeatherAlertDraft[] {
  const window = hours.slice(0, horizonHours);
  const alerts: WeatherAlertDraft[] = [];
  const firstIndex = (predicate: (hour: ForecastHour) => boolean) => window.findIndex(predicate);

  const hailIndex = firstIndex((h) => h.weatherCode === 96 || h.weatherCode === 99);
  if (hailIndex >= 0) {
    alerts.push({
      type: 'HAIL',
      severity: 'SEVERE',
      title: 'Hail risk',
      message: `Hailstorm expected ${inHours(hailIndex)} on parcel ${parcelName}.`,
      startsAt: window[hailIndex]!.time,
    });
  }

  const stormIndex = firstIndex((h) => h.weatherCode === 95);
  if (stormIndex >= 0) {
    alerts.push({
      type: 'STORM',
      severity: 'SEVERE',
      title: 'Thunderstorm',
      message: `Thunderstorm expected ${inHours(stormIndex)} on parcel ${parcelName}.`,
      startsAt: window[stormIndex]!.time,
    });
  }

  const rainIndex = firstIndex((h) => h.precipitationMm >= RAIN_THRESHOLD_MM);
  if (rainIndex >= 0) {
    alerts.push({
      type: 'RAIN',
      severity: 'INFO',
      title: 'Rain',
      message: `Rain expected ${inHours(rainIndex)} on parcel ${parcelName}.`,
      startsAt: window[rainIndex]!.time,
    });
  }

  const frostIndex = firstIndex((h) => h.temperatureC <= FROST_C);
  if (frostIndex >= 0) {
    alerts.push({
      type: 'FROST',
      severity: 'WARNING',
      title: 'Frost',
      message: `Frost expected ${inHours(frostIndex)} on parcel ${parcelName}: ${Math.round(window[frostIndex]!.temperatureC)} °C.`,
      startsAt: window[frostIndex]!.time,
    });
  }

  const heatIndex = firstIndex((h) => h.temperatureC >= HEAT_C);
  if (heatIndex >= 0) {
    alerts.push({
      type: 'HEAT',
      severity: 'WARNING',
      title: 'Extreme temperatures',
      message: `Heatwave expected ${inHours(heatIndex)} on parcel ${parcelName}: ${Math.round(window[heatIndex]!.temperatureC)} °C.`,
      startsAt: window[heatIndex]!.time,
    });
  }

  const current = window[0];
  if (current && current.windKmh > SPRAY_WIND_LIMIT_KMH) {
    alerts.push({
      type: 'WIND_SPRAYING',
      severity: 'WARNING',
      title: 'Strong wind',
      message: `Wind ${Math.round(current.windKmh)} km/h. Herbicide spraying is not recommended.`,
      startsAt: current.time,
    });
  }

  return alerts;
}
