import { CROPS, type CropCode } from './crops';

export interface CropHistoryEntry {
  crop: CropCode;
  /** Anul agricol al recoltei. */
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
  RECOMMENDED: '✅ Recomandat',
  ACCEPTABLE: '⚠️ Acceptabil',
  NOT_RECOMMENDED: '❌ Nerecomandat',
};

const BASE_SCORE = 75;

const yearsWord = (n: number) => (n === 1 ? 'un an' : n === 2 ? 'doi ani' : `${n} ani`);
const agoPhrase = (n: number) => (n === 0 ? 'în acest an' : n === 1 ? 'anul trecut' : `acum ${n} ani`);

/** Bolile comune (Sclerotinia) fac aceste culturi predecesoare riscante una pentru alta. */
const SCLEROTINIA_HOSTS: readonly CropCode[] = ['SUNFLOWER', 'RAPESEED', 'SOY'];

/**
 * Evaluează cultura propusă pe baza istoricului parcelei.
 * Pauza se măsoară în ani întregi fără cultură: 2022 și 2027 înseamnă o pauză de 4 ani.
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
        `${profile.name} a fost cultivată ${agoPhrase(yearsAgo)} pe această parcelă. ` +
          `Se recomandă o pauză minimă de ${yearsWord(profile.minBreakYears)}. ` +
          'Riscul de boli și scăderea producției este ridicat.',
      );
    } else {
      add('BREAK_OK', 5, `Pauza de ${yearsWord(breakYears)} față de ultima cultură de ${profile.name.toLowerCase()} este respectată.`);
    }
  }

  if (previous && previous.crop !== proposed) {
    const previousProfile = CROPS[previous.crop];

    if (previousProfile.family === profile.family && profile.family !== 'VEGETABLE') {
      add('SAME_FAMILY', -15, `${previousProfile.name} și ${profile.name.toLowerCase()} fac parte din aceeași familie și au boli și dăunători comuni.`);
    }
    if (proposed === 'CORN' && previousProfile.family === 'LEGUME') {
      add('CORN_AFTER_LEGUME', 15, `Porumbul valorifică azotul lăsat în sol de ${previousProfile.name.toLowerCase()}.`);
    }
    if (proposed === 'SOY' && previousProfile.family === 'CEREAL') {
      add('SOY_AFTER_CEREAL', 15, `Soia este recomandată după cereale, iar ${previousProfile.name.toLowerCase()} este o premergătoare bună.`);
    }
    if (previous.crop === 'ALFALFA' && proposed !== 'CORN') {
      add('ALFALFA_NITROGEN', 15, 'Lucerna a îmbunătățit rezerva de azot din sol.');
    }
    if (
      proposed !== 'CORN' &&
      previous.crop !== 'ALFALFA' &&
      profile.family === 'CEREAL' &&
      previousProfile.family === 'LEGUME'
    ) {
      add('CEREAL_AFTER_LEGUME', 10, `Cerealele beneficiază de azotul fixat de ${previousProfile.name.toLowerCase()}.`);
    }
    if (SCLEROTINIA_HOSTS.includes(proposed) && SCLEROTINIA_HOSTS.includes(previous.crop)) {
      add('SCLEROTINIA', -20, `${previousProfile.name} și ${profile.name.toLowerCase()} sunt gazde pentru putregaiul alb (Sclerotinia).`);
    }
  }

  const lastDecade = past.filter((entry) => entry.year >= proposedYear - 10);
  const sameCount = lastDecade.filter((entry) => entry.crop === proposed).length;
  if (lastDecade.length >= 4 && sameCount / lastDecade.length > 0.4) {
    add('HIGH_FREQUENCY', -10, `${profile.name} a ocupat parcela în ${sameCount} din ultimii ${lastDecade.length} ani înregistrați.`);
  }

  if (past.length === 0) {
    add('NO_HISTORY', -5, 'Parcela nu are istoric de culturi, deci evaluarea este orientativă.');
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
      ordered[0]?.message ?? `${profile.name} nu are restricții de rotație pe această parcelă.`,
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
