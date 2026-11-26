import { COPILOT_TASK_TYPES } from './copilot/tasks';
import type { TaskCategory } from './copilot/types';

/**
 * The agricultural calendar: colour-coded operations, resource conflicts,
 * weather warnings, field timelines and the planning dashboard. Days are
 * local calendar days, YYYY-MM-DD.
 */

export type CalendarCategory = TaskCategory;

export interface CategoryStyle {
  label: string;
  /** Validated against the app's dark surfaces; tillage is soil brown, harvest red. */
  color: string;
  icon: string;
}

export const CALENDAR_CATEGORIES: Record<CalendarCategory, CategoryStyle> = {
  TILLAGE: { label: 'Tillage', color: '#A9774B', icon: 'shovel' },
  PLANTING: { label: 'Planting', color: '#5FB35A', icon: 'seed-outline' },
  IRRIGATION: { label: 'Irrigation', color: '#4C9FD6', icon: 'water-outline' },
  FERTILIZATION: { label: 'Fertilization', color: '#E2C23D', icon: 'sprout' },
  CROP_PROTECTION: { label: 'Crop protection', color: '#A87BD8', icon: 'spray' },
  HARVEST: { label: 'Harvest', color: '#E0584A', icon: 'barley' },
  MONITORING: { label: 'Field inspection', color: '#D9D6C8', icon: 'magnify' },
};

export const CATEGORY_ORDER: readonly CalendarCategory[] = ['TILLAGE', 'PLANTING', 'IRRIGATION', 'FERTILIZATION', 'CROP_PROTECTION', 'HARVEST', 'MONITORING'];

/** Work types of the operation log, mapped to calendar categories. */
const WORK_CATEGORY: Record<string, CalendarCategory> = {
  PLOWING: 'TILLAGE',
  DISCING: 'TILLAGE',
  SUBSOILING: 'TILLAGE',
  SOWING: 'PLANTING',
  FERTILIZING: 'FERTILIZATION',
  SPRAYING: 'CROP_PROTECTION',
  HARVESTING: 'HARVEST',
  REEL: 'IRRIGATION',
  PIVOT: 'IRRIGATION',
  LINEAR: 'IRRIGATION',
};

export function categoryOf(type: string): CalendarCategory {
  return COPILOT_TASK_TYPES[type]?.category ?? WORK_CATEGORY[type] ?? 'MONITORING';
}

// ------------------------------------------------------------------ dates

const DAY = 86_400_000;
const at = (day: string) => Date.parse(`${day.slice(0, 10)}T12:00:00Z`);
export const shiftDay = (day: string, days: number) => new Date(at(day) + days * DAY).toISOString().slice(0, 10);
export const dayDiff = (from: string, to: string) => Math.round((at(to) - at(from)) / DAY);
const weekday = (day: string) => new Date(at(day)).getUTCDay();

/** Monday of the week containing the day. */
export const weekStart = (day: string) => shiftDay(day, -((weekday(day) + 6) % 7));
export const weekDays = (day: string) => Array.from({ length: 7 }, (_, i) => shiftDay(weekStart(day), i));

/** Weeks (Monday first) covering the month of the day, each with 7 days. */
export function monthGrid(day: string): string[][] {
  const first = `${day.slice(0, 7)}-01`;
  const start = weekStart(first);
  const month = day.slice(0, 7);
  const weeks: string[][] = [];
  for (let cursor = start; weeks.length < 6; cursor = shiftDay(cursor, 7)) {
    const week = Array.from({ length: 7 }, (_, i) => shiftDay(cursor, i));
    if (weeks.length > 0 && !week.some((item) => item.slice(0, 7) === month)) break;
    weeks.push(week);
  }
  return weeks;
}

/** The farming season around a day: autumn sowing to the next harvest, September to August. */
export function seasonRange(day: string): { start: string; end: string; label: string } {
  const year = Number(day.slice(0, 4));
  const startYear = Number(day.slice(5, 7)) >= 9 ? year : year - 1;
  return { start: `${startYear}-09-01`, end: `${startYear + 1}-08-31`, label: `${startYear}/${String(startYear + 1).slice(2)}` };
}

// ------------------------------------------------------------------ entries

export interface CalendarTask {
  id: string;
  title: string;
  type: string;
  parcelId: string | null;
  priority: 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT';
  status: 'TODO' | 'IN_PROGRESS' | 'DONE';
  /** Local day. */
  day: string;
  assigneeId: string | null;
  machineId: string | null;
  source?: 'AI' | 'USER' | 'PLAN';
}

