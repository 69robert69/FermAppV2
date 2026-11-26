import type { TillageSettings, TillageSystem } from '@fermapp/domain';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';

interface TillagePrefs {
  /** The user's own cost (€/ha) and diesel (l/ha) figures. Missing values use the defaults. */
  settings: TillageSettings;
  setValue: (system: TillageSystem, field: 'costPerHa' | 'fuelLPerHa', value: number | undefined) => void;
  reset: () => void;
}

/** Saved on the phone, like the diesel price of the catalog. */
export const useTillagePrefs = create<TillagePrefs>()(
  persist(
    (set) => ({
      settings: {},
      setValue: (system, field, value) =>
        set((state) => ({ settings: { ...state.settings, [system]: { ...state.settings[system], [field]: value } } })),
      reset: () => set({ settings: {} }),
    }),
    { name: 'fermapp.tillage-prefs', storage: createJSONStorage(() => AsyncStorage) },
  ),
);

export const useTillageSettings = () => useTillagePrefs((state) => state.settings);

export { CURRENCY_SYMBOL as CURRENCY } from '@fermapp/domain';
