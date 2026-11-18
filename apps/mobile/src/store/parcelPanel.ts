import { create } from 'zustand';

export interface TrackedWork {
  workId: string;
  parcelId: string;
  label: string;
}

interface ParcelPanelState {
  selectedParcelId: string | null;
  /** Crește la fiecare atingere, ca aceeași parcelă să poată fi redeschisă imediat. */
  selectionToken: number;
  tracked: TrackedWork | null;
  select: (parcelId: string) => void;
  clearSelection: () => void;
  track: (work: TrackedWork) => void;
  stopTracking: () => void;
}

export const useParcelPanel = create<ParcelPanelState>((set) => ({
  selectedParcelId: null,
  selectionToken: 0,
  tracked: null,
  select: (selectedParcelId) => set((state) => ({ selectedParcelId, selectionToken: state.selectionToken + 1 })),
  clearSelection: () => set({ selectedParcelId: null }),
  track: (tracked) => set({ tracked, selectedParcelId: null }),
  stopTracking: () => set({ tracked: null }),
}));
