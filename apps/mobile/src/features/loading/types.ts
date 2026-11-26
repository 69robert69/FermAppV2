import type { ComponentType } from 'react';
import type { SharedValue } from 'react-native-reanimated';

/** Values every plant reads; all of them live on the UI thread. */
export interface PlantProps {
  /** Displayed loading progress, 0 to 1. Growth follows it exactly. */
  progress: SharedValue<number>;
  /** Seconds since the loader appeared, for wind and ambient motion. */
  clock: SharedValue<number>;
  /** 1 normally, 0 when the system asks for reduced motion. */
  motion: number;
}

export type GrowthStages = readonly [string, string, string, string, string, string];

/**
 * A plant that grows while the app loads. The shell owns the progress, the
 * stage names, the messages, the bar and the fade; a plant only draws itself
 * inside the shared 200 x 240 scene (soil surface at y = 176).
 *
 * To add a plant: create a file in `plants/` exporting one of these and add it
 * to `PLANTS` in `index.tsx`.
 */
export interface PlantLoader {
  id: string;
  name: string;
  /** Six stages, entered at 0%, 17%, 33%, 50%, 67% and 83%. */
  stages: GrowthStages;
  /** Messages about this plant, mixed with the general farm messages. */
  messages: readonly string[];
  /** Highlight colour of the plant name and the end of the progress bar. */
  accent: string;
  Plant: ComponentType<PlantProps>;
}

/** Where each of the six stages starts, as a share of the progress. */
export const STAGE_STARTS = [0, 1 / 6, 2 / 6, 3 / 6, 4 / 6, 5 / 6] as const;
