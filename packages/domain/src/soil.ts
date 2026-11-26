import type { SoilTexture } from './crops';

export interface SoilInput {
  ph: number;
  clayPct: number;
  sandPct: number;
  /** Organic carbon, in g/kg. */
  organicCarbonGKg: number;
  /** Water at field capacity and at wilting point, in % by volume. */
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

/** Van Bemmelen factor: organic matter is about 58% carbon. */
const VAN_BEMMELEN = 1.724;

function classifyTexture(clayPct: number, sandPct: number): { texture: SoilTexture; label: string } {
  if (clayPct >= 40) return { texture: 'CLAY', label: 'clay' };
  if (clayPct >= 27) return { texture: 'CLAY_LOAM', label: 'clay loam' };
  if (sandPct >= 65) return { texture: 'SANDY', label: 'sandy' };
  return { texture: 'LOAMY', label: 'loam' };
}

function classifySoilType(ph: number, clayPct: number, sandPct: number, organicMatterPct: number): string {
  if (sandPct >= 75) return 'Arenosol';
  if (clayPct >= 50) return 'Vertisol';
  if (ph >= 8.3) return 'Kastanozem';
  if (organicMatterPct >= 2.8 && ph >= 6 && ph < 8.3) return 'Chernozem';
  if (organicMatterPct >= 2.8) return 'Phaeozem';
  if (ph < 5.8) return 'Luvisol';
  return 'Eutric Cambisol';
}

/** Estimates available water when SoilGrids does not provide retention values. */
function estimateAvailableWaterPct(clayPct: number, sandPct: number, organicMatterPct: number): number {
  const base = 18 - Math.abs(clayPct - 25) * 0.12 - Math.max(0, sandPct - 50) * 0.18;
  return Math.max(5, base + organicMatterPct * 0.6);
}

/**
 * Indicative classification based on soil properties.
 * The official soil type can only be established by a pedological survey (SRTS).
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
    label: `${soilType} (${textureLabel})`,
  };
}
