import { COPILOT_TASK_TYPES } from '@fermapp/domain';

export const parseNumber = (value: string): number => Number(value.replace(',', '.').trim());

export const isPositive = (value: string) => {
  const number = parseNumber(value);
  return value.trim() !== '' && Number.isFinite(number) && number > 0;
};

export const ha = (value: number) => `${value.toLocaleString('en-GB', { maximumFractionDigits: 2 })} ha`;

export const number = (value: number, digits = 1) => value.toLocaleString('en-GB', { maximumFractionDigits: digits });

export function duration(totalSeconds: number) {
  const seconds = Math.max(0, Math.round(totalSeconds));
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}

export function hoursMinutes(totalSeconds: number) {
  const minutes = Math.max(0, Math.round(totalSeconds / 60));
  const hours = Math.floor(minutes / 60);
  return hours > 0 ? `${hours}h ${minutes % 60}m` : `${minutes}m`;
}

export const clock = (date: Date | string) =>
  new Date(date).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' });

export const shortDate = (date: Date | string) =>
  new Date(date).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' });

export const fullDate = (date: Date | string) =>
  new Date(date).toLocaleDateString('en-GB', { day: 'numeric', month: 'long', year: 'numeric' });

/** Accepts a date in DD.MM.YYYY format and returns null if it is not valid. */
export function parseRoDate(value: string): Date | null {
  const match = /^(\d{1,2})\.(\d{1,2})\.(\d{4})$/.exec(value.trim());
  if (!match) return null;
  const [, d, m, y] = match.map(Number) as [number, number, number, number];
  const date = new Date(y, m - 1, d, 12);
  return date.getMonth() === m - 1 && date.getDate() === d ? date : null;
}

export const toRoDate = (date: Date) =>
  `${String(date.getDate()).padStart(2, '0')}.${String(date.getMonth() + 1).padStart(2, '0')}.${date.getFullYear()}`;

export const WORK_TYPES: Record<string, string> = {
  PLOWING: 'Ploughing',
  DISCING: 'Discing',
  SUBSOILING: 'Subsoiling',
  SOWING: 'Sowing',
  FERTILIZING: 'Fertilizing',
  SPRAYING: 'Spraying',
  HARVESTING: 'Harvesting',
};

export const MACHINE_TYPES: Record<string, string> = {
  TRACTOR: 'Tractor',
  COMBINE: 'Combine',
  SPRAYER: 'Self-propelled sprayer',
  SEEDER: 'Seed drill',
  DISC: 'Disc',
  CULTIVATOR: 'Cultivator',
  SUBSOILER: 'Subsoiler',
  TRAILER: 'Trailer',
};

export const IRRIGATION_TYPES: Record<string, string> = { REEL: 'Hose reel', PIVOT: 'Pivot', LINEAR: 'Linear' };

/** Task types the copilot plans beyond the original list; manual tasks can use them too. */
const COPILOT_TYPES: Record<string, string> = Object.fromEntries(Object.entries(COPILOT_TASK_TYPES).map(([code, item]) => [code, item.label]));

export const TASK_TYPES: Record<string, string> = {
  ...COPILOT_TYPES,
  FERTILIZATION: 'Fertilization',
  HERBICIDE: 'Herbicide application',
  FUNGICIDE: 'Fungicide application',
  INSECTICIDE: 'Insecticide application',
  PEST_CHECK: 'Pest check',
  DISEASE_CHECK: 'Disease check',
  MOISTURE_CHECK: 'Moisture check',
  LEAF_ANALYSIS: 'Leaf analysis',
  HARVEST: 'Harvest',
  SOIL_PREP: 'Seedbed preparation',
  SEED_PURCHASE: 'Seed purchase',
  SOWING: 'Sowing',
  EMERGENCE_CHECK: 'Emergence check',
  HARVEST_PREP: 'Harvest preparation',
};

export const PRIORITIES: Record<string, string> = { LOW: 'Low', MEDIUM: 'Medium', HIGH: 'High', URGENT: 'Urgent' };

export const ROLE_LABELS: Record<string, string> = {
  ADMIN: 'Administrator',
  FARM_MANAGER: 'Farm Manager',
  OPERATOR: 'Machine Operator',
  AGRONOMIST: 'Agronomist Consultant',
};
