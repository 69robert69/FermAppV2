import type { CropCode } from '../crops';
import type { TillageSystem } from '../tillage';

/**
 * Everything the copilot knows about the farm, gathered from every module
 * before a question is answered. Dates are ISO strings; amounts are in euro.
 */
export interface FarmSnapshot {
  /** The moment of the question. */
  now: string;
  farmName: string;
  parcels: CopilotParcel[];
  tasks: CopilotTask[];
  runs: CopilotRun[];
  machines: CopilotMachine[];
  members: CopilotMember[];
  /** Forecast at the farm centre: parcels of one farm share the weather. */
  weather: CopilotWeather | null;
  /** Operators do not see costs and profit. */
  canSeeFinance: boolean;
}

export interface CopilotSeason {
  year: number;
  crop: CropCode;
  sowingDate: string | null;
  harvestDate: string | null;
  yieldT: number | null;
  expectedYieldTHa: number | null;
  expectedPricePerT: number | null;
  actualPricePerT: number | null;
}

export interface CopilotSoil {
  ph: number | null;
  organicMatterPct: number | null;
  clayPct: number | null;
  sandPct: number | null;
  waterHoldingMmPerM: number | null;
  /** Textural class, for example "Clay loam". */
  texture: string | null;
  label: string | null;
}

export interface CopilotEconomics {
  year: number;
  costPerHa: number;
  totalCost: number;
  /** Cost share by category, for example { Fuel: 22 }. */
  costShares: Record<string, number>;
  expectedProfitPerHa: number | null;
  actualProfitPerHa: number | null;
  revenuePerHa: number | null;
}

export interface CopilotParcel {
  id: string;
  name: string;
  code: string | null;
  areaHa: number;
  tillage: TillageSystem | null;
  irrigatedHa: number | null;
  notes: string | null;
  /** Newest first. */
  seasons: CopilotSeason[];
  soil: CopilotSoil | null;
  economics: CopilotEconomics | null;
  /** Newest first. */
  observations: { date: string; text: string }[];
  /** The latest analysed satellite image, when monitoring is on. */
  satellite?: CopilotSatellite | null;
}

export interface CopilotSatellite {
  date: string;
  ndvi: number;
  ndre: number | null;
  ndmi: number | null;
  /** 0-100, or null without a crop. */
  healthScore: number | null;
  healthLabel: string;
  /** NDVI change since about a week and a month earlier. */
  change7d: number | null;
  change30d: number | null;
  findings: { severity: 'INFO' | 'WARNING' | 'CRITICAL'; title: string; message: string; explanation: string; task: { type: string; title: string; reason: string; priority: 'LOW' | 'MEDIUM' | 'HIGH' } | null }[];
}

export type CopilotTaskStatus = 'TODO' | 'IN_PROGRESS' | 'DONE';
export type CopilotPriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT';

export interface CopilotTask {
  id: string;
  parcelId: string | null;
  title: string;
  type: string;
  priority: CopilotPriority;
  status: CopilotTaskStatus;
  dueDate: string;
  assigneeId: string | null;
  machineId: string | null;
  source: 'AI' | 'USER' | 'PLAN';
}

export interface CopilotRun {
  id: string;
  kind: 'WORK' | 'IRRIGATION';
  parcelId: string;
  /** Work type (PLOWING, SPRAYING…) or irrigation system. */
  type: string;
  status: 'RUNNING' | 'PAUSED' | 'COMPLETED' | 'CANCELLED';
  startDate: string;
  finishDate: string | null;
  machineId: string | null;
  operatorId: string | null;
  areaHa: number;
  doseMm?: number | null;
}

export interface CopilotMachine {
  id: string;
  name: string;
  /** TRACTOR, COMBINE, SPRAYER, SEEDER, DISC, CULTIVATOR, SUBSOILER, TRAILER. */
  type: string;
  powerHp: number | null;
}

export interface CopilotMember {
  id: string;
  name: string;
  role: 'ADMIN' | 'FARM_MANAGER' | 'OPERATOR' | 'AGRONOMIST';
}

export interface CopilotDay {
  date: string;
  tMinC: number;
  tMaxC: number;
  precipitationMm: number;
  rainProbabilityPct: number | null;
  windMaxKmh: number | null;
  et0Mm: number | null;
}

export interface CopilotHour {
  time: string;
  temperatureC: number;
  humidityPct: number;
  precipitationMm: number;
  precipitationProbabilityPct?: number | null;
  windKmh: number;
  weatherCode?: number | null;
}

