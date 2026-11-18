import type { CropCode } from '@fermapp/domain';
import { useQuery } from '@tanstack/react-query';
import { apiGet } from '../../api/client';
import type { ActiveIrrigation, ActiveWork } from '../intelligence/api';

export interface MapParcelInfo {
  parcelId: string;
  crop: { code: CropCode; name: string; planned: boolean; year: number } | null;
  growthPct: number | null;
  openTasks: number;
  ndvi: number | null;
  work: ActiveWork[];
  irrigation: ActiveIrrigation[];
}

export const mapOverviewKey = ['map-overview'] as const;

export const useMapOverview = () =>
  useQuery({
    queryKey: mapOverviewKey,
    staleTime: 15_000,
    refetchInterval: 60_000,
    queryFn: () => apiGet<MapParcelInfo[]>('/map/overview'),
  });
