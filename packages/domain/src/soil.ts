import type { SoilTexture } from './crops';

export interface SoilInput {
  ph: number;
  clayPct: number;
  sandPct: number;
  /** Carbon organic, în g/kg. */
  organicCarbonGKg: number;
  /** Apă la capacitatea de câmp și la coeficientul de ofilire, în % volumetric. */
  fieldCapacityPct?: number | null;
  wiltingPointPct?: number | null;
}

export interface SoilClassification {
  siltPct: number;
  organicMatterPct: number;
  waterHoldingMmPerM: number;
  texture: SoilTexture;
  textureLabel: string;
  soilType: string;
  label: string;
}

/** Factorul van Bemmelen: materia organică are aproximativ 58% carbon. */
const VAN_BEMMELEN = 1.724;

function classifyTexture(clayPct: number, sandPct: number): { texture: SoilTexture; label: string } {
  if (clayPct >= 40) return { texture: 'CLAY', label: 'argilos' };
  if (clayPct >= 27) return { texture: 'CLAY_LOAM', label: 'lutos-argilos' };
  if (sandPct >= 65) return { texture: 'SANDY', label: 'nisipos' };
  return { texture: 'LOAMY', label: 'lutos' };
}

function classifySoilType(ph: number, clayPct: number, sandPct: number, organicMatterPct: number): string {
  if (sandPct >= 75) return 'Psamosol';
  if (clayPct >= 50) return 'Vertosol';
  if (ph >= 8.3) return 'Kastanoziom';
  if (organicMatterPct >= 2.8 && ph >= 6 && ph < 8.3) return 'Cernoziom';
  if (organicMatterPct >= 2.8) return 'Faeoziom';
  if (ph < 5.8) return 'Luvosol';
  return 'Eutricambosol';
}

/** Estimează apa utilă când SoilGrids nu oferă valorile de retenție. */
function estimateAvailableWaterPct(clayPct: number, sandPct: number, organicMatterPct: number): number {
  const base = 18 - Math.abs(clayPct - 25) * 0.12 - Math.max(0, sandPct - 50) * 0.18;
  return Math.max(5, base + organicMatterPct * 0.6);
}

/**
 * Clasificare orientativă pe baza proprietăților solului.
 * Tipul de sol oficial se stabilește doar prin studiu pedologic (SRTS).
 */
export function classifySoil(input: SoilInput): SoilClassification {
  const { ph, clayPct, sandPct, organicCarbonGKg, fieldCapacityPct, wiltingPointPct } = input;
  const organicMatterPct = Math.round(((organicCarbonGKg * VAN_BEMMELEN) / 10) * 100) / 100;
  const { texture, label: textureLabel } = classifyTexture(clayPct, sandPct);
  const soilType = classifySoilType(ph, clayPct, sandPct, organicMatterPct);
  const availablePct =
    fieldCapacityPct != null && wiltingPointPct != null && fieldCapacityPct > wiltingPointPct
      ? fieldCapacityPct - wiltingPointPct
      : estimateAvailableWaterPct(clayPct, sandPct, organicMatterPct);

  return {
    siltPct: Math.max(0, Math.round((100 - clayPct - sandPct) * 10) / 10),
    organicMatterPct,
    waterHoldingMmPerM: Math.round(availablePct * 10),
    texture,
    textureLabel,
    soilType,
    label: `${soilType} ${textureLabel}`,
  };
}
