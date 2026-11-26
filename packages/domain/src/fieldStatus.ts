/**
 * The real state of a parcel, from its plan of work rather than from the last
 * operation: is something running, is anything late, is work due today, is
 * more work coming, or is everything planned done.
 */

/** In this order in charts: blue sits between the warm states so neighbours stay distinct for colour-blind readers. */
export const FIELD_STATES = ['OVERDUE', 'DUE_TODAY', 'ACTIVE', 'SCHEDULED', 'COMPLETE'] as const;
export type FieldState = (typeof FIELD_STATES)[number];

/**
 * Map colours: red, orange, blue, yellow, green, validated as a set on the app's
 * dark surfaces. Every state also carries an icon and a label, never colour alone.
 */
export const FIELD_STATE_COLORS: Record<FieldState, string> = {
  OVERDUE: '#D93C74',
  DUE_TODAY: '#CF6A10',
  ACTIVE: '#3B8FD6',
  SCHEDULED: '#A99212',
  COMPLETE: '#109C86',
};

export const FIELD_STATE_LABELS: Record<FieldState, string> = {
  OVERDUE: 'Overdue work',
  ACTIVE: 'Work in progress',
  DUE_TODAY: 'Work due today',
  SCHEDULED: 'Work scheduled',
  COMPLETE: 'All work completed',
};

/** Work planned within this many days counts as the active period. */
export const ACTIVE_PERIOD_DAYS = 7;

export interface FieldTask {
  id: string;
  title: string;
  type?: string;
  status: 'TODO' | 'IN_PROGRESS' | 'DONE';
  dueDate: string;
}

export interface FieldStatus {
  state: FieldState;
  /** Short badge text, for example "2 tasks left" or "Work scheduled tomorrow". */
  badge: string;
  overdue: number;
  dueToday: number;
  /** Open tasks due later within the active period. */
  upcoming: number;
  /** Every open task in the active period, late ones included. */
  pending: number;
  /** Open tasks due after the active period. */
  later: number;
  nextDue: FieldTask | null;
  /** Share of the season's planned tasks that are done, 0-100; null when nothing was planned. */
  completionScore: number | null;
  done: number;
  total: number;
}

const DAY_MS = 86_400_000;
const startOfDay = (date: Date) => new Date(date.getFullYear(), date.getMonth(), date.getDate());
const plural = (count: number, one: string, many: string) => `${count} ${count === 1 ? one : many}`;

export function fieldStatus(input: { tasks: readonly FieldTask[]; activeWork: number; now?: Date; periodDays?: number }): FieldStatus {
  const now = input.now ?? new Date();
  const today = startOfDay(now).getTime();
  const tomorrow = today + DAY_MS;
  const periodEnd = today + (input.periodDays ?? ACTIVE_PERIOD_DAYS + 1) * DAY_MS;
  const year = now.getFullYear();

  let overdue = 0;
  let dueToday = 0;
  let dueTodayNotStarted = 0;
  let upcoming = 0;
  let later = 0;
  let nextDue: FieldTask | null = null;
  let done = 0;
  let total = 0;

  for (const task of input.tasks) {
    const due = new Date(task.dueDate).getTime();
    if (new Date(task.dueDate).getFullYear() === year) {
      total += 1;
      if (task.status === 'DONE') done += 1;
    }
    if (task.status === 'DONE') continue;
    if (due < today) overdue += 1;
    else if (due < tomorrow) {
      dueToday += 1;
      if (task.status === 'TODO') dueTodayNotStarted += 1;
    } else if (due < periodEnd) upcoming += 1;
    else later += 1;
    if (due >= today && (!nextDue || due < new Date(nextDue.dueDate).getTime())) nextDue = task;
  }

  const inProgress = input.tasks.some((task) => task.status === 'IN_PROGRESS' && new Date(task.dueDate).getTime() < tomorrow);
  const pending = overdue + dueToday + upcoming;
  // Lateness needs attention first; then work happening now; then work due today that has not started.
  const state: FieldState =
    overdue > 0 ? 'OVERDUE' : input.activeWork > 0 || inProgress ? 'ACTIVE' : dueTodayNotStarted > 0 ? 'DUE_TODAY' : pending > 0 ? 'SCHEDULED' : 'COMPLETE';

  const nextIsTomorrow = nextDue ? new Date(nextDue.dueDate).getTime() < tomorrow + DAY_MS && new Date(nextDue.dueDate).getTime() >= tomorrow : false;
  const badge =
    state === 'OVERDUE'
      ? plural(overdue, 'task overdue', 'tasks overdue')
      : state === 'ACTIVE'
        ? pending > 0
          ? `Work in progress, ${plural(pending, 'task', 'tasks')} left`
          : 'Work in progress'
        : state === 'DUE_TODAY'
          ? plural(dueToday, 'task due today', 'tasks due today')
          : state === 'SCHEDULED'
            ? pending === 1 && nextIsTomorrow
              ? 'Work scheduled tomorrow'
              : `${plural(pending, 'scheduled activity', 'scheduled activities')}`
            : 'All work completed';

  return {
    state,
    badge,
    overdue,
    dueToday,
    upcoming,
    pending,
    later,
    nextDue,
    completionScore: total > 0 ? Math.round((done / total) * 100) : null,
    done,
    total,
  };
}

