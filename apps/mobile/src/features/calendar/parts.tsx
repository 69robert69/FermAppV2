import { MaterialCommunityIcons } from '@expo/vector-icons';
import {
  bestDayFor,
  CALENDAR_CATEGORIES,
  CATEGORY_ORDER,
  categoryOf,
  shiftDay,
  weatherWarnings,
  type CalendarDay,
  type CalendarTask,
  type ResourceConflict,
} from '@fermapp/domain';
import { useState, type ReactNode } from 'react';
import { Modal, Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { GestureDetector, usePanGesture } from 'react-native-gesture-handler';
import Animated, { useAnimatedStyle, useSharedValue, withSpring } from 'react-native-reanimated';
import { Choice, Txt, type IconName } from '../../components/ui';
import { updateTask, type CalendarData } from '../../firebase/calendar';
import { shortDate } from '../../lib/format';
import { colors, fonts, space } from '../../theme';

export const PRIORITY_LABEL = { LOW: 'Low', MEDIUM: 'Medium', HIGH: 'High', URGENT: 'Urgent' } as const;

export interface Names {
  parcel: (id: string | null) => string | null;
  member: (id: string | null) => string | null;
  machine: (id: string | null) => string | null;
}

export function namesOf(data: CalendarData | undefined): Names {
  return {
    parcel: (id) => (id ? data?.parcels.find((item) => item.id === id)?.name ?? null : null),
    member: (id) => (id ? data?.members.find((item) => item.id === id)?.name ?? null : null),
    machine: (id) => (id ? data?.machines.find((item) => item.id === id)?.name ?? null : null),
  };
}

export function Legend() {
  return (
    <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.legend}>
      {CATEGORY_ORDER.map((category) => (
        <View key={category} style={styles.legendItem}>
          <View style={[styles.dot, { backgroundColor: CALENDAR_CATEGORIES[category].color }]} />
          <Txt variant="small">{CALENDAR_CATEGORIES[category].label}</Txt>
        </View>
      ))}
    </ScrollView>
  );
}

export interface DragApi {
  /** A long press lifted a chip. */
  onStart: () => void;
  onMove: (absoluteY: number) => void;
  onDrop: (task: CalendarTask, absoluteY: number) => void;
}

