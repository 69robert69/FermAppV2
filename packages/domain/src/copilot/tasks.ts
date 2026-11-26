import type { TillageSystem } from '../tillage';
import { addDays } from './language';
import type { CopilotPriority, CopilotTask, FarmSnapshot, TaskCategory, TaskProposal } from './types';

/**
 * Task types the copilot plans. Codes already used by the To-Do list keep their
 * meaning, so AI tasks behave exactly like tasks created by hand.
 */
export interface CopilotTaskType {
  label: string;
  category: TaskCategory;
  /** Machine types that can carry the work out, best first. */
  machines: readonly string[];
}

export const COPILOT_TASK_TYPES: Record<string, CopilotTaskType> = {
  PLOWING: { label: 'Plowing', category: 'TILLAGE', machines: ['TRACTOR'] },
  DISKING: { label: 'Disking', category: 'TILLAGE', machines: ['DISC', 'TRACTOR'] },
  SUBSOILING: { label: 'Subsoiling', category: 'TILLAGE', machines: ['SUBSOILER', 'TRACTOR'] },
  CHISELING: { label: 'Chiseling', category: 'TILLAGE', machines: ['CULTIVATOR', 'TRACTOR'] },
  STRIP_TILL: { label: 'Strip-Till', category: 'TILLAGE', machines: ['CULTIVATOR', 'TRACTOR'] },
  SOIL_PREP: { label: 'Seedbed preparation', category: 'TILLAGE', machines: ['CULTIVATOR', 'DISC', 'TRACTOR'] },
  SEED_PURCHASE: { label: 'Seed purchase', category: 'PLANTING', machines: [] },
  SOWING: { label: 'Sowing', category: 'PLANTING', machines: ['SEEDER', 'TRACTOR'] },
  FERTILIZATION: { label: 'Base fertilization', category: 'FERTILIZATION', machines: ['TRACTOR'] },
  NITROGEN: { label: 'Nitrogen fertilizer', category: 'FERTILIZATION', machines: ['TRACTOR'] },
  FOLIAR_FEEDING: { label: 'Foliar feeding', category: 'FERTILIZATION', machines: ['SPRAYER', 'TRACTOR'] },
  HERBICIDE: { label: 'Herbicide application', category: 'CROP_PROTECTION', machines: ['SPRAYER', 'TRACTOR'] },
  FUNGICIDE: { label: 'Fungicide application', category: 'CROP_PROTECTION', machines: ['SPRAYER', 'TRACTOR'] },
  INSECTICIDE: { label: 'Insecticide application', category: 'CROP_PROTECTION', machines: ['SPRAYER', 'TRACTOR'] },
  IRRIGATION_START: { label: 'Start irrigation', category: 'IRRIGATION', machines: [] },
  IRRIGATION_STOP: { label: 'Stop irrigation', category: 'IRRIGATION', machines: [] },
  IRRIGATION_CHECK: { label: 'Irrigation check', category: 'IRRIGATION', machines: [] },
  MOISTURE_CHECK: { label: 'Moisture check', category: 'IRRIGATION', machines: [] },
  CROP_SCOUTING: { label: 'Crop scouting', category: 'MONITORING', machines: [] },
  FIELD_INSPECTION: { label: 'Field inspection', category: 'MONITORING', machines: [] },
  DISEASE_CHECK: { label: 'Disease inspection', category: 'MONITORING', machines: [] },
  PEST_CHECK: { label: 'Pest check', category: 'MONITORING', machines: [] },
  WEED_MONITORING: { label: 'Weed monitoring', category: 'MONITORING', machines: [] },
  EMERGENCE_CHECK: { label: 'Emergence check', category: 'MONITORING', machines: [] },
  LEAF_ANALYSIS: { label: 'Leaf analysis', category: 'MONITORING', machines: [] },
  SOIL_SAMPLING: { label: 'Soil sampling', category: 'MONITORING', machines: [] },
  HARVEST_PREP: { label: 'Harvest preparation', category: 'HARVEST', machines: [] },
  HARVEST: { label: 'Harvest', category: 'HARVEST', machines: ['COMBINE'] },
  GRAIN_TRANSPORT: { label: 'Grain transport', category: 'HARVEST', machines: ['TRAILER', 'TRACTOR'] },
};

