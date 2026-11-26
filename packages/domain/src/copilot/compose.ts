import { CROPS, type CropCode } from '../crops';
import { formatEuro } from '../money';
import type { ParcelFacts, WeatherFacts } from './facts';
import { daysBetween, type DateExpression } from './language';
import type { CopilotAnswer, CopilotMemory, CopilotParcel, CopilotTopic, FarmSnapshot } from './types';

/** Everything an answer builder needs. */
export interface Context {
  /** The normalized question. */
  text: string;
  snapshot: FarmSnapshot;
  today: string;
  parcel: CopilotParcel | null;
  crop: CropCode | null;
  memory: CopilotMemory;
  /** The question is about several parcels or the whole farm. */
  farmScope: boolean;
  weather: WeatherFacts | null;
  date: DateExpression;
}

export function blank(topic: CopilotTopic, ctx: Context, parcelId: string | null = ctx.parcel?.id ?? null): CopilotAnswer {
  return {
    topic,
    parcelId,
    crop: ctx.crop,
    direct: '',
    reasoning: [],
    risks: [],
    alternatives: [],
    economics: [],
    nextSteps: [],
    proposals: [],
    changes: [],
    dataGaps: [],
    confidence: 'MEDIUM',
  };
}

export const euro = (value: number) => formatEuro(Math.round(value));
export const euroHa = (value: number) => `${formatEuro(Math.round(value))}/ha`;
export const ha = (value: number) => `${Math.round(value * 100) / 100} ha`;
export const t = (value: number) => `${Math.round(value * 10) / 10}`;
export const cropName = (crop: CropCode) => CROPS[crop].name.toLowerCase();
export const signedPct = (value: number) => `${value > 0 ? '+' : ''}${Math.round(value)}%`;

const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** "today", "tomorrow", "Fri 14 Jun". */
export function dayLabel(day: string, today: string): string {
  const offset = daysBetween(today, day);
  if (offset === 0) return 'today';
  if (offset === 1) return 'tomorrow';
  const date = new Date(`${day.slice(0, 10)}T12:00:00Z`);
  return `${WEEKDAYS[date.getUTCDay()]} ${date.getUTCDate()} ${MONTHS[date.getUTCMonth()]}`;
}

/** "in 3 days", "2 days ago". */
export function relativeDays(day: string, today: string): string {
  const offset = daysBetween(today, day);
  if (offset === 0) return 'today';
  if (offset === 1) return 'tomorrow';
  if (offset === -1) return 'yesterday';
  return offset > 0 ? `in ${offset} days` : `${-offset} days ago`;
}

export const list = (items: readonly string[]) =>
  items.length < 2 ? (items[0] ?? '') : `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`;

/** Missing data that limits an answer about a parcel. */
export function parcelGaps(facts: ParcelFacts, ctx: Context, needs: { soil?: boolean; history?: boolean; weather?: boolean; costs?: boolean } = {}): string[] {
  const gaps: string[] = [];
  if (needs.soil && !facts.parcel.soil) gaps.push(`No soil analysis for ${facts.parcel.name}: open the parcel to load it from SoilGrids, or record a lab analysis.`);
  if (needs.history && facts.yields.length === 0) gaps.push(`No recorded yields for ${facts.parcel.name}: estimates use regional reference yields.`);
  if (needs.weather && !ctx.weather) gaps.push('The forecast is not available (no connection): weather advice is limited.');
  if (needs.costs && ctx.snapshot.canSeeFinance && !facts.parcel.economics) gaps.push(`No costs recorded for ${facts.parcel.name} this season: economic figures are estimates from typical costs.`);
  return gaps;
}

export function confidenceOf(gaps: readonly string[]): CopilotAnswer['confidence'] {
  return gaps.length === 0 ? 'HIGH' : gaps.length <= 1 ? 'MEDIUM' : 'LOW';
}