export interface FieldStatusSummary {
  complete: number;
  active: number;
  /** Parcels with work still planned in the active period (due today or later). */
  pending: number;
  overdue: number;
  /** Open tasks due in the next seven days across the farm. */
  next7Days: number;
  averageScore: number | null;
  mostAdvanced: { parcelId: string; name: string; score: number }[];
  /** Late or due today and not started: these need someone now. */
  urgent: { parcelId: string; name: string; status: FieldStatus }[];
}

export function summarizeFieldStatus(items: readonly { parcelId: string; name: string; status: FieldStatus }[]): FieldStatusSummary {
  const scored = items.filter((item) => item.status.completionScore !== null);
  return {
    complete: items.filter((item) => item.status.state === 'COMPLETE').length,
    active: items.filter((item) => item.status.state === 'ACTIVE').length,
    pending: items.filter((item) => item.status.state === 'SCHEDULED' || item.status.state === 'DUE_TODAY').length,
    overdue: items.filter((item) => item.status.state === 'OVERDUE').length,
    next7Days: items.reduce((sum, item) => sum + item.status.dueToday + item.status.upcoming, 0),
    averageScore: scored.length ? Math.round(scored.reduce((sum, item) => sum + item.status.completionScore!, 0) / scored.length) : null,
    mostAdvanced: scored
      .map((item) => ({ parcelId: item.parcelId, name: item.name, score: item.status.completionScore! }))
      .sort((a, b) => b.score - a.score)
      .slice(0, 5),
    urgent: items
      .filter((item) => item.status.state === 'OVERDUE' || item.status.state === 'DUE_TODAY')
      .sort((a, b) => b.status.overdue - a.status.overdue || b.status.dueToday - a.status.dueToday),
  };
}

/** Which planned tasks an operation of each type carries out. */
export const WORK_TO_TASK_TYPES: Record<string, string[]> = {
  PLOWING: ['SOIL_PREP'],
  DISCING: ['SOIL_PREP'],
  SUBSOILING: ['SOIL_PREP'],
  SOWING: ['SOWING'],
  FERTILIZING: ['FERTILIZATION'],
  SPRAYING: ['HERBICIDE', 'FUNGICIDE', 'INSECTICIDE'],
  HARVESTING: ['HARVEST'],
};

/** A finished operation may close a task planned up to this many days later. */
const EARLY_WINDOW_DAYS = 3;

/**
 * The open task a finished operation carries out: same parcel, matching type,
 * due by a few days after the operation, the earliest first.
 */
export function taskForFinishedWork<T extends { status: string; dueDate: string | Date; type: string }>(tasks: readonly T[], workType: string, finishedAt: Date): T | null {
  const types = WORK_TO_TASK_TYPES[workType] ?? [];
  const limit = startOfDay(finishedAt).getTime() + (EARLY_WINDOW_DAYS + 1) * DAY_MS;
  return (
    tasks
      .filter((task) => task.status !== 'DONE' && types.includes(task.type) && new Date(task.dueDate).getTime() < limit)
      .sort((a, b) => new Date(a.dueDate).getTime() - new Date(b.dueDate).getTime())[0] ?? null
  );
}
