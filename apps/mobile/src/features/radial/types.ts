import type { ComponentType } from 'react';
import type { Parcel, Role, Task } from '../../api/types';
import type { IconName } from '../../components/ui';
import type { MapParcelInfo } from '../map/api';

export type BadgeTone = 'info' | 'success' | 'warning' | 'danger';

/** A small counter or status mark shown on the corner of an action button. */
export interface RadialBadge {
  label: string;
  tone: BadgeTone;
}

/** Everything the wheel knows about the parcel it was opened on. */
export interface ParcelSnapshot {
  parcel: Parcel;
  info: MapParcelInfo | null;
  /** Tasks of the parcel that are not done yet. */
  openTasks: Task[];
  overdueTasks: number;
  favorite: boolean;
  role: Role | undefined;
}

/** Free-form values an action passes to its sheet, for example a preset task type. */
export type SheetParams = Record<string, string | number | boolean | undefined>;

export interface RadialContext extends ParcelSnapshot {
  navigate: (screen: string, params?: object) => void;
  openSheet: (actionId: string, params?: SheetParams) => void;
  openParcel: (parcel: Parcel) => void;
  toast: (message: string) => void;
}

export interface RadialSheetProps {
  parcel: Parcel;
  params: SheetParams;
  close: () => void;
  navigate: (screen: string, params?: object) => void;
  openSheet: (actionId: string, params?: SheetParams) => void;
  openParcel: (parcel: Parcel) => void;
}

/**
 * One slice of the parcel wheel. An action either runs directly (`run`) or opens
 * its own bottom sheet (`sheet`). New actions are added with `registerRadialAction`
 * and need no change in the wheel itself.
 */
export interface RadialAction {
  id: string;
  /** Full name, shown in the hub while the action is focused. */
  label: string;
  /** Short name printed under the button. */
  shortLabel: string;
  icon: IconName | ((snapshot: ParcelSnapshot) => IconName);
  accent: string;
  /** Lower numbers come first, clockwise from the top. */
  order: number;
  isAvailable?: (snapshot: ParcelSnapshot) => boolean;
  badge?: (snapshot: ParcelSnapshot) => RadialBadge | null;
  /** One line of live status shown in the hub, for example "Irrigating, 42%". */
  hint?: (snapshot: ParcelSnapshot) => string | null;
  /** Shown in a title bar above the sheet. Defaults to `label`. */
  sheetTitle?: string;
  sheet?: ComponentType<RadialSheetProps>;
  run?: (context: RadialContext) => void | Promise<void>;
}
