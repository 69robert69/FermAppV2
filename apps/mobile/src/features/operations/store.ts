import { create } from 'zustand';
import { useToast } from '../../store/toast';

interface OperationsState {
  /** "Live Operations": every active machine animated, cards always visible, fleet panel open. */
  live: boolean;
  setLive: (live: boolean) => void;
}

export const useOperations = create<OperationsState>((set) => ({
  live: false,
  setLive: (live) => set({ live }),
}));

const announced = new Set<string>();

/**
 * Shows one short message per finished run. The phone notices the end first
 * (from the timer) and the server confirms it later; only the first one speaks.
 */
export function announceCompletion(runId: string, message: string) {
  if (announced.has(runId)) return;
  announced.add(runId);
  useToast.getState().show(message);
}
