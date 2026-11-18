import { DEFAULT_FUEL_PRICE_RON, type SeedFilters } from '@fermapp/domain';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';

interface CatalogPrefs {
  fuelPricePerL: number;
  setFuelPrice: (value: number) => void;
}

/** Prețul motorinei rămâne salvat pe telefon între sesiuni. */
export const useCatalogPrefs = create<CatalogPrefs>()(
  persist(
    (set) => ({
      fuelPricePerL: DEFAULT_FUEL_PRICE_RON,
      setFuelPrice: (fuelPricePerL) => set({ fuelPricePerL }),
    }),
    { name: 'fermapp.catalog-prefs', storage: createJSONStorage(() => AsyncStorage) },
  ),
);

interface CatalogFilters {
  seeds: SeedFilters;
  setSeeds: (changes: Partial<SeedFilters>) => void;
  resetSeeds: () => void;
}

/** Filtrele rămân aplicate cât timp utilizatorul intră și iese din fișele produselor. */
export const useCatalogFilters = create<CatalogFilters>((set) => ({
  seeds: { sort: 'name' },
  setSeeds: (changes) => set((state) => ({ seeds: { ...state.seeds, ...changes } })),
  resetSeeds: () => set({ seeds: { sort: 'name' } }),
}));