export const CATEGORY_LABELS: Record<TaskCategory, string> = {
  TILLAGE: 'Tillage',
  PLANTING: 'Planting',
  FERTILIZATION: 'Fertilization',
  CROP_PROTECTION: 'Crop protection',
  IRRIGATION: 'Irrigation',
  MONITORING: 'Monitoring',
  HARVEST: 'Harvest',
};

export const taskCategory = (type: string): TaskCategory | null => COPILOT_TASK_TYPES[type]?.category ?? null;

/** Words that name a kind of work, used to find the tasks a request refers to. */
export const CATEGORY_WORDS: Record<TaskCategory, readonly string[]> = {
  TILLAGE: ['tillage', 'cultivation', 'cultivating', 'plow', 'plough', 'disk', 'disc', 'subsoil', 'chisel', 'seedbed', 'arat', 'aratura', 'discuit', 'scarific', 'pregatire'],
  PLANTING: ['sowing', 'sow', 'planting', 'plant', 'seeding', 'semanat', 'semana', 'insamantare'],
  FERTILIZATION: ['fertiliz', 'fertilis', 'nitrogen', 'urea', 'npk', 'foliar', 'manure', 'ingrasa', 'azot', 'uree', 'fertilizare'],
  CROP_PROTECTION: ['spray', 'herbicide', 'fungicide', 'insecticide', 'protection', 'stropi', 'erbicid', 'tratament', 'fungicid', 'insecticid'],
  IRRIGATION: ['irrigat', 'watering', 'irigat', 'udat'],
  MONITORING: ['scouting', 'inspection', 'monitor', 'check', 'sampling', 'inspectie', 'verificare', 'monitorizare'],
  HARVEST: ['harvest', 'combine', 'recolt', 'treier'],
};

const busy = (snapshot: FarmSnapshot, day: string, pick: (task: CopilotTask) => string | null) => {
  const ids = new Set<string>();
  for (const task of snapshot.tasks) {
    if (task.status === 'DONE' || task.dueDate.slice(0, 10) !== day) continue;
    const id = pick(task);
    if (id) ids.add(id);
  }
  return ids;
};

/** A machine of the right type that is not running and has nothing else that day. */
export function suggestMachine(snapshot: FarmSnapshot, type: string, day: string): string | null {
  const wanted = COPILOT_TASK_TYPES[type]?.machines ?? [];
  if (!wanted.length) return null;
  const taken = busy(snapshot, day, (task) => task.machineId);
  const running = new Set(snapshot.runs.filter((run) => run.status === 'RUNNING' || run.status === 'PAUSED').map((run) => run.machineId));
  for (const machineType of wanted) {
    const free = snapshot.machines
      .filter((machine) => machine.type === machineType && !taken.has(machine.id) && !running.has(machine.id))
      .sort((a, b) => (b.powerHp ?? 0) - (a.powerHp ?? 0))[0];
    if (free) return free.id;
  }
  return null;
}

/** The operator with the fewest open tasks that week; field work goes to machine operators first. */
export function suggestOperator(snapshot: FarmSnapshot, type: string, day: string): string | null {
  const fieldWork = (COPILOT_TASK_TYPES[type]?.machines.length ?? 0) > 0;
  const pool = snapshot.members.filter((member) => (fieldWork ? member.role === 'OPERATOR' : member.role === 'AGRONOMIST' || member.role === 'OPERATOR'));
  const candidates = pool.length ? pool : snapshot.members.filter((member) => member.role === 'OPERATOR');
  if (!candidates.length) return null;
  const weekEnd = addDays(day, 6);
  const load = (id: string) =>
    snapshot.tasks.filter((task) => task.assigneeId === id && task.status !== 'DONE' && task.dueDate.slice(0, 10) >= day && task.dueDate.slice(0, 10) <= weekEnd).length;
  return [...candidates].sort((a, b) => load(a.id) - load(b.id) || a.name.localeCompare(b.name))[0]!.id;
}

let sequence = 0;

export interface ProposalInput {
  type: string;
  parcelId: string | null;
  dueDate: string;
  priority: CopilotPriority;
  reason: string;
  /** Days the work can move without harm, after the due date. */
  windowDays?: number;
  title?: string;
  /** Planned after this task: not given for follow-ups themselves. */
  tillage?: TillageSystem | null;
  withFollowUps?: boolean;
}

