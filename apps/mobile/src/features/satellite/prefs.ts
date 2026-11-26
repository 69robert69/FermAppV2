import AsyncStorage from '@react-native-async-storage/async-storage';
import { useEffect, useState } from 'react';
import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import type { MapLayer } from './planetary';

interface SatellitePrefs {
  /** "Enable Satellite Monitoring": off means no satellite request at all. */
  enabled: boolean;
  /** "Enable NDVI Layers" on the farm map. */
  mapLayers: boolean;
  /** "Enable AI Analysis": findings reach the copilot. */
  aiAnalysis: boolean;
  /** "Enable Automatic Field Alerts": notifications for problems. */
  autoAlerts: boolean;
  /** "Enable Satellite Auto Refresh": new images are analysed in the background. */
  autoRefresh: boolean;
  /** The overlay shown on the farm map. */
  mapLayer: MapLayer | null;
  set: (changes: Partial<Omit<SatellitePrefs, 'set'>>) => void;
}

export const useSatellitePrefs = create<SatellitePrefs>()(
  persist(
    (set) => ({
      enabled: true,
      mapLayers: true,
      aiAnalysis: true,
      autoAlerts: true,
      autoRefresh: true,
      mapLayer: null,
      set: (changes) => set(changes),
    }),
    { name: 'fermapp.satellite-prefs', storage: createJSONStorage(() => AsyncStorage) },
  ),
);

/** Settings once loaded from the phone, so turned-off features never fetch during start-up. */
export function useSatelliteSettings() {
  const prefs = useSatellitePrefs();
  const [hydrated, setHydrated] = useState(() => useSatellitePrefs.persist.hasHydrated());
  useEffect(() => {
    if (hydrated) return;
    const unsubscribe = useSatellitePrefs.persist.onFinishHydration(() => setHydrated(true));
    if (useSatellitePrefs.persist.hasHydrated()) setHydrated(true);
    return unsubscribe;
  }, [hydrated]);
  return { ...prefs, ready: hydrated, active: hydrated && prefs.enabled };
}
