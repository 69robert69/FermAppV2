export type HealthClass = 'GOOD' | 'MODERATE' | 'PROBLEM';

export interface VegetationHealth {
  healthIndex: number;
  healthClass: HealthClass;
  color: 'green' | 'yellow' | 'red';
  label: string;
}

const clamp01 = (value: number) => Math.max(0, Math.min(1, value));

/** 0-100 index: NDVI shows biomass, and NDRE shows nitrogen and chlorophyll status. */
export function vegetationHealth(ndvi: number, ndre?: number | null): VegetationHealth {
  const ndviScore = clamp01((ndvi - 0.1) / 0.7);
  const score = ndre == null ? ndviScore : ndviScore * 0.7 + clamp01((ndre - 0.05) / 0.45) * 0.3;
  const healthIndex = Math.round(score * 100);
  if (healthIndex >= 60) return { healthIndex, healthClass: 'GOOD', color: 'green', label: 'Good growth' };
  if (healthIndex >= 35) return { healthIndex, healthClass: 'MODERATE', color: 'yellow', label: 'Moderate stress' };
  return { healthIndex, healthClass: 'PROBLEM', color: 'red', label: 'Possible problem' };
}
