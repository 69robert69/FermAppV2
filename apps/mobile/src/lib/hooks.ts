import { timedProgress } from '@fermapp/domain';
import { useMutation, useQuery, useQueryClient, type QueryKey } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { Alert } from 'react-native';
import { apiGet, ApiError } from '../api/client';
import type { Parcel, TimedRun } from '../api/types';

export function useNow(intervalMs = 1000, active = true) {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    if (!active) return;
    const timer = setInterval(() => setNow(new Date()), intervalMs);
    return () => clearInterval(timer);
  }, [intervalMs, active]);
  return now;
}

/** Progresul unei operațiuni la momentul dat, calculat pe telefon. */
export function runProgressAt(run: TimedRun, now: Date) {
  if (run.status !== 'RUNNING' && run.status !== 'PAUSED') return run.progress;
  const running = run.status === 'RUNNING';
  const progress = timedProgress({
    startedAt: new Date(run.startedAt),
    durationMs: run.durationHours * 3_600_000,
    pausedMs: run.pausedMs,
    pausedAt: run.pausedAt ? new Date(run.pausedAt) : null,
    now,
  });
  return {
    percent: progress.percent,
    elapsedSeconds: Math.round(progress.elapsedMs / 1000),
    remainingSeconds: Math.round(progress.remainingMs / 1000),
    eta: running ? progress.eta.toISOString() : null,
    finished: progress.finished,
  };
}

/** Cronometru local, ca progresul să avanseze și între actualizările serverului. */
export function useLiveProgress(run: TimedRun) {
  const now = useNow(1000, run.status === 'RUNNING');
  return runProgressAt(run, now);
}

export const useParcels = () =>
  useQuery({ queryKey: ['parcels'], queryFn: () => apiGet<Parcel[]>('/parcels') });

export const errorMessage = (error: unknown) => {
  if (error instanceof ApiError) {
    const detail = error.details?.[0]?.message;
    return detail ?? error.message;
  }
  return 'A apărut o problemă neașteptată. Încearcă din nou.';
};

/** Mutație care reîmprospătează listele afectate și afișează eroarea exactă. */
export function useAction<TVariables = void, TResult = unknown>(
  action: (variables: TVariables) => Promise<TResult>,
  invalidate: QueryKey[],
  onSuccess?: (result: TResult) => void,
) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: action,
    onSuccess: async (result) => {
      await Promise.all(invalidate.map((queryKey) => client.invalidateQueries({ queryKey })));
      onSuccess?.(result);
    },
    onError: (error) => Alert.alert('Acțiunea nu a reușit', errorMessage(error)),
  });
}
