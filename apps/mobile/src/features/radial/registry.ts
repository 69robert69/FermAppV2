import { useMemo, useSyncExternalStore } from 'react';
import type { ParcelSnapshot, RadialAction } from './types';

/** The wheel stays readable and every slice stays large enough for a thumb up to this count. */
export const MAX_RADIAL_ACTIONS = 12;

const actions = new Map<string, RadialAction>();
const listeners = new Set<() => void>();
let version = 0;

function notify() {
  version += 1;
  listeners.forEach((listener) => listener());
}

/** Adds an action to the parcel wheel, or replaces the one with the same id. Returns a remover. */
export function registerRadialAction(action: RadialAction): () => void {
  actions.set(action.id, action);
  notify();
  return () => {
    if (actions.get(action.id) === action) {
      actions.delete(action.id);
      notify();
    }
  };
}

export function registerRadialActions(list: RadialAction[]): () => void {
  const removers = list.map(registerRadialAction);
  return () => removers.forEach((remove) => remove());
}

export const getRadialAction = (id: string) => actions.get(id);

const subscribe = (listener: () => void) => {
  listeners.add(listener);
  return () => listeners.delete(listener);
};

/** The actions available for this parcel and role, in wheel order. */
export function useRadialActions(snapshot: ParcelSnapshot | null): RadialAction[] {
  const current = useSyncExternalStore(subscribe, () => version);
  return useMemo(() => {
    if (!snapshot) return [];
    return [...actions.values()]
      .filter((action) => action.isAvailable?.(snapshot) ?? true)
      .sort((a, b) => a.order - b.order)
      .slice(0, MAX_RADIAL_ACTIONS);
    // `current` changes whenever an action is registered or removed.
  }, [snapshot, current]);
}

export const resolveIcon = (action: RadialAction, snapshot: ParcelSnapshot) =>
  typeof action.icon === 'function' ? action.icon(snapshot) : action.icon;
