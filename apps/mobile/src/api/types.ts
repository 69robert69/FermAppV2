import type { CropCode, PolygonGeometry } from '@fermapp/domain';

export type Role = 'ADMIN' | 'FARM_MANAGER' | 'OPERATOR' | 'AGRONOMIST';
export type RunStatus = 'RUNNING' | 'PAUSED' | 'COMPLETED' | 'CANCELLED';
export type TaskStatus = 'TODO' | 'IN_PROGRESS' | 'DONE';
export type TaskPriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT';

export interface Parcel {
  id: string;
  name: string;
  code: string;
  geometry: PolygonGeometry;
  areaHa: number;
  perimeterM: number;
  centerLat: number;
  centerLng: number;
  locality: string | null;
  county: string | null;
  notes: string | null;
  irrigatedAreaHa: number | null;
  archivedAt: string | null;
  seasons?: { crop: CropCode; year: number }[];
  soilProfile?: { status: string; label: string | null } | null;
  satellite?: { date: string; ndvi: number; healthIndex: number; healthClass: string }[];
}

export interface CropSeason {
  id: string;
  year: number;
  crop: CropCode;
  sowingDate: string | null;
  harvestDate: string | null;
  yieldT: number | null;
  harvestMoisture: number | null;
  notes: string | null;
  seedName?: string | null;
}

export interface LiveProgress {
  percent: number;
  elapsedSeconds: number;
  remainingSeconds: number;
  eta: string | null;
  finished: boolean;
}

export interface TimedRun {
  id: string;
  status: RunStatus;
  startedAt: string;
  pausedAt: string | null;
  pausedMs: number;
  durationHours: number;
  progressPct: number;
  progress: LiveProgress;
  parcel: { id: string; name: string; code: string };
}

export interface WorkSession extends TimedRun {
  type: string;
  areaHa: number;
  capacityHaH: number;
  areaDoneHa: number;
  fuelLiters: number | null;
  machine: { id: string; name: string } | null;
  operator: { id: string; fullName: string };
}

export interface IrrigationRun extends TimedRun {
  systemType: 'REEL' | 'PIVOT' | 'LINEAR';
  routeLengthM: number;
  speedMPerH: number;
  doseMm: number | null;
}

export interface Task {
  id: string;
  type: string;
  title: string;
  description: string | null;
  priority: TaskPriority;
  status: TaskStatus;
  dueDate: string;
  parcel: { id: string; name: string } | null;
  assignee: { id: string; fullName: string } | null;
  planStepKey?: string | null;
}

export interface Machine {
  id: string;
  name: string;
  type: string;
  brand: string | null;
  model: string | null;
  powerHp: number | null;
  avgConsumptionLh: number | null;
  fuelConsumptionMin?: number | null;
  fuelConsumptionMax?: number | null;
  tankCapacity?: number | null;
  weight?: number | null;
  operator: { id: string; fullName: string } | null;
}

export interface WeatherAlert {
  id: string;
  type: string;
  severity: 'INFO' | 'WARNING' | 'SEVERE';
  title: string;
  message: string;
  createdAt: string;
  parcel: { id: string; name: string };
}

export interface Dashboard {
  year: number;
  parcels: { count: number; totalAreaHa: number; cultivatedAreaHa: number; freeAreaHa: number };
  crops: { crop: CropCode; name: string; areaHa: number }[];
  activeWork: WorkSession[];
  activeIrrigation: IrrigationRun[];
  tasks: { open: number; overdue: number };
  alerts: WeatherAlert[];
}

export interface TeamMember {
  userId: string;
  fullName: string;
  email: string;
  role: Role;
}

export interface ReportData {
  title: string;
  period: string;
  columns: { key: string; label: string; numeric?: boolean }[];
  rows: Record<string, string | number | null>[];
  totals?: Record<string, string | number>;
}
