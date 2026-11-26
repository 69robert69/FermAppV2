import AsyncStorage from '@react-native-async-storage/async-storage';
import { useEffect, useState } from 'react';
import { LoaderShell } from './LoaderShell';
import { alfalfaLoader } from './plants/alfalfa';
import { cornLoader } from './plants/corn';
import { potatoLoader } from './plants/potato';
import { soybeanLoader } from './plants/soybean';
import { sunflowerLoader } from './plants/sunflower';
import { wheatLoader } from './plants/wheat';
import type { PlantLoader } from './types';

export type { PlantLoader } from './types';

/** Every plant that can grow on the loading screen. Add a new one here. */
export const PLANTS: readonly PlantLoader[] = [cornLoader, sunflowerLoader, wheatLoader, soybeanLoader, potatoLoader, alfalfaLoader];

const LAST_KEY = 'fermapp.loader.last';

// The last plant is read while the fonts load, so the choice is ready when the loader mounts.
let lastShown: string | null = null;
void AsyncStorage.getItem(LAST_KEY)
  .then((value) => {
    lastShown = value;
  })
  .catch(() => undefined);

/** A random plant, never the one shown at the previous launch when another is available. */
export function pickPlant(plants: readonly PlantLoader[] = PLANTS, previous: string | null = lastShown): PlantLoader {
  const pool = plants.length > 1 ? plants.filter((plant) => plant.id !== previous) : plants;
  return pool[Math.floor(Math.random() * pool.length)] ?? plants[0]!;
}

interface Props {
  /** Real loading progress, 0 to 1. */
  progress: number;
  onFinish: () => void;
}

export function AppLoader({ progress, onFinish }: Props) {
  // Chosen once per launch, so the plant never changes mid-animation.
  const [plant] = useState(() => pickPlant());
  useEffect(() => {
    void AsyncStorage.setItem(LAST_KEY, plant.id).catch(() => undefined);
  }, [plant.id]);
  return <LoaderShell plant={plant} progress={progress} onFinish={onFinish} />;
}