export interface CalendarRun {
  id: string;
  kind: 'WORK' | 'IRRIGATION';
  type: string;
  parcelId: string;
  status: string;
  startDay: string;
  endDay: string | null;
  machineId: string | null;
  operatorId: string | null;
}

export interface Resource {
  id: string;
  name: string;
  /** Machine type, or the member's role. */
  type: string;
}

export interface CalendarDay {
  date: string;
  tMinC: number;
  tMaxC: number;
  precipitationMm: number;
  rainProbabilityPct: number | null;
  windMaxKmh: number | null;
}

export const isOpen = (task: CalendarTask) => task.status !== 'DONE';
export const isOverdue = (task: CalendarTask, today: string) => isOpen(task) && task.day < today;

// ------------------------------------------------------------------ conflicts

export interface ResourceConflict {
  key: string;
  kind: 'MACHINE' | 'OPERATOR' | 'CAPACITY';
  day: string;
  taskIds: string[];
  title: string;
  reason: string;
}

const FIELD_MACHINES = new Set(['TRACTOR', 'COMBINE', 'SPRAYER', 'SEEDER', 'DISC', 'CULTIVATOR', 'SUBSOILER', 'TRAILER']);

/** Same machine or operator in two places on one day, or more jobs than machines of a type. */
export function detectConflicts(input: {
  tasks: readonly CalendarTask[];
  runs?: readonly CalendarRun[];
  machines: readonly Resource[];
  members: readonly Resource[];
  parcelName?: (id: string | null) => string;
}): ResourceConflict[] {
  const conflicts: ResourceConflict[] = [];
  const name = input.parcelName ?? ((id: string | null) => id ?? 'farm');
  const open = input.tasks.filter(isOpen);
  const byDay = new Map<string, CalendarTask[]>();
  for (const task of open) byDay.set(task.day, [...(byDay.get(task.day) ?? []), task]);

  for (const [day, tasks] of byDay) {
    const running = (input.runs ?? []).filter((run) => run.status === 'RUNNING' || run.status === 'PAUSED');
    for (const machine of input.machines) {
      const using = tasks.filter((task) => task.machineId === machine.id);
      const runningElsewhere = running.find((run) => run.machineId === machine.id && run.startDay <= day && (run.endDay ?? day) >= day && !using.some((task) => task.parcelId === run.parcelId));
      const places = new Set(using.map((task) => task.parcelId));
      if (places.size >= 2 || (using.length && runningElsewhere)) {
        conflicts.push({
          key: `machine-${machine.id}-${day}`,
          kind: 'MACHINE',
          day,
          taskIds: using.map((task) => task.id),
          title: `${machine.name} double-booked`,
          reason: runningElsewhere
            ? `${machine.name} is still working on ${name(runningElsewhere.parcelId)} while ${using.map((task) => `“${task.title}”`).join(', ')} needs it.`
            : `${machine.name} is scheduled on ${places.size} fields the same day: ${[...places].map(name).join(', ')}.`,
        });
      }
    }
    for (const member of input.members) {
      const assigned = tasks.filter((task) => task.assigneeId === member.id && (COPILOT_TASK_TYPES[task.type]?.machines.length ?? 0) > 0);
      const places = new Set(assigned.map((task) => task.parcelId));
      if (places.size >= 2) {
        conflicts.push({ key: `operator-${member.id}-${day}`, kind: 'OPERATOR', day, taskIds: assigned.map((task) => task.id), title: `${member.name} assigned twice`, reason: `${member.name} has field work on ${places.size} fields the same day: ${[...places].map(name).join(', ')}.` });
      }
    }
    // Jobs without a named machine still need one of the right type.
    const demand = new Map<string, CalendarTask[]>();
    for (const task of tasks) {
      if (task.machineId) continue;
      const type = COPILOT_TASK_TYPES[task.type]?.machines[0];
      if (!type || !FIELD_MACHINES.has(type) || type === 'TRACTOR') continue;
      demand.set(type, [...(demand.get(type) ?? []), task]);
    }
    for (const [type, jobs] of demand) {
      const fleet = input.machines.filter((machine) => machine.type === type).length;
      const parcels = new Set(jobs.map((task) => task.parcelId));
      if (parcels.size > fleet) {
        const label = type.toLowerCase();
        conflicts.push({
          key: `capacity-${type}-${day}`,
          kind: 'CAPACITY',
          day,
          taskIds: jobs.map((task) => task.id),
          title: fleet ? `Not enough ${label}s` : `No ${label} in the fleet`,
          reason: fleet ? `${parcels.size} fields need a ${label} the same day, but the fleet has ${fleet}.` : `${jobs.map((task) => `“${task.title}”`).join(', ')} need${jobs.length === 1 ? 's' : ''} a ${label}, and the fleet has none.`,
        });
      }
    }
  }
  return conflicts.sort((a, b) => a.day.localeCompare(b.day));
}