/** A task in the week or day view: tap to edit, long-press and drag to another day. */
export function TaskChip({ task, names, days, conflicts, onPress, drag, expanded }: {
  task: CalendarTask;
  names: Names;
  days: readonly CalendarDay[];
  conflicts: readonly ResourceConflict[];
  onPress: () => void;
  drag: DragApi | null;
  expanded?: boolean;
}) {
  const category = CALENDAR_CATEGORIES[categoryOf(task.type)];
  const warnings = weatherWarnings(task, days).filter((warning) => warning.level === 'WARNING');
  const conflicted = conflicts.some((conflict) => conflict.taskIds.includes(task.id));
  const offset = useSharedValue({ x: 0, y: 0 });
  const lifted = useSharedValue(0);
  const style = useAnimatedStyle(() => ({
    transform: [{ translateX: offset.value.x }, { translateY: offset.value.y }, { scale: 1 + lifted.value * 0.04 }],
    zIndex: lifted.value ? 10 : 0,
    shadowOpacity: lifted.value * 0.4,
    elevation: lifted.value * 8,
  }));
  const pan = usePanGesture({
    enabled: drag != null && task.status !== 'DONE',
    activateAfterLongPress: 280,
    runOnJS: true,
    onActivate: () => {
      lifted.value = withSpring(1);
      drag?.onStart();
    },
    onUpdate: (event) => {
      offset.value = { x: event.translationX, y: event.translationY };
      drag?.onMove(event.absoluteY);
    },
    onDeactivate: (event) => {
      drag?.onDrop(task, event.absoluteY);
    },
    onFinalize: () => {
      offset.value = withSpring({ x: 0, y: 0 });
      lifted.value = withSpring(0);
    },
  });
  const who = [names.member(task.assigneeId), names.machine(task.machineId)].filter(Boolean).join(' · ');
  return (
    <GestureDetector gesture={pan}>
      <Animated.View style={[styles.chip, { borderLeftColor: category.color }, task.status === 'DONE' && styles.chipDone, style]}>
        <Pressable onPress={onPress} accessibilityRole="button" accessibilityHint={drag ? 'Long press and drag to move it to another day' : undefined} style={{ gap: 2 }}>
          <View style={styles.chipHead}>
            <MaterialCommunityIcons name={category.icon as IconName} size={14} color={category.color} />
            <Txt variant="small" color={colors.chaff} numberOfLines={expanded ? 2 : 1} style={styles.chipTitle}>{task.title}</Txt>
            {task.source === 'AI' ? <Txt variant="small">🤖</Txt> : null}
            {task.priority === 'HIGH' || task.priority === 'URGENT' ? <MaterialCommunityIcons name="flag" size={13} color={task.priority === 'URGENT' ? colors.rust : colors.amber} /> : null}
            {warnings.length ? <MaterialCommunityIcons name="weather-cloudy-alert" size={14} color={colors.amber} /> : null}
            {conflicted ? <MaterialCommunityIcons name="alert" size={14} color={colors.rust} /> : null}
          </View>
          {expanded ? (
            <>
              <Txt variant="small">{[names.parcel(task.parcelId), who, `${PRIORITY_LABEL[task.priority]} priority`].filter(Boolean).join(' · ')}</Txt>
              {warnings.map((warning) => <Txt key={warning.title} variant="small" color={colors.amber}>{`⚠ ${warning.title}: ${warning.reason}`}</Txt>)}
              {conflicts.filter((conflict) => conflict.taskIds.includes(task.id)).map((conflict) => <Txt key={conflict.key} variant="small" color={colors.rust}>{`⚠ ${conflict.reason}`}</Txt>)}
            </>
          ) : null}
        </Pressable>
      </Animated.View>
    </GestureDetector>
  );
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <View style={{ gap: space.xs }}>
      <Txt variant="small" style={styles.sheetLabel}>{label}</Txt>
      {children}
    </View>
  );
}

