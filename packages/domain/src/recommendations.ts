import { CROP_CODES, CROPS, type CropCode, type SoilTexture } from './crops';
import { evaluateRotation, type CropHistoryEntry } from './rotation';

export interface RecommendationInput {
  year: number;
  history: readonly CropHistoryEntry[];
  soil?: { ph: number; texture: SoilTexture } | null;
  climate?: {
    /** Temperatura medie în lunile de vegetație, în °C. */
    seasonTempC: number;
    annualPrecipitationMm: number;
    /** Grade-zile anuale peste 10 °C. */
    gddBase10?: number;
  } | null;
  crops?: readonly CropCode[];
}

export interface CropRecommendation {
  crop: CropCode;
  name: string;
  score: number;
  reasons: string[];
  /** Criteriile fără date, care au primit un punctaj neutru. */
  missingData: string[];
}

const WEIGHTS = { rotation: 35, soil: 25, temperature: 20, water: 20 } as const;
const NEUTRAL = 0.65;

/** 1 în interval, apoi scade liniar până la 0 la distanța `tolerance` de interval. */
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
      reasons.push(`Rotație ${rotation.score}/100: ${rotation.explanation}`);

      let soilFit = NEUTRAL;
      if (input.soil) {
        const phFit = rangeFit(input.soil.ph, profile.phRange, 1.5);
        const textureFit = profile.textures.includes(input.soil.texture) ? 1 : 0.45;
        soilFit = phFit * 0.6 + textureFit * 0.4;
        reasons.push(
          phFit === 1
            ? `pH ${input.soil.ph} se încadrează în intervalul optim ${profile.phRange[0]}-${profile.phRange[1]}.`
            : `pH ${input.soil.ph} este în afara intervalului optim ${profile.phRange[0]}-${profile.phRange[1]}.`,
        );
        if (textureFit < 1) reasons.push('Textura solului nu este printre cele preferate de cultură.');
      } else {
        missingData.push('sol');
      }

      let temperatureFit = NEUTRAL;
      let waterFit = NEUTRAL;
      if (input.climate) {
        temperatureFit = rangeFit(input.climate.seasonTempC, profile.seasonTempC, 8);
        reasons.push(
          temperatureFit === 1
            ? `Temperatura medie de ${input.climate.seasonTempC.toFixed(1)} °C este favorabilă.`
            : `Temperatura medie de ${input.climate.seasonTempC.toFixed(1)} °C este în afara optimului de ${profile.seasonTempC[0]}-${profile.seasonTempC[1]} °C.`,
        );
        waterFit = rangeFit(input.climate.annualPrecipitationMm, profile.waterMm, 300);
        reasons.push(
          input.climate.annualPrecipitationMm < profile.waterMm[0]
            ? `Precipitațiile de ${Math.round(input.climate.annualPrecipitationMm)} mm sunt sub necesarul minim de ${profile.waterMm[0]} mm.`
            : `Precipitațiile de ${Math.round(input.climate.annualPrecipitationMm)} mm acoperă necesarul minim de ${profile.waterMm[0]} mm.`,
        );
      } else {
        missingData.push('climă');
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
