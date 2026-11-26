import { CROPS, type CropCode } from './crops';

export interface DailyTemperature {
  date: string;
  tMaxC: number;
  tMinC: number;
}

/** Degree-days for one day, capped at the base and upper thresholds. */
export function dailyGdd(tMaxC: number, tMinC: number, baseC: number, capC = 30): number {
  const max = Math.min(capC, Math.max(baseC, tMaxC));
  const min = Math.min(capC, Math.max(baseC, tMinC));
  return Math.max(0, (max + min) / 2 - baseC);
}

const STAGES = [
  { upTo: 0.1, label: 'Emergence' },
  { upTo: 0.35, label: 'Vegetative growth' },
  { upTo: 0.55, label: 'Flowering' },
  { upTo: 0.8, label: 'Grain fill' },
  { upTo: 1, label: 'Ripening' },
];

export interface CropDevelopment {
  crop: CropCode;
  accumulatedGdd: number;
  requiredGdd: number;
  percent: number;
  stage: string;
}

export function cropDevelopment(crop: CropCode, days: readonly DailyTemperature[]): CropDevelopment {
  const profile = CROPS[crop];
  const accumulated = days.reduce(
    (sum, day) => sum + dailyGdd(day.tMaxC, day.tMinC, profile.gddBaseC, profile.gddCapC),
    0,
  );
  const ratio = accumulated / profile.gddToMaturity;
  const stage = ratio >= 1 ? 'Mature' : STAGES.find((item) => ratio <= item.upTo)!.label;
  return {
    crop,
    accumulatedGdd: Math.round(accumulated),
    requiredGdd: profile.gddToMaturity,
    percent: Math.min(100, Math.round(ratio * 100)),
    stage,
  };
}