/** Edit one task: date, priority, operator, machine and status. Every change saves at once. */
export function TaskSheet({ task, data, days, conflicts, canEdit, onClose }: {
  task: CalendarTask | null;
  data: CalendarData | undefined;
  days: readonly CalendarDay[];
  conflicts: readonly ResourceConflict[];
  canEdit: boolean;
  onClose: () => void;
}) {
  const [saving, setSaving] = useState(false);
  if (!task) return null;
  const names = namesOf(data);
  const category = CALENDAR_CATEGORIES[categoryOf(task.type)];
  const warnings = weatherWarnings(task, days);
  const best = bestDayFor(task.type, task.day, days);
  const save = async (edit: Parameters<typeof updateTask>[1]) => {
    setSaving(true);
    await updateTask(task.id, edit).finally(() => setSaving(false));
    onClose();
  };
  // Operators first: they do the field work, but anyone on the farm can be responsible.
  const operators = [...(data?.members ?? [])].sort((a, b) => Number(b.type === 'OPERATOR') - Number(a.type === 'OPERATOR') || a.name.localeCompare(b.name));
  return (
    <Modal visible transparent animationType="slide" onRequestClose={onClose}>
      <Pressable style={styles.backdrop} onPress={onClose} accessibilityLabel="Close" />
      <View style={styles.sheet}>
        <ScrollView contentContainerStyle={{ gap: space.md, paddingBottom: space.xl }}>
          <View style={styles.chipHead}>
            <View style={[styles.dot, { backgroundColor: category.color }]} />
            <Txt variant="small" style={styles.sheetLabel}>{category.label.toUpperCase()}</Txt>
            {task.source === 'AI' ? <Txt variant="small">🤖 AI Generated</Txt> : null}
          </View>
          <Txt variant="title">{task.title}</Txt>
          <Txt variant="small">{[names.parcel(task.parcelId), `Due ${shortDate(task.day)}`].filter(Boolean).join(' · ')}</Txt>
          {warnings.map((warning) => <Txt key={warning.title} variant="small" color={warning.level === 'WARNING' ? colors.amber : colors.straw}>{`⚠ ${warning.title}: ${warning.reason}`}</Txt>)}
          {conflicts.filter((conflict) => conflict.taskIds.includes(task.id)).map((conflict) => (
            <Txt key={conflict.key} variant="small" color={colors.rust}>{`⚠ Resource conflict: ${conflict.reason}`}</Txt>
          ))}
          {canEdit ? (
            <>
              <Row label="DATE">
                <View style={styles.buttons}>
                  <SheetButton icon="chevron-left" label="Day before" disabled={saving} onPress={() => void save({ day: shiftDay(task.day, -1) })} />
                  <SheetButton icon="chevron-right" label="Day after" disabled={saving} onPress={() => void save({ day: shiftDay(task.day, 1) })} />
                  <SheetButton icon="calendar-week" label="+1 week" disabled={saving} onPress={() => void save({ day: shiftDay(task.day, 7) })} />
                  {best && best !== task.day ? <SheetButton icon="weather-partly-cloudy" label={`Best weather: ${shortDate(best)}`} disabled={saving} onPress={() => void save({ day: best })} /> : null}
                </View>
              </Row>
              <Row label="PRIORITY">
                <Choice options={(Object.keys(PRIORITY_LABEL) as CalendarTask['priority'][]).map((value) => ({ value, label: PRIORITY_LABEL[value] }))} value={task.priority} onChange={(priority) => void save({ priority })} />
              </Row>
              <Row label="OPERATOR">
                <Choice options={[{ value: 'none', label: 'Nobody' }, ...operators.map((member) => ({ value: member.id, label: member.name }))]} value={task.assigneeId ?? 'none'} onChange={(id) => void save({ assigneeId: id === 'none' ? null : id })} />
              </Row>
              <Row label="MACHINE">
                <Choice options={[{ value: 'none', label: 'None' }, ...(data?.machines ?? []).map((machine) => ({ value: machine.id, label: machine.name }))]} value={task.machineId ?? 'none'} onChange={(id) => void save({ machineId: id === 'none' ? null : id })} />
              </Row>
              <Row label="STATUS">
                <Choice options={[{ value: 'TODO', label: 'To Do' }, { value: 'IN_PROGRESS', label: 'In Progress' }, { value: 'DONE', label: 'Done' }]} value={task.status} onChange={(status) => void save({ status: status as CalendarTask['status'] })} />
              </Row>
            </>
          ) : (
            <Txt variant="small">Only managers and agronomists can reschedule or reassign work.</Txt>
          )}
        </ScrollView>
      </View>
    </Modal>
  );
}

function SheetButton({ icon, label, onPress, disabled }: { icon: IconName; label: string; onPress: () => void; disabled?: boolean }) {
  return (
    <Pressable accessibilityRole="button" disabled={disabled} onPress={onPress} style={styles.sheetButton}>
      <MaterialCommunityIcons name={icon} size={16} color={colors.wheat} />
      <Txt variant="small" color={colors.wheat} style={{ fontFamily: fonts.semibold }}>{label}</Txt>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  legend: { gap: space.md, paddingVertical: space.xs },
  legendItem: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  dot: { width: 10, height: 10, borderRadius: 5 },
  chip: { backgroundColor: colors.ridge, borderRadius: 6, borderLeftWidth: 4, paddingVertical: 6, paddingHorizontal: 8, shadowColor: '#000', shadowOffset: { width: 0, height: 4 }, shadowRadius: 8 },
  chipDone: { opacity: 0.5 },
  chipHead: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  chipTitle: { flex: 1, fontFamily: fonts.medium },
  backdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.45)' },
  sheet: { maxHeight: '80%', backgroundColor: colors.furrow, borderTopLeftRadius: 16, borderTopRightRadius: 16, padding: space.lg },
  sheetLabel: { fontFamily: fonts.semibold, letterSpacing: 0.8, fontSize: 11 },
  buttons: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  sheetButton: { flexDirection: 'row', alignItems: 'center', gap: 4, minHeight: 36, paddingHorizontal: 12, borderRadius: 18, borderWidth: 1, borderColor: colors.line },
});