export interface CopilotWeather {
  /** Today first. */
  days: CopilotDay[];
  /** Upcoming hours in local time, for spraying windows. */
  hours: CopilotHour[];
  /** Rain of the last 30 days, when known. */
  past30RainMm: number | null;
}

// ------------------------------------------------------------------ answers

export type CopilotTopic =
  | 'NEXT_CROP'
  | 'CROP_SUITABILITY'
  | 'FERTILIZE'
  | 'HARVEST'
  | 'IRRIGATE'
  | 'SPRAY'
  | 'YIELD'
  | 'PENDING'
  | 'PROFITABILITY'
  | 'MACHINERY'
  | 'REDUCE_COSTS'
  | 'TILLAGE'
  | 'PRIORITIZE'
  | 'ROI_RANKING'
  | 'WEEK_SCHEDULE'
  | 'HECTARES_LEFT'
  | 'IDLE_MACHINES'
  | 'FARM_PROFIT'
  | 'OVERDUE'
  | 'RISKS'
  | 'SATELLITE'
  | 'RESCHEDULE'
  | 'CANCEL'
  | 'REPRIORITIZE'
  | 'GROUP'
  | 'CREATE_TASK'
  | 'WHY'
  | 'GENERAL';

export type TaskCategory = 'TILLAGE' | 'PLANTING' | 'FERTILIZATION' | 'CROP_PROTECTION' | 'IRRIGATION' | 'MONITORING' | 'HARVEST';

/** A task the copilot recommends; it becomes a real To-Do item once approved. */
export interface TaskProposal {
  /** Stable within one answer, used by the screen to track approval. */
  key: string;
  title: string;
  /** Task type code, see COPILOT_TASK_TYPES. */
  type: string;
  category: TaskCategory;
  parcelId: string | null;
  /** YYYY-MM-DD. */
  dueDate: string;
  /** Suggested time window, YYYY-MM-DD. */
  window: { start: string; end: string };
  priority: CopilotPriority;
  reason: string;
  assigneeId: string | null;
  machineId: string | null;
  /** Planned after this one is created, for example soil sampling after harvest. */
  followUps: TaskProposal[];
}

/** A change to an existing task: move, cancel or reprioritize. */
export interface TaskChange {
  key: string;
  kind: 'RESCHEDULE' | 'CANCEL' | 'PRIORITY';
  taskId: string;
  title: string;
  parcelId: string | null;
  fromDate: string;
  toDate?: string;
  fromPriority: CopilotPriority;
  toPriority?: CopilotPriority;
  reason: string;
}

export interface CopilotAnswer {
  topic: CopilotTopic;
  /** The parcel the answer is about, remembered for follow-up questions. */
  parcelId: string | null;
  crop: CropCode | null;
  direct: string;
  reasoning: string[];
  risks: string[];
  alternatives: string[];
  economics: string[];
  nextSteps: string[];
  proposals: TaskProposal[];
  changes: TaskChange[];
  /** Information that would make the answer more precise. */
  dataGaps: string[];
  confidence: 'HIGH' | 'MEDIUM' | 'LOW';
  /**
   * The user explicitly asked for the tasks ("add them to To-Do", "yes"), so the
   * request is itself the approval: the app creates them without a second tap.
   */
  execute?: boolean;
}

/** What the copilot remembers between questions of one conversation. */
export interface CopilotMemory {
  parcelId: string | null;
  topic: CopilotTopic | null;
  crop: CropCode | null;
  /** The previous answer, so "why?" can expand it. */
  last: CopilotAnswer | null;
}

export const EMPTY_MEMORY: CopilotMemory = { parcelId: null, topic: null, crop: null, last: null };

export type InsightSeverity = 'INFO' | 'WARNING' | 'CRITICAL';

/** Something the copilot noticed without being asked. */
export interface CopilotInsight {
  key: string;
  severity: InsightSeverity;
  kind: 'OVERDUE' | 'UPCOMING' | 'WEATHER' | 'MACHINERY' | 'FERTILIZATION' | 'HARVEST' | 'IRRIGATION' | 'PROFIT' | 'DATA' | 'SATELLITE';
  parcelId: string | null;
  message: string;
  /** A question that opens the matching analysis in the chat. */
  ask: string | null;
  proposal: TaskProposal | null;
}
