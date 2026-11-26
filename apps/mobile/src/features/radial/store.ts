import AsyncStorage from '@react-native-async-storage/async-storage';
import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import type { SheetParams } from './types';

export interface RadialMenuState {
  parcelId: string;
  /** Where the finger pressed, relative to the map. Drag directions are measured from here. */
  originX: number;
  originY: number;
  /** Increments on every opening, so the entry animation replays. */
  token: number;
}

interface RadialState {
  menu: RadialMenuState | null;
  phase: 'open' | 'closing';
  sheet: { actionId: string; parcelId: string; params: SheetParams; token: number } | null;
  openMenu: (menu: Omit<RadialMenuState, 'token'>) => void;
  /** Starts the exit animation. The overlay calls `finishClose` when it has played. */
  closeMenu: () => void;
  finishClose: () => void;
  openSheet: (actionId: string, parcelId: string, params?: SheetParams) => void;
  closeSheet: () => void;
}

export const useRadialStore = create<RadialState>((set, get) => ({
  menu: null,
  phase: 'open',
  sheet: null,
  openMenu: (menu) => set({ menu: { ...menu, token: (get().menu?.token ?? 0) + 1 }, phase: 'open' }),
  closeMenu: () => {
    if (get().menu) set({ phase: 'closing' });
  },
  finishClose: () => set({ menu: null, phase: 'open' }),
  openSheet: (actionId, parcelId, params = {}) => set((state) => ({ sheet: { actionId, parcelId, params, token: (state.sheet?.token ?? 0) + 1 } })),
  closeSheet: () => set({ sheet: null }),
}));

interface FavoritesState {
  ids: string[];
  toggle: (parcelId: string) => boolean;
}

/** Priority parcels, saved on the phone. */
export const useFavoriteParcels = create<FavoritesState>()(
  persist(
    (set, get) => ({
      ids: [],
      toggle: (parcelId) => {
        const on = !get().ids.includes(parcelId);
        set({ ids: on ? [...get().ids, parcelId] : get().ids.filter((id) => id !== parcelId) });
        return on;
      },
    }),
    { name: 'fermapp.favorite-parcels', storage: createJSONStorage(() => AsyncStorage) },
  ),
);
