import {
  checkTaskCompatibility,
  checkWorkCompatibility,
  planTillageOperations,
  TILLAGE_SYSTEMS,
  type TillageSystem,
} from '@fermapp/domain';
import { useQuery } from '@tanstack/react-query';
import { apiGet, apiSend } from '../../api/client';
import type { Parcel, Task, WorkSession } from '../../api/types';
import { useAction, useParcels } from '../../lib/hooks';
import { intelligenceKey } from '../intelligence/api';
import { mapOverviewKey } from '../map/api';

export const useParcelById = (parcelId: string | null | undefined): Parcel | undefined => {
  const parcels = useParcels();
  return parcelId ? parcels.data?.find((item) => item.id === parcelId) : undefined;
};

const refreshKeys = (parcelId: string) => [['parcels'], ['parcel', parcelId], mapOverviewKey, intelligenceKey(parcelId), ['dashboard']];

export function useSetTillage(parcelId: string, onDone?: () => void) {
  return useAction<{ system: TillageSystem; year: number }>(
    async (body) => {
      await apiSend('POST', `/parcels/${parcelId}/tillage`, body);
    },
    refreshKeys(parcelId),
    onDone,
  );
}

/**
 * Creates To Do tasks for the operations the technology recommends, skipping
 * the ones already open on the parcel with the same title.
 */
export function useGenerateTillageTasks(parcelId: string, onDone?: (created: number) => void) {
  return useAction<{ system: TillageSystem; start: Date; existing: Task[] }, number>(
    async ({ system, start, existing }) => {
      const open = new Set(existing.filter((task) => task.parcel?.id === parcelId && task.status !== 'DONE').map((task) => task.title.toLowerCase()));
      const missing = planTillageOperations(system, start).filter((operation) => !open.has(operation.label.toLowerCase()));
      for (const operation of missing) {
        await apiSend('POST', '/tasks', {
          type: operation.taskType,
          title: operation.label,
          priority: 'MEDIUM',
          dueDate: operation.dueDate,
          parcelId,
        });
      }
      return missing.length;
    },
    [['tasks'], ...refreshKeys(parcelId)],
    onDone,
  );
}

export interface TillageConflict {
  id: string;
  kind: 'task' | 'work';
  title: string;
  message: string;
}

/** Open tasks and running operations of the parcel that contradict its technology. */
export function useTillageConflicts(parcelId: string, system: TillageSystem | null | undefined): TillageConflict[] {
  const tasks = useQuery({ queryKey: ['tasks'], staleTime: 30_000, queryFn: () => apiGet<Task[]>('/tasks') });
  const work = useQuery({ queryKey: ['work'], staleTime: 30_000, queryFn: () => apiGet<WorkSession[]>('/work') });
  if (!system) return [];
  const discouragedLabels = Object.keys(TILLAGE_SYSTEMS[system].discouragedWork);
  const conflicts: TillageConflict[] = [];
  for (const task of tasks.data ?? []) {
    if (task.parcel?.id !== parcelId || task.status === 'DONE') continue;
    const check = checkTaskCompatibility(system, task.type);
    // A task titled after a discouraged operation, for example "Ploughing", is flagged too.
    const named = discouragedLabels.find((type) => task.title.toLowerCase().includes(type === 'PLOWING' ? 'plough' : type.toLowerCase().slice(0, 5)));
    const byName = named ? checkWorkCompatibility(system, named) : null;
    const message = !check.compatible ? check.message : byName && !byName.compatible ? byName.message : null;
    if (message) conflicts.push({ id: task.id, kind: 'task', title: task.title, message });
  }
  for (const run of work.data ?? []) {
    if (run.parcel.id !== parcelId || (run.status !== 'RUNNING' && run.status !== 'PAUSED')) continue;
    const check = checkWorkCompatibility(system, run.type);
    if (!check.compatible && check.message) conflicts.push({ id: run.id, kind: 'work', title: run.machine?.name ?? 'Running operation', message: check.message });
  }
  return conflicts;
}
