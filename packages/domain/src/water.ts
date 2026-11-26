import { CROPS, type CropCode } from './crops';

export interface WaterBalanceInput {
  crop: CropCode;
  precipitationMm: number;
  irrigationMm?: number;
  /** Dose applied in one irrigation pass, in mm. */
  doseMm?: number;
}

export interface WaterBalance {
  crop: CropCode;
  requiredMinMm: number;
  requiredMaxMm: number;
  requiredMm: number;
  receivedMm: number;
  deficitMm: number;
  coveragePct: number;
  irrigationPasses: number;
  doseMm: number;
  suggestion: string;
}

/** Seasonal water balance, relative to the midpoint of the crop's requirement range. */
export function waterBalance(input: WaterBalanceInput): WaterBalance {
  const { crop, precipitationMm, irrigationMm = 0, doseMm = 30 } = input;
  const profile = CROPS[crop];
  const [requiredMinMm, requiredMaxMm] = profile.waterMm;
  const requiredMm = (requiredMinMm + requiredMaxMm) / 2;
  const receivedMm = Math.max(0, precipitationMm) + Math.max(0, irrigationMm);
  const deficitMm = Math.max(0, requiredMm - receivedMm);
  const irrigationPasses = deficitMm > 0 ? Math.ceil(deficitMm / doseMm) : 0;

  let suggestion: string;
  if (deficitMm === 0) {
    suggestion = `${profile.name}: the water requirement is covered, irrigation is not needed.`;
  } else if (receivedMm >= requiredMinMm) {
    suggestion = `${profile.name}: water received reaches the minimum threshold. Irrigation is optional, with ${irrigationPasses} passes of ${doseMm} mm for maximum yield.`;
  } else {
    suggestion = `${profile.name}: deficit of ${Math.round(deficitMm)} mm. ${irrigationPasses} irrigation passes of ${doseMm} mm are recommended.`;
  }

  return {
    crop,
    requiredMinMm,
    requiredMaxMm,
    requiredMm,
    receivedMm: Math.round(receivedMm * 10) / 10,
    deficitMm: Math.round(deficitMm * 10) / 10,
    coveragePct: Math.min(100, Math.round((receivedMm / requiredMm) * 100)),
    irrigationPasses,
    doseMm,
    suggestion,
  };
}
