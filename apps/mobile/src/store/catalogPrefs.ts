import { DEFAULT_FUEL_PRICE_EUR, RON_PER_EUR, type SeedFilters } from '@fermapp/domain';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';

interface CatalogPrefs {
  fuelPricePerL: number;
  setFuelPrice: (value: number) => void;
}

/** The diesel price, in euro per litre, stays saved on the phone between sessions. */
export const useCatalogPrefs = create<CatalogPrefs>()(
  persist(
    (set) => ({
      fuelPricePerL: DEFAULT_FUEL_PRICE_EUR,
      setFuelPrice: (fuelPricePerL) => set({ fuelPricePerL }),
    }),
    {
      name: 'fermapp.catalog-prefs',
      storage: createJSONStorage(() => AsyncStorage),
      // Version 1 moved the saved price from lei to euro.
      version: 1,
      migrate: (persisted, version) => {
        const state = persisted as Partial<CatalogPrefs>;
        return version < 1 && typeof state.fuelPricePerL === 'number'
          ? { ...state, fuelPricePerL: Math.round((state.fuelPricePerL / RON_PER_EUR) * 100) / 100 }
          : state;
      },
    },
  ),
);

interface CatalogFilters {
  seeds: SeedFilters;
  setSeeds: (changes: Partial<SeedFilters>) => void;
  resetSeeds: () => void;
}

/** Filters stay applied while the user goes in and out of product sheets. */
export const useCatalogFilters = create<CatalogFilters>((set) => ({
  seeds: { sort: 'name' },
  setSeeds: (changes) => set((state) => ({ seeds: { ...state.seeds, ...changes } })),
  resetSeeds: () => set({ seeds: { sort: 'name' } }),
}));
