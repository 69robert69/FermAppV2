import type { CostCategory, CropCode, ParcelPlanData, ParcelReport } from '@fermapp/domain';

export const COLLECTIONS = [
  'farms',
  'invites',
  'users',
  'parcels',
  'cropHistory',
  'tasks',
  'machines',
  'machineWork',
  'irrigations',
  'weatherData',
  'soilAnalysis',
  'recommendations',
  'satelliteImages',
  'expenses',
  'cropPlans',
  'reports',
  'economics',
  'notifications',
  'photos',
  'observations',
  'seedCatalog',
  'machineCatalog',
  'implementCatalog',
] as const;

export type CollectionName = (typeof COLLECTIONS)[number];
export type FarmRole = 'ADMIN' | 'FARM_MANAGER' | 'OPERATOR' | 'AGRONOMIST';
export type RunState = 'RUNNING' | 'PAUSED' | 'COMPLETED' | 'CANCELLED';

export interface FarmDoc {
  id: string;
  farmId: string;
  createdAt: string;
  updatedAt?: string;
}

export interface UserProfile {
  uid: string;
  name: string;
  email: string;
  role: FarmRole;
  farmId: string;
  farmName: string;
  createdAt: string;
}

export interface ParcelDoc extends FarmDoc {
  name: string;
  code: string;
  area: number;
  perimeter: number;
  county: string | null;
  locality: string | null;
  centerLat: number;
  centerLng: number;
  /** The outer outline. Firestore does not accept nested lists, so the points are objects. */
  coordinates: { lat: number; lng: number }[];
  /** The full GeoJSON polygon, saved as text. */
  geometry: string;
  currentCrop: CropCode | null;
  plannedCrop: CropCode | null;
  rotationScore: number | null;
  irrigationPercent: number | null;
  ndviScore: number | null;
  notes?: string | null;
  irrigatedAreaHa?: number | null;
  /** Soil tillage technology in use, see TILLAGE_SYSTEMS. */
  tillageSystem?: string | null;
  /** One entry per year, oldest first. */
  tillageHistory?: { year: number; system: string }[] | null;
  archivedAt: string | null;
  ownerId: string;
}

export interface CropHistoryDoc extends FarmDoc {
  parcelId: string;
  year: number;
  crop: CropCode;
  sowingDate: string | null;
  harvestDate: string | null;
  yieldT?: number | null;
  harvestMoisture?: number | null;
  notes?: string | null;
  /** The hybrid or variety chosen from the catalog. */
  seedId?: string | null;
  seedName?: string | null;
  expectedYieldTHa?: number | null;
  expectedPricePerT?: number | null;
  actualPricePerT?: number | null;
}

export interface ExpenseDoc extends FarmDoc {
  parcelId: string;
  year: number;
  category: CostCategory;
  amount: number;
  currency: string;
  date: string;
  notes: string | null;
}

/** Economic settings of the farm, one sheet per parcel and season, and the currency marker. Amounts in euro. */
export interface EconomicDoc extends FarmDoc {
  kind: 'settings' | 'sheet' | 'currency';
  parcelId: string | null;
  year: number | null;
  data?: unknown;
}

export interface SoilAnalysisDoc extends FarmDoc {
  parcelId: string;
  status: 'READY' | 'FAILED';
  ph?: number;
  clayPct?: number;
  sandPct?: number;
  siltPct?: number;
  organicCarbonGKg?: number;
  organicMatterPct?: number;
  waterHoldingMmPerM?: number;
  texture?: string;
  soilType?: string;
  label?: string;
  errorMessage?: string | null;
}

export interface TaskDoc extends FarmDoc {
  parcelId: string | null;
  title: string;
  description: string | null;
  type: string;
  priority: 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT';
  status: 'TODO' | 'IN_PROGRESS' | 'DONE';
  responsibleUser: string | null;
  dueDate: string;
  completedAt: string | null;
  planId: string | null;
  planStepKey: string | null;
  /** Who created the task. Missing on tasks created before the copilot. */
  source?: 'USER' | 'AI' | 'PLAN';
  category?: string | null;
  machineId?: string | null;
  /** Suggested time window, YYYY-MM-DD. */
  windowStart?: string | null;
  windowEnd?: string | null;
  /** Why the copilot recommended it. */
  aiReason?: string | null;
}

export interface MachineDoc extends FarmDoc {
  name: string;
  type: string;
  powerHp: number | null;
  catalogId?: string | null;
  fuelConsumptionMin?: number | null;
  fuelConsumptionMax?: number | null;
  tankCapacity?: number | null;
  weight?: number | null;
  /** Tillage technologies the machine suits; empty uses the defaults of its type. */
  tillageSystems?: string[] | null;
  brand: string | null;
  model: string | null;
  operator: string | null;
  width: number | null;
  speed: number | null;
  fuelConsumption: number | null;
  currentStatus: 'IDLE' | 'WORKING' | 'PAUSED';
  lastKnownLocation: { lat: number; lng: number; headingDeg: number | null; recordedAt: string } | null;
}

interface TimedRunDoc extends FarmDoc {
  parcelId: string;
  status: RunState;
  startDate: string;
  finishDate: string | null;
  durationHours: number;
  pausedAt: string | null;
  pausedMs: number;
}

export interface MachineWorkDoc extends TimedRunDoc {
  machineId: string | null;
  operatorId: string;
  type: string;
  areaHa: number;
  workingWidthM: number;
  speedKmh: number;
  efficiencyPct: number;
  /** Diesel consumption and price when the operation started. */
  fuelLPerH?: number | null;
  fuelPricePerL?: number | null;
  lastLocation?: { lat: number; lng: number; headingDeg: number | null; speedKmh: number | null; recordedAt: string } | null;
}

export interface IrrigationDoc extends TimedRunDoc {
  type: 'REEL' | 'PIVOT' | 'LINEAR';
  speed: number;
  length: number;
  doseMm: number | null;
  percentCompleted: number;
  eta: string | null;
}

/** The plan without financial data, visible to the whole team. */
export type CropPlanDoc = FarmDoc &
  Omit<ParcelPlanData, 'report'> & {
    parcelId: string;
    active: boolean;
    trigger: 'WIZARD' | 'CROP_CHANGED';
    report: Omit<ParcelReport, 'expectedProfitEurHa' | 'priceEurPerT' | 'costEurHa'>;
  };

/** The plan's financial estimates, hidden from operators by the security rules. */
export interface ReportDoc extends FarmDoc {
  parcelId: string;
  planId: string;
  year: number;
  expectedProfitEurHa: number;
  priceEurPerT: number;
  costEurHa: number;
}

export interface NotificationDoc extends FarmDoc {
  userId: string;
  title: string;
  body: string;
  kind: 'WORK_DONE' | 'IRRIGATION_DONE' | 'TASK_DUE' | 'WEATHER' | 'RECOMMENDATION';
  readAt: string | null;
  parcelId?: string;
  alertType?: string;
  severity?: 'INFO' | 'WARNING' | 'SEVERE';
}

export interface PhotoDoc extends FarmDoc {
  parcelId: string;
  authorId: string;
  /** The shrunk image, encoded to fit in a Firestore document. */
  imageBase64: string;
  notes: string | null;
}

export interface ObservationDoc extends FarmDoc {
  parcelId: string;
  authorId: string;
  text: string;
}
