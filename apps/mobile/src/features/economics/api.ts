import type { EconomicSettings, EconomicSheet, FarmEconomics, FarmParcelEconomics } from '@fermapp/domain';
import { useQuery } from '@tanstack/react-query';
import { apiGet, apiSend } from '../../api/client';
import { useAction } from '../../lib/hooks';

export interface FarmEconomicsResponse {
  year: number;
  settings: EconomicSettings;
  parcels: FarmParcelEconomics[];
  summary: FarmEconomics;
}

export const economicsKey = ['economics'] as const;

/** Real cost and profit of every parcel in a season, computed by the server (or the phone on Firebase). */
export const useFarmEconomics = (year: number) =>
  useQuery({
    queryKey: [...economicsKey, 'farm', year],
    staleTime: 30_000,
    queryFn: () => apiGet<FarmEconomicsResponse>('/economics/farm', { year }),
  });

export const useEconomicSettings = () =>
  useQuery({ queryKey: [...economicsKey, 'settings'], queryFn: () => apiGet<EconomicSettings>('/economics/settings') });

export const useSheet = (parcelId: string, year: number) =>
  useQuery({
    queryKey: [...economicsKey, 'sheet', parcelId, year],
    queryFn: () => apiGet<{ parcelId: string; year: number; sheet: EconomicSheet }>(`/economics/sheets/${parcelId}/${year}`),
  });

export function useSaveSheet(parcelId: string, year: number, onDone?: () => void) {
  return useAction<EconomicSheet>(
    async (sheet) => {
      await apiSend('POST', `/economics/sheets/${parcelId}/${year}`, sheet);
    },
    [economicsKey, ['report']],
    onDone,
  );
}

export function useSaveSettings(onDone?: () => void) {
  return useAction<EconomicSettings>(
    async (settings) => {
      await apiSend('POST', '/economics/settings', settings);
    },
    [economicsKey, ['report']],
    onDone,
  );
}

export const newLineId = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 7)}`;
