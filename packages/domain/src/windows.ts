export interface HourlyWeather {
  /** Local time at the parcel: YYYY-MM-DDTHH:mm. */
  time: string;
  temperatureC: number;
  humidityPct: number;
  precipitationMm: number;
  precipitationProbabilityPct?: number | null;
  windKmh: number;
}

export type FieldOperation = 'SPRAYING' | 'FERTILIZING' | 'SOWING' | 'HARVESTING';

export const OPERATION_LABELS: Record<FieldOperation, string> = {
  SPRAYING: 'spraying',
  FERTILIZING: 'fertilizing',
  SOWING: 'sowing',
  HARVESTING: 'harvesting',
};

interface OperationRule {
  maxWindKmh: number;
  tempC: readonly [number, number];
  humidityPct: readonly [number, number];
  maxRainProbabilityPct: number;
  maxHourlyRainMm: number;
  /** Maximum rain accepted in the preceding hours (wet ground or plants). */
  maxRecentRainMm: number;
  recentHours: number;
  idealWindKmh: number;
  idealTempC: number;
}

const RULES: Record<FieldOperation, OperationRule> = {
  SPRAYING: { maxWindKmh: 15, tempC: [8, 25], humidityPct: [45, 95], maxRainProbabilityPct: 30, maxHourlyRainMm: 0, maxRecentRainMm: 1, recentHours: 3, idealWindKmh: 6, idealTempC: 16 },
  FERTILIZING: { maxWindKmh: 25, tempC: [2, 28], humidityPct: [30, 100], maxRainProbabilityPct: 60, maxHourlyRainMm: 1, maxRecentRainMm: 8, recentHours: 6, idealWindKmh: 8, idealTempC: 14 },
  SOWING: { maxWindKmh: 40, tempC: [5, 32], humidityPct: [25, 95], maxRainProbabilityPct: 40, maxHourlyRainMm: 0.2, maxRecentRainMm: 6, recentHours: 24, idealWindKmh: 10, idealTempC: 18 },
  HARVESTING: { maxWindKmh: 45, tempC: [10, 38], humidityPct: [15, 72], maxRainProbabilityPct: 25, maxHourlyRainMm: 0, maxRecentRainMm: 1, recentHours: 12, idealWindKmh: 12, idealTempC: 26 },
};

export interface WorkWindow {
  operation: FieldOperation;
  start: string;
  end: string;
  hours: number;
  score: number;
  label: string;
}

const DAY_NAMES = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];

const dayName = (time: string) => {
  const [year, month, day] = time.slice(0, 10).split('-').map(Number);
  return DAY_NAMES[new Date(Date.UTC(year!, month! - 1, day!)).getUTCDay()]!;
};

function addHour(time: string): string {
  const [date, clock] = time.split('T');
  const [year, month, day] = date!.split('-').map(Number);
  const [hour, minute] = clock!.split(':').map(Number);
  return new Date(Date.UTC(year!, month! - 1, day!, hour! + 1, minute!)).toISOString().slice(0, 16);
}

/** Intervals that cross midnight also show their end day. */
function windowLabel(start: string, end: string): string {
  const startClock = start.slice(11, 16);
  const endClock = end.slice(11, 16);
  const hoursLong = (Date.parse(`${end}Z`) - Date.parse(`${start}Z`)) / 3_600_000;
  const sameDay = start.slice(0, 10) === end.slice(0, 10);
  if (sameDay || (endClock === '00:00' && hoursLong <= 24)) {
    return `${dayName(start)} ${startClock}-${endClock}`;
  }
  return `${dayName(start)} ${startClock} - ${dayName(end)} ${endClock}`;
}

function hourScore(hour: HourlyWeather, rule: OperationRule): number {
  const windPenalty = Math.max(0, hour.windKmh - rule.idealWindKmh) / Math.max(1, rule.maxWindKmh - rule.idealWindKmh);
  const span = Math.max(rule.tempC[1] - rule.idealTempC, rule.idealTempC - rule.tempC[0]);
  const tempPenalty = Math.abs(hour.temperatureC - rule.idealTempC) / span;
  const rainPenalty = (hour.precipitationProbabilityPct ?? 0) / 100;
  return Math.max(0, 100 - windPenalty * 40 - tempPenalty * 35 - rainPenalty * 25);
}

function isSuitable(hours: readonly HourlyWeather[], index: number, rule: OperationRule): boolean {
  const hour = hours[index]!;
  if (hour.windKmh > rule.maxWindKmh) return false;
  if (hour.temperatureC < rule.tempC[0] || hour.temperatureC > rule.tempC[1]) return false;
  if (hour.humidityPct < rule.humidityPct[0] || hour.humidityPct > rule.humidityPct[1]) return false;
  if (hour.precipitationMm > rule.maxHourlyRainMm) return false;
  if ((hour.precipitationProbabilityPct ?? 0) > rule.maxRainProbabilityPct) return false;
  const recent = hours
    .slice(Math.max(0, index - rule.recentHours), index)
    .reduce((sum, item) => sum + item.precipitationMm, 0);
  return recent <= rule.maxRecentRainMm;
}

/**
 * Finds suitable continuous intervals, ordered from best.
 * Hours before `from` only count towards recent rain.
 */
export function findWorkWindows(
  operation: FieldOperation,
  hours: readonly HourlyWeather[],
  minHours = 2,
  from?: string,
): WorkWindow[] {
  const rule = RULES[operation];
  const windows: WorkWindow[] = [];
  let startIndex = -1;

  const close = (endIndex: number) => {
    const count = endIndex - startIndex;
    if (startIndex < 0 || count < minHours) return;
    const slice = hours.slice(startIndex, endIndex);
    const average = slice.reduce((sum, hour) => sum + hourScore(hour, rule), 0) / count;
    const durationBonus = Math.min(10, count);
    const first = slice[0]!;
    const end = addHour(slice[count - 1]!.time);
    windows.push({
      operation,
      start: first.time,
      end,
      hours: count,
      score: Math.min(100, Math.round(average * 0.9 + durationBonus)),
      label: windowLabel(first.time, end),
    });
  };

  hours.forEach((item, index) => {
    const suitable = (from === undefined || item.time >= from) && isSuitable(hours, index, rule);
    if (suitable && startIndex < 0) startIndex = index;
    if (!suitable && startIndex >= 0) {
      close(index);
      startIndex = -1;
    }
  });
  if (startIndex >= 0) close(hours.length);

  return windows.sort((a, b) => b.score - a.score || a.start.localeCompare(b.start));
}

export function bestWindowMessage(operation: FieldOperation, windows: readonly WorkWindow[]): string {
  const best = windows[0];
  const name = OPERATION_LABELS[operation];
  return best
    ? `Best window for ${name}: ${best.label}`
    : `No suitable window for ${name} in the analysed period.`;
}
