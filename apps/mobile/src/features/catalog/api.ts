import type { CropCode, ImplementEntry, MachineRecommendation, SeedCompatibility, SeedEntry, TractorEntry, WorkOperation } from '@fermapp/domain';
import { useQuery } from '@tanstack/react-query';
import { apiGet } from '../../api/client';
import { usesFirebase } from '../../firebase/config';

export type CatalogSeed = SeedEntry & { catalogKey?: string };
export type CatalogTractor = TractorEntry & { catalogKey?: string };
export type CatalogKind = 'seed' | 'tractor';

export const useCatalogSeeds = () =>
  useQuery({ queryKey: ['catalog', 'seeds'], enabled: usesFirebase, queryFn: () => apiGet<CatalogSeed[]>('/catalog/seeds') });

export const useCatalogTractors = () =>
  useQuery({ queryKey: ['catalog', 'tractors'], enabled: usesFirebase, queryFn: () => apiGet<CatalogTractor[]>('/catalog/tractors') });

export const useCatalogImplements = () =>
  useQuery({ queryKey: ['catalog', 'implements'], enabled: usesFirebase, queryFn: () => apiGet<ImplementEntry[]>('/catalog/implements') });

export const useSeedRanking = (parcelId: string | null, crop: CropCode | null, year?: number) =>
  useQuery({
    queryKey: ['catalog', 'ranking', parcelId, crop, year],
    enabled: usesFirebase && Boolean(parcelId && crop),
    queryFn: () => apiGet<SeedCompatibility[]>(`/parcels/${parcelId}/seed-ranking`, { crop: crop!, year }),
  });

export const useMachineRecommendation = (parcelId: string | null, operation: WorkOperation | null) =>
  useQuery({
    queryKey: ['catalog', 'machine', parcelId, operation],
    enabled: usesFirebase && Boolean(parcelId && operation),
    queryFn: () => apiGet<MachineRecommendation | null>(`/parcels/${parcelId}/machine-recommendation`, { operation: operation! }),
  });
