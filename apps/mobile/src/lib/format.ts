export const parseNumber = (value: string): number => Number(value.replace(',', '.').trim());

export const isPositive = (value: string) => {
  const number = parseNumber(value);
  return value.trim() !== '' && Number.isFinite(number) && number > 0;
};

export const ha = (value: number) => `${value.toLocaleString('ro-RO', { maximumFractionDigits: 2 })} ha`;

export const number = (value: number, digits = 1) => value.toLocaleString('ro-RO', { maximumFractionDigits: digits });

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
  new Date(date).toLocaleTimeString('ro-RO', { hour: '2-digit', minute: '2-digit' });

export const shortDate = (date: Date | string) =>
  new Date(date).toLocaleDateString('ro-RO', { day: 'numeric', month: 'short' });

export const fullDate = (date: Date | string) =>
  new Date(date).toLocaleDateString('ro-RO', { day: 'numeric', month: 'long', year: 'numeric' });

/** Acceptă data în format ZZ.LL.AAAA și returnează null dacă nu este validă. */
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
  PLOWING: 'Arat',
  DISCING: 'Discuit',
  SUBSOILING: 'Scarificat',
  SOWING: 'Semănat',
  FERTILIZING: 'Fertilizat',
  SPRAYING: 'Erbicidat',
  HARVESTING: 'Recoltat',
};

export const MACHINE_TYPES: Record<string, string> = {
  TRACTOR: 'Tractor',
  COMBINE: 'Combină',
  SPRAYER: 'Autopropulsată',
  SEEDER: 'Semănătoare',
  DISC: 'Disc',
  CULTIVATOR: 'Cultivator',
  SUBSOILER: 'Scarificator',
  TRAILER: 'Remorcă',
};

export const IRRIGATION_TYPES: Record<string, string> = { REEL: 'Tambur', PIVOT: 'Pivot', LINEAR: 'Liniar' };

export const TASK_TYPES: Record<string, string> = {
  FERTILIZATION: 'Fertilizare',
  HERBICIDE: 'Erbicidare',
  FUNGICIDE: 'Fungicidare',
  INSECTICIDE: 'Insecticidare',
  PEST_CHECK: 'Verificare dăunători',
  DISEASE_CHECK: 'Verificare boli',
  MOISTURE_CHECK: 'Verificare umiditate',
  LEAF_ANALYSIS: 'Analiză frunze',
  HARVEST: 'Recoltare',
  SOIL_PREP: 'Pregătirea terenului',
  SEED_PURCHASE: 'Achiziție sămânță',
  SOWING: 'Semănat',
  EMERGENCE_CHECK: 'Verificare răsărire',
  HARVEST_PREP: 'Pregătire recoltare',
};

export const PRIORITIES: Record<string, string> = { LOW: 'Scăzută', MEDIUM: 'Medie', HIGH: 'Ridicată', URGENT: 'Urgentă' };

export const ROLE_LABELS: Record<string, string> = {
  ADMIN: 'Administrator',
  FARM_MANAGER: 'Manager Fermă',
  OPERATOR: 'Operator Utilaj',
  AGRONOMIST: 'Consultant Agronom',
};
