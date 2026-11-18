import { CROPS, type CropCode } from './crops';

export interface WaterBalanceInput {
  crop: CropCode;
  precipitationMm: number;
  irrigationMm?: number;
  /** Doza aplicată la o trecere de irigare, în mm. */
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

/** Bilanțul de apă pe sezon, raportat la mijlocul intervalului de necesar al culturii. */
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
    suggestion = `${profile.name}: necesarul de apă este acoperit, irigarea nu este necesară.`;
  } else if (receivedMm >= requiredMinMm) {
    suggestion = `${profile.name}: apa primită atinge pragul minim. Irigarea este opțională, cu ${irrigationPasses} treceri de ${doseMm} mm pentru producție maximă.`;
  } else {
    suggestion = `${profile.name}: deficit de ${Math.round(deficitMm)} mm. Se recomandă ${irrigationPasses} treceri de irigare de ${doseMm} mm.`;
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
