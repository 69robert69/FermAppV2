import { CROP_CODES, CROPS, type CropCode, type SoilTexture } from './crops';
import { evaluateRotation, type CropHistoryEntry } from './rotation';

export interface RecommendationInput {
  year: number;
  history: readonly CropHistoryEntry[];
  soil?: { ph: number; texture: SoilTexture } | null;
  climate?: {
    /** Mean temperature during the growing months, in °C. */
    seasonTempC: number;
    annualPrecipitationMm: number;
    /** Annual degree-days above 10 °C. */
    gddBase10?: number;
  } | null;
  crops?: readonly CropCode[];
}

export interface CropRecommendation {
  crop: CropCode;
  name: string;
  score: number;
  reasons: string[];
  /** Criteria without data, which received a neutral score. */
  missingData: string[];
}

const WEIGHTS = { rotation: 35, soil: 25, temperature: 20, water: 20 } as const;
const NEUTRAL = 0.65;

/** 1 inside the range, then decreases linearly to 0 at distance `tolerance` from the range. */
function rangeFit(value: number, [min, max]: readonly [number, number], tolerance: number): number {
  if (value >= min && value <= max) return 1;
  const distance = value < min ? min - value : value - max;
  return Math.max(0, 1 - distance / tolerance);
}

export function recommendCrops(input: RecommendationInput): CropRecommendation[] {
  const crops = input.crops ?? CROP_CODES;

  return crops
    .map((crop): CropRecommendation => {
      const profile = CROPS[crop];
      const reasons: string[] = [];
      const missingData: string[] = [];

      const rotation = evaluateRotation(crop, input.year, input.history);
      reasons.push(`Rotation ${rotation.score}/100: ${rotation.explanation}`);

      let soilFit = NEUTRAL;
      if (input.soil) {
        const phFit = rangeFit(input.soil.ph, profile.phRange, 1.5);
        const textureFit = profile.textures.includes(input.soil.texture) ? 1 : 0.45;
        soilFit = phFit * 0.6 + textureFit * 0.4;
        reasons.push(
          phFit === 1
            ? `pH ${input.soil.ph} is within the optimal range of ${profile.phRange[0]}-${profile.phRange[1]}.`
            : `pH ${input.soil.ph} is outside the optimal range of ${profile.phRange[0]}-${profile.phRange[1]}.`,
        );
        if (textureFit < 1) reasons.push('The soil texture is not among those preferred by the crop.');
      } else {
        missingData.push('soil');
      }

      let temperatureFit = NEUTRAL;
      let waterFit = NEUTRAL;
      if (input.climate) {
        temperatureFit = rangeFit(input.climate.seasonTempC, profile.seasonTempC, 8);
        reasons.push(
          temperatureFit === 1
            ? `The mean temperature of ${input.climate.seasonTempC.toFixed(1)} °C is favourable.`
            : `The mean temperature of ${input.climate.seasonTempC.toFixed(1)} °C is outside the optimum of ${profile.seasonTempC[0]}-${profile.seasonTempC[1]} °C.`,
        );
        waterFit = rangeFit(input.climate.annualPrecipitationMm, profile.waterMm, 300);
        reasons.push(
          input.climate.annualPrecipitationMm < profile.waterMm[0]
            ? `Rainfall of ${Math.round(input.climate.annualPrecipitationMm)} mm is below the minimum requirement of ${profile.waterMm[0]} mm.`
            : `Rainfall of ${Math.round(input.climate.annualPrecipitationMm)} mm covers the minimum requirement of ${profile.waterMm[0]} mm.`,
        );
      } else {
        missingData.push('climate');
      }

      const score =
        (rotation.score / 100) * WEIGHTS.rotation +
        soilFit * WEIGHTS.soil +
        temperatureFit * WEIGHTS.temperature +
        waterFit * WEIGHTS.water;

      return { crop, name: profile.name, score: Math.round(score), reasons, missingData };
    })
    .sort((a, b) => b.score - a.score);
}