export function propose(snapshot: FarmSnapshot, input: ProposalInput): TaskProposal {
  const parcel = snapshot.parcels.find((item) => item.id === input.parcelId);
  const kind = COPILOT_TASK_TYPES[input.type];
  const label = kind?.label ?? input.type;
  const due = input.dueDate.slice(0, 10);
  const proposal: TaskProposal = {
    key: `p${++sequence}`,
    title: input.title ?? (parcel ? `${label} – ${parcel.name}` : label),
    type: input.type,
    category: kind?.category ?? 'MONITORING',
    parcelId: input.parcelId,
    dueDate: due,
    window: { start: due, end: addDays(due, input.windowDays ?? 3) },
    priority: input.priority,
    reason: input.reason,
    assigneeId: suggestOperator(snapshot, input.type, due),
    machineId: suggestMachine(snapshot, input.type, due),
    followUps: [],
  };
  if (input.withFollowUps !== false) proposal.followUps = followUpsFor(snapshot, proposal, input.tillage ?? parcel?.tillage ?? null);
  return proposal;
}

/** The work that naturally comes next, so planning continues after a task is created. */
export function followUpsFor(snapshot: FarmSnapshot, task: Pick<TaskProposal, 'type' | 'parcelId' | 'dueDate'>, tillage: TillageSystem | null): TaskProposal[] {
  const next = (type: string, days: number, priority: CopilotPriority, reason: string, windowDays = 5) =>
    propose(snapshot, { type, parcelId: task.parcelId, dueDate: addDays(task.dueDate, days), priority, reason, windowDays, withFollowUps: false });
  switch (task.type) {
    case 'HARVEST': {
      const steps = [
        next('GRAIN_TRANSPORT', 0, 'HIGH', 'Trailers must be ready when the combine starts, so grain does not wait in the field.', 1),
        next('SOIL_SAMPLING', 3, 'MEDIUM', 'Sampling right after harvest gives the nutrient balance for next season’s fertilization plan.', 10),
      ];
      const prep: Record<TillageSystem, [string, number, string] | null> = {
        CONVENTIONAL: ['PLOWING', 7, 'Autumn plowing after harvest buries the residue and lets frost break the clods.'],
        MINIMUM_TILL: ['CHISELING', 5, 'Minimum-till preparation: a shallow chisel pass mixes the residue without inverting the soil.'],
        STRIP_TILL: ['STRIP_TILL', 10, 'Autumn strip-till lets the strips settle and warm faster in spring.'],
        NO_TILL: null,
      };
      const step = prep[tillage ?? 'MINIMUM_TILL'];
      if (step) steps.push(next(step[0], step[1], 'MEDIUM', step[2], 10));
      else steps.push(next('FIELD_INSPECTION', 5, 'LOW', 'No Till: check that the residue is spread evenly before direct sowing.', 10));
      return steps;
    }
    case 'SOWING':
      return [
        next('EMERGENCE_CHECK', 12, 'MEDIUM', 'Counting plants after emergence shows whether the stand needs re-sowing.'),
        next('WEED_MONITORING', 21, 'MEDIUM', 'Early weed control protects yield the most in the first weeks.'),
      ];
    case 'HERBICIDE':
    case 'FUNGICIDE':
    case 'INSECTICIDE':
      return [next('FIELD_INSPECTION', 7, 'LOW', 'An efficacy check a week after the treatment shows whether a second pass is needed.')];
    case 'FERTILIZATION':
    case 'NITROGEN':
      return [next('CROP_SCOUTING', 14, 'LOW', 'Scouting two weeks later shows how the crop responded to the nitrogen.')];
    case 'IRRIGATION_START':
      return [next('IRRIGATION_CHECK', 2, 'MEDIUM', 'Check the dose and uniformity after the first passes.', 2)];
    case 'PLOWING':
    case 'DISKING':
    case 'SUBSOILING':
    case 'CHISELING':
      return [next('SOIL_PREP', 10, 'MEDIUM', 'Seedbed preparation before sowing levels the field and breaks the remaining clods.')];
    case 'SOIL_SAMPLING':
      return [next('FERTILIZATION', 14, 'MEDIUM', 'Base fertilization adjusted to the soil analysis results.', 14)];
    default:
      return [];
  }
}
