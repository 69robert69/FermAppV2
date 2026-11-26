import { TILLAGE_SYSTEMS } from '@fermapp/domain';
import { canOperate, canPlan } from '../../store/auth';
import { colors } from '../../theme';
import { registerRadialActions } from './registry';
import { CropStatusSheet, FieldWorkSheet, IrrigationSheet, MachineSheet, NoteSheet, PhotoSheet, PlanSheet, TasksSheet, TillageSheet } from './sheets';
import { useFavoriteParcels } from './store';
import type { RadialAction } from './types';

const plural = (count: number, one: string, many: string) => `${count} ${count === 1 ? one : many}`;

/**
 * The default parcel wheel, clockwise from the top. Other features can add their
 * own slice with `registerRadialAction({ id, order, ... })` without touching this file.
 */
export const DEFAULT_RADIAL_ACTIONS: RadialAction[] = [
  {
    id: 'machine',
    label: 'Add tractor to work',
    shortLabel: 'Tractor',
    icon: 'tractor-variant',
    accent: colors.wheat,
    order: 10,
    isAvailable: (s) => canOperate(s.role),
    badge: (s) => (s.info?.work.length ? { label: String(s.info.work.length), tone: 'info' } : null),
    hint: (s) => (s.info?.work.length ? `${plural(s.info.work.length, 'machine', 'machines')} working here` : 'No machine assigned'),
    sheet: MachineSheet,
  },
  {
    id: 'irrigation',
    label: 'Irrigation',
    shortLabel: 'Irrigation',
    icon: 'water',
    accent: colors.water,
    order: 20,
    badge: (s) => {
      const runs = s.info?.irrigation ?? [];
      if (runs.some((run) => run.status === 'PAUSED')) return { label: '!', tone: 'warning' };
      return runs.length ? { label: `${Math.floor(runs[0]!.progress.percent)}%`, tone: 'info' } : null;
    },
    hint: (s) => {
      const run = s.info?.irrigation[0];
      if (!run) return 'Not irrigating';
      return run.status === 'PAUSED' ? 'Irrigation paused' : `Irrigating, ${Math.floor(run.progress.percent)}%`;
    },
    sheet: IrrigationSheet,
  },
  {
    id: 'tasks',
    label: 'Parcel to-do',
    shortLabel: 'To-Do',
    icon: 'checkbox-marked-circle-outline',
    accent: colors.leaf,
    order: 30,
    badge: (s) => {
      if (s.overdueTasks > 0) return { label: String(s.overdueTasks), tone: 'danger' };
      const open = Math.max(s.openTasks.length, s.info?.openTasks ?? 0);
      return open ? { label: String(open), tone: 'success' } : null;
    },
    hint: (s) => {
      const open = Math.max(s.openTasks.length, s.info?.openTasks ?? 0);
      if (!open) return 'Nothing open';
      return `${plural(open, 'open task', 'open tasks')}${s.overdueTasks ? `, ${s.overdueTasks} overdue` : ''}`;
    },
    sheet: TasksSheet,
  },
  {
    id: 'field-work',
    label: 'Add field work',
    shortLabel: 'Field work',
    icon: 'sprout',
    accent: colors.amber,
    order: 40,
    isAvailable: (s) => canOperate(s.role) || canPlan(s.role),
    hint: (s) => (s.parcel.tillageSystem ? `Recommended: ${TILLAGE_SYSTEMS[s.parcel.tillageSystem].operations.map((operation) => operation.label.toLowerCase()).join(', ')}` : 'Discing, sowing, spraying...'),
    sheet: FieldWorkSheet,
  },
  {
    id: 'crop-status',
    label: 'Crop status',
    shortLabel: 'Crop',
    icon: 'chart-donut',
    accent: colors.leaf,
    order: 50,
    badge: (s) => (s.parcel.satellite?.[0]?.healthClass === 'PROBLEM' ? { label: '!', tone: 'danger' } : null),
    hint: (s) => {
      const crop = s.info?.crop;
      if (!crop) return 'No crop this season';
      return s.info?.growthPct != null ? `${crop.name}, ${s.info.growthPct}% grown` : crop.name;
    },
    sheet: CropStatusSheet,
  },
  {
    id: 'tillage',
    label: 'Tillage technology',
    shortLabel: 'Tillage',
    icon: 'layers-triple-outline',
    accent: colors.wheat,
    order: 55,
    badge: (s) => (s.parcel.tillageSystem ? null : { label: '?', tone: 'warning' }),
    hint: (s) => (s.parcel.tillageSystem ? `${TILLAGE_SYSTEMS[s.parcel.tillageSystem].name}, soil ${TILLAGE_SYSTEMS[s.parcel.tillageSystem].soilHealth}/100` : 'Not set yet'),
    sheet: TillageSheet,
  },
  {
    id: 'note',
    label: 'Farmer note',
    shortLabel: 'Note',
    icon: 'note-edit-outline',
    accent: colors.chaff,
    order: 60,
    hint: () => 'Quick field observation',
    sheet: NoteSheet,
  },
  {
    id: 'photo',
    label: 'Add photo',
    shortLabel: 'Photo',
    icon: 'camera-plus-outline',
    accent: colors.chaff,
    order: 70,
    hint: () => 'Camera or gallery, kept in history',
    sheet: PhotoSheet,
  },
  {
    id: 'plan',
    label: 'Schedule work',
    shortLabel: 'Plan',
    icon: 'calendar-clock',
    accent: colors.water,
    order: 80,
    isAvailable: (s) => canPlan(s.role),
    hint: () => 'Pick a date and who does it',
    sheetTitle: 'Schedule work',
    sheet: PlanSheet,
  },
  {
    id: 'favorite',
    label: 'Priority parcel',
    shortLabel: 'Favorite',
    icon: (s) => (s.favorite ? 'star' : 'star-outline'),
    accent: colors.wheat,
    order: 90,
    hint: (s) => (s.favorite ? 'Remove from priority parcels' : 'Mark as a priority parcel'),
    run: (ctx) => {
      const on = useFavoriteParcels.getState().toggle(ctx.parcel.id);
      ctx.toast(on ? `${ctx.parcel.name} marked as priority.` : `${ctx.parcel.name} removed from priority.`);
    },
  },
];

registerRadialActions(DEFAULT_RADIAL_ACTIONS);