// ------------------------------------------------------------------ weather

export interface WeatherWarning {
  level: 'CAUTION' | 'WARNING';
  title: string;
  reason: string;
}

/** What the forecast means for a planned operation. */
export function weatherWarnings(task: Pick<CalendarTask, 'type' | 'day'>, days: readonly CalendarDay[]): WeatherWarning[] {
  const day = days.find((item) => item.date === task.day);
  if (!day) return [];
  const next = days.find((item) => item.date === shiftDay(task.day, 1));
  const category = categoryOf(task.type);
  const warnings: WeatherWarning[] = [];
  const wind = day.windMaxKmh ?? 0;
  const rain24 = day.precipitationMm + (next?.precipitationMm ?? 0) * 0.5;
  if (category === 'CROP_PROTECTION' || task.type === 'FOLIAR_FEEDING') {
    if (wind > 25) warnings.push({ level: 'WARNING', title: 'Spraying not recommended', reason: `Wind up to ${Math.round(wind)} km/h causes drift.` });
    else if (wind > 15) warnings.push({ level: 'CAUTION', title: 'Windy for spraying', reason: `Wind up to ${Math.round(wind)} km/h: spray early morning with drift-reducing nozzles.` });
    if (day.precipitationMm >= 1 || (day.rainProbabilityPct ?? 0) >= 60) warnings.push({ level: 'WARNING', title: task.type === 'HERBICIDE' ? 'Delay herbicide application' : 'Delay the treatment', reason: `Rain expected within 24 hours (${Math.round(day.precipitationMm)} mm, ${day.rainProbabilityPct ?? '?'}%) would wash the product off.` });
  }
  if (category === 'FERTILIZATION' && rain24 >= 15) warnings.push({ level: 'WARNING', title: 'Leaching risk', reason: `${Math.round(rain24)} mm of rain around the application washes nitrogen out.` });
  if (category === 'HARVEST') {
    if (day.precipitationMm >= 10) warnings.push({ level: 'WARNING', title: 'Harvest risk', reason: `Heavy rainfall expected (${Math.round(day.precipitationMm)} mm): grain moisture and field access suffer.` });
    else if (day.precipitationMm >= 2) warnings.push({ level: 'CAUTION', title: 'Wet harvest', reason: `${Math.round(day.precipitationMm)} mm of rain raises grain moisture and drying costs.` });
  }
  if ((category === 'TILLAGE' || category === 'PLANTING') && day.precipitationMm >= 10) warnings.push({ level: 'WARNING', title: 'Soil too wet', reason: `${Math.round(day.precipitationMm)} mm of rain: working wet soil causes compaction.` });
  if (category === 'PLANTING' && day.tMinC <= 2) warnings.push({ level: 'CAUTION', title: 'Cold for sowing', reason: `Night temperature down to ${Math.round(day.tMinC)} °C slows emergence.` });
  if (category === 'IRRIGATION' && day.precipitationMm >= 10) warnings.push({ level: 'CAUTION', title: 'Rain will water the field', reason: `${Math.round(day.precipitationMm)} mm of rain is forecast: irrigation may be unnecessary.` });
  return warnings;
}

/** The nearest day from `from` without a weather warning for the operation, within the forecast. */
export function bestDayFor(type: string, from: string, days: readonly CalendarDay[]): string | null {
  return days.find((day) => day.date >= from && weatherWarnings({ type, day: day.date }, days).every((warning) => warning.level !== 'WARNING'))?.date ?? null;
}

// ------------------------------------------------------------------ timeline

export interface TimelineEntry {
  id: string;
  day: string;
  endDay: string | null;
  category: CalendarCategory;
  label: string;
  state: 'DONE' | 'RUNNING' | 'PLANNED' | 'OVERDUE';
  source: 'TASK' | 'WORK' | 'IRRIGATION' | 'SEASON';
}

