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
  /** Conturul exterior. Firestore nu acceptă liste în liste, deci punctele sunt obiecte. */
  coordinates: { lat: number; lng: number }[];
  /** Poligonul GeoJSON complet, salvat ca text. */
  geometry: string;
  currentCrop: CropCode | null;
  plannedCrop: CropCode | null;
  rotationScore: number | null;
  irrigationPercent: number | null;
  ndviScore: number | null;
  notes?: string | null;
  irrigatedAreaHa?: number | null;
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
  /** Hibridul sau soiul ales din catalog. */
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
  /** Consumul și prețul motorinei de la pornirea lucrării. */
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

/** Planul fără date financiare, vizibil pentru toată echipa. */
export type CropPlanDoc = FarmDoc &
  Omit<ParcelPlanData, 'report'> & {
    parcelId: string;
    active: boolean;
    trigger: 'WIZARD' | 'CROP_CHANGED';
    report: Omit<ParcelReport, 'expectedProfitEurHa' | 'priceEurPerT' | 'costEurHa'>;
  };

/** Estimările financiare ale planului, ascunse operatorilor prin regulile de securitate. */
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
  /** Imaginea micșorată, codată pentru a încăpea într-un document Firestore. */
  imageBase64: string;
  notes: string | null;
}

export interface ObservationDoc extends FarmDoc {
  parcelId: string;
  authorId: string;
  text: string;
}
