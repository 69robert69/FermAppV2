import { CROP_CODES, type CropCode } from '../crops';
import type { TillageSystem } from '../tillage';
import type { CopilotParcel } from './types';

/**
 * Light language understanding for farm questions, in English and Romanian.
 * Everything is matched on lower-case text without diacritics.
 */

export const normalize = (text: string) =>
  text
    .toLowerCase()
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/[’`]/g, "'")
    .replace(/\s+/g, ' ')
    .trim();

/**
 * True when any keyword starts a word or phrase in the text ("fertiliz" matches
 * "fertilize"). A keyword ending in `$` must match a whole word ("late$" does not match "later").
 */
export function mentions(text: string, keywords: readonly string[]): boolean {
  return keywords.some((keyword) => {
    const whole = keyword.endsWith('$');
    const body = (whole ? keyword.slice(0, -1) : keyword).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    return new RegExp(`(^|[^a-z0-9])${body}${whole ? '(?![a-z0-9])' : ''}`).test(text);
  });
}

const CROP_WORDS: Record<CropCode, readonly string[]> = {
  WHEAT: ['wheat', 'grau', 'griu'],
  BARLEY: ['barley', 'orz'],
  CORN: ['corn', 'maize', 'porumb'],
  SUNFLOWER: ['sunflower', 'floarea soarelui', 'floarea-soarelui', 'floare'],
  RAPESEED: ['rapeseed', 'rape', 'canola', 'rapita'],
  SOY: ['soy', 'soya', 'soybean', 'soia'],
  ALFALFA: ['alfalfa', 'lucerne', 'lucerna'],
  OAT: ['oat', 'oats', 'ovaz'],
  POTATO: ['potato', 'potatoes', 'cartof', 'cartofi'],
  BEET: ['beet', 'sugar beet', 'sfecla'],
  VEGETABLES: ['vegetable', 'vegetables', 'legume'],
};

export function findCrop(text: string): CropCode | null {
  for (const code of CROP_CODES) if (mentions(text, CROP_WORDS[code])) return code;
  return null;
}

const TILLAGE_WORDS: Record<TillageSystem, readonly string[]> = {
  NO_TILL: ['no-till', 'no till', 'notill', 'semanat direct', 'direct sowing'],
  STRIP_TILL: ['strip-till', 'strip till', 'strip'],
  MINIMUM_TILL: ['minimum till', 'minimum-till', 'min-till', 'min till', 'minimum tillage', 'lucrari minime'],
  CONVENTIONAL: ['conventional', 'plough', 'plow', 'arat$', 'aratura'],
};

export function findTillage(text: string): TillageSystem | null {
  for (const code of ['NO_TILL', 'STRIP_TILL', 'MINIMUM_TILL', 'CONVENTIONAL'] as const) if (mentions(text, TILLAGE_WORDS[code])) return code;
  return null;
}

const FIELD_WORDS = ['field', 'fields', 'parcel', 'parcels', 'parcela', 'parcele', 'parcelei', 'tarla', 'tarlaua', 'sola', 'camp', 'campul', 'lot'];
const THIS_FIELD = ['this field', 'this parcel', 'that field', 'that parcel', 'here', 'it', 'aceasta parcela', 'parcela asta', 'campul asta', 'tarlaua asta', 'aici', 'acolo'];

/** The parcel named in the question: by name, by code, or "field 12" style numbers. */
export function findParcel(text: string, parcels: readonly CopilotParcel[]): CopilotParcel | null {
  const named = [...parcels]
    .filter((parcel) => parcel.name.trim().length >= 2)
    .sort((a, b) => b.name.length - a.name.length)
    .find((parcel) => {
      const name = normalize(parcel.name);
      return name.length >= 3 ? text.includes(name) : mentions(text, [name]);
    });
  if (named) return named;
  const coded = parcels.find((parcel) => parcel.code && normalize(parcel.code).length >= 3 && text.includes(normalize(parcel.code)));
  if (coded) return coded;
  const number = new RegExp(`(?:${FIELD_WORDS.join('|')})\\s*(?:no\\.?|nr\\.?|#)?\\s*([a-z]?\\d+[a-z]?)\\b`).exec(text)?.[1];
  if (number) {
    const token = new RegExp(`(^|[^a-z0-9])${number}([^a-z0-9]|$)`);
    return parcels.find((parcel) => token.test(normalize(parcel.name)) || (parcel.code ? token.test(normalize(parcel.code)) : false)) ?? null;
  }
  return null;
}

export const refersToCurrentParcel = (text: string) => mentions(text, THIS_FIELD);

/** "which field", "all fields", "the farm": the question is about several parcels. */
export const asksAboutFarm = (text: string) =>
  mentions(text, ['which field', 'which parcel', 'which fields', 'which parcels', 'all fields', 'all parcels', 'farm', 'every field', 'fields', 'parcels', 'ce parcela', 'care parcela', 'care parcele', 'ce parcele', 'toate parcelele', 'ferma', 'fermei', 'parcelele', 'tarlalele', 'how many fields', 'cate parcele', 'tractors', 'tractoare', 'utilaje']);

// ------------------------------------------------------------------ dates

const DAY = 86_400_000;
const WEEKDAYS: readonly (readonly string[])[] = [
  ['sunday', 'duminica'],
  ['monday', 'luni'],
  ['tuesday', 'marti'],
  ['wednesday', 'miercuri'],
  ['thursday', 'joi'],
  ['friday', 'vineri'],
  ['saturday', 'sambata'],
];
const MONTHS: readonly (readonly string[])[] = [
  ['january', 'jan', 'ianuarie', 'ian'],
  ['february', 'feb', 'februarie'],
  ['march', 'mar', 'martie'],
  ['april', 'apr', 'aprilie'],
  ['may', 'mai'],
  ['june', 'jun', 'iunie'],
  ['july', 'jul', 'iulie'],
  ['august', 'aug'],
  ['september', 'sep', 'sept', 'septembrie'],
  ['october', 'oct', 'octombrie'],
  ['november', 'nov', 'noiembrie'],
  ['december', 'dec', 'decembrie'],
];
const NUMBER_WORDS: Record<string, number> = {
  one: 1, a: 1, an: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7, eight: 8, nine: 9, ten: 10, fourteen: 14,
  o: 1, un: 1, unu: 1, una: 1, doua: 2, doi: 2, trei: 3, patru: 4, cinci: 5, sase: 6, sapte: 7, opt: 8, noua: 9, zece: 10,
};

export const isoDay = (date: Date) => date.toISOString().slice(0, 10);
export const addDays = (day: string, days: number) => isoDay(new Date(Date.parse(`${day.slice(0, 10)}T12:00:00Z`) + days * DAY));
export const daysBetween = (from: string, to: string) => Math.round((Date.parse(`${to.slice(0, 10)}T12:00:00Z`) - Date.parse(`${from.slice(0, 10)}T12:00:00Z`)) / DAY);
export const weekdayIndex = (day: string) => new Date(`${day.slice(0, 10)}T12:00:00Z`).getUTCDay();

const amount = (token: string) => (/^\d+$/.test(token) ? Number(token) : NUMBER_WORDS[token] ?? null);

export interface DateExpression {
  /** An absolute target date, YYYY-MM-DD. */
  date: string | null;
  /** A shift of the current date, in days ("by one week"). */
  shiftDays: number | null;
}

/** Reads "Friday", "tomorrow", "next week", "in 10 days", "by one week", "15 September", "15.09". */
export function parseDate(text: string, today: string): DateExpression {
  const result: DateExpression = { date: null, shiftDays: null };
  const span = /(?:by|with|cu|for|pentru)\s+(\d+|[a-z]+)\s+(day|days|zi|zile|week|weeks|saptamana|saptamani)/.exec(text);
  if (span) {
    const n = amount(span[1]!);
    if (n) result.shiftDays = /week|sapt/.test(span[2]!) ? n * 7 : n;
  } else if (mentions(text, ['a week', 'one week', 'o saptamana']) && mentions(text, ['delay', 'postpone', 'push', 'amana', 'amanare', 'intarzie'])) {
    result.shiftDays = 7;
  }

  const relative = /(?:in|peste|within|over the next|next|\+)\s*(\d+|[a-z]+)\s+(day|days|zi|zile|week|weeks|saptamana|saptamani)/.exec(text);
  if (relative && !span) {
    const n = amount(relative[1]!);
    if (n) result.date = addDays(today, /week|sapt/.test(relative[2]!) ? n * 7 : n);
  }

  if (mentions(text, ['day after tomorrow', 'poimaine'])) result.date = addDays(today, 2);
  else if (mentions(text, ['tomorrow', 'maine'])) result.date = addDays(today, 1);
  else if (mentions(text, ['today', 'azi', 'astazi'])) result.date ??= today;
  if (mentions(text, ['next week', 'saptamana viitoare', 'saptamana urmatoare']) && !result.date) {
    const toMonday = ((8 - weekdayIndex(today)) % 7) || 7;
    result.date = addDays(today, toMonday);
  }

  WEEKDAYS.forEach((names, index) => {
    if (result.date || !mentions(text, names)) return;
    const ahead = ((index - weekdayIndex(today) + 7) % 7) || 7;
    result.date = addDays(today, ahead);
  });

  if (!result.date) {
    const year = Number(today.slice(0, 4));
    const pick = (day: number, month: number) => {
      if (day < 1 || day > 31 || month < 0 || month > 11) return null;
      const candidate = isoDay(new Date(Date.UTC(year, month, day, 12)));
      return candidate < today ? isoDay(new Date(Date.UTC(year + 1, month, day, 12))) : candidate;
    };
    const numeric = /\b(\d{1,2})[./](\d{1,2})(?:[./](\d{2,4}))?\b/.exec(text);
    if (numeric) {
      const explicitYear = numeric[3] ? Number(numeric[3].length === 2 ? `20${numeric[3]}` : numeric[3]) : null;
      result.date = explicitYear
        ? isoDay(new Date(Date.UTC(explicitYear, Number(numeric[2]) - 1, Number(numeric[1]), 12)))
        : pick(Number(numeric[1]), Number(numeric[2]) - 1);
    } else {
      MONTHS.forEach((names, month) => {
        if (result.date) return;
        const pattern = names.join('|');
        const dayFirst = new RegExp(`\\b(\\d{1,2})\\s+(?:${pattern})\\b`).exec(text);
        const monthFirst = new RegExp(`\\b(?:${pattern})\\s+(\\d{1,2})\\b`).exec(text);
        const day = dayFirst?.[1] ?? monthFirst?.[1];
        if (day) result.date = pick(Number(day), month);
      });
    }
  }
  return result;
}
