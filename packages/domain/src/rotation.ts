import { CROPS, type CropCode } from './crops';

export interface CropHistoryEntry {
  crop: CropCode;
  /** Agricultural year of the harvest. */
  year: number;
}

export type RotationVerdict = 'RECOMMENDED' | 'ACCEPTABLE' | 'NOT_RECOMMENDED';

export interface RotationFactor {
  code: string;
  impact: number;
  message: string;
}

export interface RotationResult {
  crop: CropCode;
  year: number;
  score: number;
  verdict: RotationVerdict;
  label: string;
  explanation: string;
  factors: RotationFactor[];
}

export const ROTATION_THRESHOLDS = { recommended: 70, acceptable: 40 } as const;

export const VERDICT_LABELS: Record<RotationVerdict, string> = {
  RECOMMENDED: '✅ Recommended',
  ACCEPTABLE: '⚠️ Acceptable',
  NOT_RECOMMENDED: '❌ Not recommended',
};

const BASE_SCORE = 75;

const yearsWord = (n: number) => (n === 1 ? 'one year' : n === 2 ? 'two years' : `${n} years`);
const agoPhrase = (n: number) => (n === 0 ? 'this year' : n === 1 ? 'last year' : `${n} years ago`);

/** Common diseases (Sclerotinia) make these preceding crops risky for one another. */
const SCLEROTINIA_HOSTS: readonly CropCode[] = ['SUNFLOWER', 'RAPESEED', 'SOY'];

/**
 * Evaluates the proposed crop based on the parcel's history.
 * The break is measured in full years without the crop: 2022 and 2027 mean a 4-year break.
 */
export function evaluateRotation(
  proposed: CropCode,
  proposedYear: number,
  history: readonly CropHistoryEntry[],
): RotationResult {
  const profile = CROPS[proposed];
  const past = history
    .filter((entry) => entry.year < proposedYear)
    .sort((a, b) => b.year - a.year);
  const factors: RotationFactor[] = [];
  const add = (code: string, impact: number, message: string) =>
    factors.push({ code, impact, message });

  const previous = past[0]?.year === proposedYear - 1 ? past[0] : undefined;
  const lastSame = past.find((entry) => entry.crop === proposed);
  let breakViolated = false;

  if (lastSame) {
    const yearsAgo = proposedYear - lastSame.year;
    const breakYears = yearsAgo - 1;
    if (breakYears < profile.minBreakYears) {
      breakViolated = true;
      const missing = profile.minBreakYears - breakYears;
      add(
        'MIN_BREAK',
        -(35 + missing * 10),
        `${profile.name} was grown ${agoPhrase(yearsAgo)} on this parcel. ` +
          `A minimum break of ${yearsWord(profile.minBreakYears)} is recommended. ` +
          'The risk of disease and yield loss is high.',
      );
    } else {
      add('BREAK_OK', 5, `The ${yearsWord(breakYears)} break since the last ${profile.name.toLowerCase()} crop is respected.`);
    }
  }

  if (previous && previous.crop !== proposed) {
    const previousProfile = CROPS[previous.crop];

    if (previousProfile.family === profile.family && profile.family !== 'VEGETABLE') {
      add('SAME_FAMILY', -15, `${previousProfile.name} and ${profile.name.toLowerCase()} belong to the same family and share diseases and pests.`);
    }
    if (proposed === 'CORN' && previousProfile.family === 'LEGUME') {
      add('CORN_AFTER_LEGUME', 15, `Corn uses the nitrogen left in the soil by ${previousProfile.name.toLowerCase()}.`);
    }
    if (proposed === 'SOY' && previousProfile.family === 'CEREAL') {
      add('SOY_AFTER_CEREAL', 15, `Soybean is recommended after cereals, and ${previousProfile.name.toLowerCase()} is a good preceding crop.`);
    }
    if (previous.crop === 'ALFALFA' && proposed !== 'CORN') {
      add('ALFALFA_NITROGEN', 15, 'Alfalfa has improved the soil nitrogen reserve.');
    }
    if (
      proposed !== 'CORN' &&
      previous.crop !== 'ALFALFA' &&
      profile.family === 'CEREAL' &&
      previousProfile.family === 'LEGUME'
    ) {
      add('CEREAL_AFTER_LEGUME', 10, `Cereals benefit from the nitrogen fixed by ${previousProfile.name.toLowerCase()}.`);
    }
    if (SCLEROTINIA_HOSTS.includes(proposed) && SCLEROTINIA_HOSTS.includes(previous.crop)) {
      add('SCLEROTINIA', -20, `${previousProfile.name} and ${profile.name.toLowerCase()} are both hosts of white mould (Sclerotinia).`);
    }
  }

  const lastDecade = past.filter((entry) => entry.year >= proposedYear - 10);
  const sameCount = lastDecade.filter((entry) => entry.crop === proposed).length;
  if (lastDecade.length >= 4 && sameCount / lastDecade.length > 0.4) {
    add('HIGH_FREQUENCY', -10, `${profile.name} occupied the parcel in ${sameCount} of the last ${lastDecade.length} recorded years.`);
  }

  if (past.length === 0) {
    add('NO_HISTORY', -5, 'The parcel has no crop history, so the assessment is indicative.');
  }

  const raw = factors.reduce((score, factor) => score + factor.impact, BASE_SCORE);
  const capped = breakViolated ? Math.min(raw, ROTATION_THRESHOLDS.acceptable - 1) : raw;
  const score = Math.max(0, Math.min(100, Math.round(capped)));
  const verdict: RotationVerdict =
    score >= ROTATION_THRESHOLDS.recommended
      ? 'RECOMMENDED'
      : score >= ROTATION_THRESHOLDS.acceptable
        ? 'ACCEPTABLE'
        : 'NOT_RECOMMENDED';

  const ordered = [...factors].sort((a, b) => Math.abs(b.impact) - Math.abs(a.impact));
  return {
    crop: proposed,
    year: proposedYear,
    score,
    verdict,
    label: VERDICT_LABELS[verdict],
    explanation:
      ordered[0]?.message ?? `${profile.name} has no rotation restrictions on this parcel.`,
    factors: ordered,
  };
}

export function rankRotationOptions(
  proposedYear: number,
  history: readonly CropHistoryEntry[],
  crops: readonly CropCode[],
): RotationResult[] {
  return crops
    .map((crop) => evaluateRotation(crop, proposedYear, history))
    .sort((a, b) => b.score - a.score);
}