/** Everything that happened or is planned on one field within a period, in date order. */
export function fieldTimeline(input: {
  parcelId: string;
  tasks: readonly CalendarTask[];
  runs: readonly CalendarRun[];
  seasons?: readonly { year: number; cropName: string; sowingDate: string | null; harvestDate: string | null }[];
  from: string;
  to: string;
  today: string;
  labelOf?: (type: string) => string;
}): TimelineEntry[] {
  const label = input.labelOf ?? ((type: string) => COPILOT_TASK_TYPES[type]?.label ?? type);
  const inside = (day: string) => day >= input.from && day <= input.to;
  const entries: TimelineEntry[] = [];
  for (const task of input.tasks) {
    if (task.parcelId !== input.parcelId || !inside(task.day)) continue;
    entries.push({ id: `t-${task.id}`, day: task.day, endDay: null, category: categoryOf(task.type), label: task.title, state: task.status === 'DONE' ? 'DONE' : task.status === 'IN_PROGRESS' ? 'RUNNING' : task.day < input.today ? 'OVERDUE' : 'PLANNED', source: 'TASK' });
  }
  for (const run of input.runs) {
    if (run.parcelId !== input.parcelId || !inside(run.startDay)) continue;
    entries.push({ id: `r-${run.id}`, day: run.startDay, endDay: run.endDay, category: categoryOf(run.type), label: run.kind === 'IRRIGATION' ? 'Irrigation' : label(run.type === 'DISCING' ? 'DISKING' : run.type === 'FERTILIZING' ? 'FERTILIZATION' : run.type === 'SPRAYING' ? 'HERBICIDE' : run.type === 'HARVESTING' ? 'HARVEST' : run.type), state: run.status === 'COMPLETED' ? 'DONE' : run.status === 'RUNNING' || run.status === 'PAUSED' ? 'RUNNING' : 'DONE', source: run.kind });
  }
  for (const season of input.seasons ?? []) {
    if (season.sowingDate && inside(season.sowingDate)) entries.push({ id: `s-${season.year}-sow`, day: season.sowingDate, endDay: null, category: 'PLANTING', label: `${season.cropName} sown`, state: 'DONE', source: 'SEASON' });
    if (season.harvestDate && inside(season.harvestDate)) entries.push({ id: `s-${season.year}-harvest`, day: season.harvestDate, endDay: null, category: 'HARVEST', label: `${season.cropName} harvested`, state: season.harvestDate <= input.today ? 'DONE' : 'PLANNED', source: 'SEASON' });
  }
  return entries.sort((a, b) => a.day.localeCompare(b.day) || a.label.localeCompare(b.label));
}

// ------------------------------------------------------------------ dashboard

export interface CalendarDashboard {
  today: CalendarTask[];
  thisWeek: CalendarTask[];
  delayed: CalendarTask[];
  availableMachines: Resource[];
  availableOperators: Resource[];
  critical: CalendarTask[];
}

export function calendarDashboard(input: { tasks: readonly CalendarTask[]; runs: readonly CalendarRun[]; machines: readonly Resource[]; operators: readonly Resource[]; today: string }): CalendarDashboard {
  const { today } = input;
  const open = input.tasks.filter(isOpen).sort((a, b) => a.day.localeCompare(b.day));
  const weekEnd = shiftDay(weekStart(today), 6);
  const todays = open.filter((task) => task.day === today);
  const active = input.runs.filter((run) => run.status === 'RUNNING' || run.status === 'PAUSED');
  const busyMachines = new Set([...todays.map((task) => task.machineId), ...active.map((run) => run.machineId)].filter(Boolean));
  const busyPeople = new Set([...todays.map((task) => task.assigneeId), ...active.map((run) => run.operatorId)].filter(Boolean));
  return {
    today: todays,
    thisWeek: open.filter((task) => task.day >= today && task.day <= weekEnd),
    delayed: open.filter((task) => task.day < today),
    availableMachines: input.machines.filter((machine) => !busyMachines.has(machine.id)),
    availableOperators: input.operators.filter((member) => !busyPeople.has(member.id)),
    critical: open.filter((task) => (task.priority === 'HIGH' || task.priority === 'URGENT') && task.day >= today && dayDiff(today, task.day) <= 7),
  };
}
