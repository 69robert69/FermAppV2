import type { CropCode, MachineRecommendation, SeedEntry, WorkOperation, NextAction, ParcelPlanData, RotationResult, SoilIndices, SprayAlert, VegetationHealth } from '@fermapp/domain';
import { useQuery } from '@tanstack/react-query';
import { apiGet } from '../../api/client';
import type { IrrigationRun, Task, WorkSession } from '../../api/types';

export interface WeatherDay {
  date: string;
  tMaxC: number;
  tMinC: number;
  precipitationMm: number;
  humidityPct: number | null;
  windMaxKmh: number | null;
  et0Mm: number | null;
  rainProbabilityPct: number | null;
}

export interface ParcelWeather {
  current: {
    temperatureC: number;
    humidityPct: number;
    precipitationMm: number;
    windKmh: number;
    et0Mm: number | null;
  } | null;
  today: WeatherDay | null;
  next7: WeatherDay[];
  next14: WeatherDay[];
  alerts: SprayAlert[];
}

export interface SatellitePoint {
  date: string;
  ndvi: number;
  ndre: number | null;
  healthIndex: number;
  health: VegetationHealth;
}

export interface ParcelPhoto {
  id: string;
  /** The image saved in Firebase. On the current server, the photo is downloaded by its identifier. */
  imageUri?: string;
  notes: string | null;
  createdAt: string;
}

export interface ParcelObservation {
  id: string;
  text: string;
  createdAt: string;
  author: { id: string; fullName: string };
}

export interface ParcelEconomicsView {
  year: number;
  seasonId: string | null;
  expectedYieldTHa: number | null;
  expectedPricePerT: number | null;
  seedsCost: number;
  fertilizerCost: number;
  chemicalCost: number;
  fuelCost: number;
  laborCost: number;
  irrigationCost: number;
  otherCost: number;
  totalCost: number;
  costPerHa: number;
  expectedIncome: number | null;
  expectedProfit: number | null;
  profitMarginPct: number | null;
  roiPct: number | null;
  actualIncome: number | null;
  actualProfit: number | null;
}

export interface ActiveWork extends WorkSession {
  workingWidthM: number;
  speedKmh: number;
  lastLocation: MachinePosition | null;
}

export interface ActiveIrrigation extends IrrigationRun {
  label: string;
}

export interface MachinePosition {
  lat: number;
  lng: number;
  speedKmh: number | null;
  recordedAt: string;
}

export interface ParcelIntelligence {
  generatedAt: string;
  header: {
    id: string;
    name: string;
    code: string;
    areaHa: number;
    county: string | null;
    locality: string | null;
    lat: number;
    lng: number;
  };
  crop: {
    seasonId: string;
    code: CropCode;
    name: string;
    year: number;
    sowingDate: string | null;
    status: 'NOT_SOWN' | 'GROWING' | 'READY' | 'HARVESTED';
    vegetationDay: number;
    totalDays: number;
    percent: number;
    estimatedHarvestDate: string | null;
  } | null;
  rotation: RotationResult & {
    mode: 'CURRENT' | 'SUGGESTED';
    history: { year: number; crop: CropCode; name: string }[];
  };
  irrigation: {
    configured: boolean;
    irrigatedHa: number | null;
    notIrrigatedHa: number | null;
    coveragePct: number | null;
  };
  activeIrrigation: ActiveIrrigation[];
  activeWork: ActiveWork[];
  nextAction: NextAction;
  /** The chosen hybrid and the catalog recommendations, available on Firebase. */
  catalog?: {
    cropName: string | null;
    seasonId: string | null;
    seed: (SeedEntry & { compatibility: number; reason: string }) | null;
    recommended: { id: string; name: string; score: number; reason: string }[];
    operation: WorkOperation | null;
    machine: MachineRecommendation | null;
  };
  plan: { id: string; year: number; createdAt: string; data: ParcelPlanData } | null;
  planHistory: { id: string; year: number; cropName: string; trigger: string; active: boolean; createdAt: string }[];
  tasks: {
    summary: { todo: number; inProgress: number; done: number };
    items: Task[];
  };
  weather: ParcelWeather | null;
  soil: {
    status: string;
    label?: string | null;
    ph?: number | null;
    clayPct?: number | null;
    sandPct?: number | null;
    siltPct?: number | null;
    organicMatterPct?: number | null;
    organicCarbonGKg?: number | null;
    errorMessage?: string | null;
    indices?: SoilIndices | null;
  };
  economics: ParcelEconomicsView | null;
  satellite: {
    latest: SatellitePoint | null;
    sevenDaysAgo: SatellitePoint | null;
    thirtyDaysAgo: SatellitePoint | null;
  };
  photos: ParcelPhoto[];
  observations: ParcelObservation[];
}

export interface CropRecommendationView {
  crop: CropCode;
  name: string;
  score: number;
  reasons: string[];
  missingData: string[];
}

export const intelligenceKey = (parcelId: string) => ['intelligence', parcelId] as const;
export const machineLocationKey = (workId: string) => ['machine-location', workId] as const;

export const useParcelIntelligence = (parcelId: string | null) =>
  useQuery({
    queryKey: intelligenceKey(parcelId ?? 'none'),
    enabled: Boolean(parcelId),
    staleTime: 15_000,
    queryFn: () => apiGet<ParcelIntelligence>(`/parcels/${parcelId}/intelligence`),
  });

export const useCropRecommendations = (parcelId: string) =>
  useQuery({
    queryKey: ['recommendations', parcelId, 'panel'],
    staleTime: 3_600_000,
    queryFn: () => apiGet<{ year: number; recommendations: CropRecommendationView[] }>(`/parcels/${parcelId}/recommendations`),
  });

export const useMachineLocation = (workId: string | null) =>
  useQuery({
    queryKey: machineLocationKey(workId ?? 'none'),
    enabled: Boolean(workId),
    queryFn: () => apiGet<MachinePosition | null>(`/work/${workId}/location`),
  });
