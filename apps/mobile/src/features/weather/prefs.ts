import AsyncStorage from '@react-native-async-storage/async-storage';
import { useEffect, useState } from 'react';
import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';

export const REFRESH_OPTIONS: { value: string; label: string }[] = [
  { value: '15', label: '15 min' },
  { value: '30', label: '30 min' },
  { value: '60', label: '1 hour' },
  { value: '180', label: '3 hours' },
];

interface WeatherPrefs {
  /** "Show Weather Widget" in Settings. */
  enabled: boolean;
  refreshMinutes: number;
  /** Whether the widget is open. `null` follows the screen size; not saved between sessions. */
  expanded: boolean | null;
  setEnabled: (enabled: boolean) => void;
  setRefreshMinutes: (minutes: number) => void;
  setExpanded: (expanded: boolean | null) => void;
}

export const useWeatherPrefs = create<WeatherPrefs>()(
  persist(
    (set) => ({
      enabled: true,
      refreshMinutes: 30,
      expanded: null,
      setEnabled: (enabled) => set({ enabled }),
      setRefreshMinutes: (refreshMinutes) => set({ refreshMinutes }),
      setExpanded: (expanded) => set({ expanded }),
    }),
    {
      name: 'fermapp.weather-prefs',
      storage: createJSONStorage(() => AsyncStorage),
      partialize: ({ enabled, refreshMinutes }) => ({ enabled, refreshMinutes }),
    },
  ),
);

/**
 * True only once the saved setting is known, so a widget the user turned off
 * never loads weather during the moment the app starts.
 */
export function useWeatherWidgetEnabled() {
  const enabled = useWeatherPrefs((state) => state.enabled);
  const [hydrated, setHydrated] = useState(() => useWeatherPrefs.persist.hasHydrated());
  useEffect(() => {
    if (hydrated) return;
    const unsubscribe = useWeatherPrefs.persist.onFinishHydration(() => setHydrated(true));
    if (useWeatherPrefs.persist.hasHydrated()) setHydrated(true);
    return unsubscribe;
  }, [hydrated]);
  return hydrated && enabled;
}
