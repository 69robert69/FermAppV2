import type { HourlyWeather } from './windows';

export type AlertType = 'RAIN' | 'STORM' | 'HAIL' | 'FROST' | 'HEAT' | 'WIND_SPRAYING';
export type AlertSeverity = 'INFO' | 'WARNING' | 'SEVERE';

export interface ForecastHour extends HourlyWeather {
  /** Cod WMO, folosit pentru furtuni (95) și grindină (96, 99). */
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

const inHours = (n: number) => (n <= 1 ? 'într-o oră' : `în ${n} ore`);

/** Generează cel mult o alertă pe tip, pentru primul moment relevant din orizont. */
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
      title: 'Risc de grindină',
      message: `Furtună cu grindină estimată ${inHours(hailIndex)} pe parcela ${parcelName}.`,
      startsAt: window[hailIndex]!.time,
    });
  }

  const stormIndex = firstIndex((h) => h.weatherCode === 95);
  if (stormIndex >= 0) {
    alerts.push({
      type: 'STORM',
      severity: 'SEVERE',
      title: 'Furtună',
      message: `Furtună estimată ${inHours(stormIndex)} pe parcela ${parcelName}.`,
      startsAt: window[stormIndex]!.time,
    });
  }

  const rainIndex = firstIndex((h) => h.precipitationMm >= RAIN_THRESHOLD_MM);
  if (rainIndex >= 0) {
    alerts.push({
      type: 'RAIN',
      severity: 'INFO',
      title: 'Ploaie',
      message: `Ploaie estimată ${inHours(rainIndex)} pe parcela ${parcelName}.`,
      startsAt: window[rainIndex]!.time,
    });
  }

  const frostIndex = firstIndex((h) => h.temperatureC <= FROST_C);
  if (frostIndex >= 0) {
    alerts.push({
      type: 'FROST',
      severity: 'WARNING',
      title: 'Îngheț',
      message: `Îngheț estimat ${inHours(frostIndex)} pe parcela ${parcelName}: ${Math.round(window[frostIndex]!.temperatureC)} °C.`,
      startsAt: window[frostIndex]!.time,
    });
  }

  const heatIndex = firstIndex((h) => h.temperatureC >= HEAT_C);
  if (heatIndex >= 0) {
    alerts.push({
      type: 'HEAT',
      severity: 'WARNING',
      title: 'Temperaturi extreme',
      message: `Caniculă estimată ${inHours(heatIndex)} pe parcela ${parcelName}: ${Math.round(window[heatIndex]!.temperatureC)} °C.`,
      startsAt: window[heatIndex]!.time,
    });
  }

  const current = window[0];
  if (current && current.windKmh > SPRAY_WIND_LIMIT_KMH) {
    alerts.push({
      type: 'WIND_SPRAYING',
      severity: 'WARNING',
      title: 'Vânt puternic',
      message: `Vânt ${Math.round(current.windKmh)} km/h. Erbicidarea nu este recomandată.`,
      startsAt: current.time,
    });
  }

  return alerts;
}
