import { MaterialCommunityIcons } from '@expo/vector-icons';
import {
  FIELD_STATE_COLORS,
  FIELD_STATE_LABELS,
  fieldStatus,
  summarizeFieldStatus,
  type FieldState,
  type FieldStatus,
} from '@fermapp/domain';
import { useQuery } from '@tanstack/react-query';
import { useMemo } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';
import { apiGet } from '../../api/client';
import type { Parcel, Task } from '../../api/types';
import { Panel, Section, Stat, Txt, type IconName } from '../../components/ui';
import { shortDate } from '../../lib/format';
import { useNow, useParcels } from '../../lib/hooks';
import { colors, fonts, space } from '../../theme';
import { useMapOverview } from '../map/api';
import { isRunActive } from '../operations/finish';

export const FIELD_STATE_ICONS: Record<FieldState, IconName> = {
  OVERDUE: 'alert-octagon',
  DUE_TODAY: 'clock-alert-outline',
  ACTIVE: 'tractor-variant',
  SCHEDULED: 'calendar-clock',
  COMPLETE: 'check-circle',
};

const STATUS_TICK_MS = 60_000;

/**
 * The plan status of every parcel: tasks due, late or done, and machines at
 * work right now. Recomputed every minute, so "today" and "late" stay true.
 */
export function useFieldStatuses(): Map<string, FieldStatus> {
  const parcels = useParcels();
  const tasks = useQuery({ queryKey: ['tasks'], staleTime: 30_000, queryFn: () => apiGet<Task[]>('/tasks') });
  const overview = useMapOverview();
  const now = useNow(STATUS_TICK_MS);
  return useMemo(() => {
    const byParcel = new Map<string, Task[]>();
    for (const task of tasks.data ?? []) {
      if (!task.parcel) continue;
      byParcel.set(task.parcel.id, [...(byParcel.get(task.parcel.id) ?? []), task]);
    }
    const active = new Map((overview.data ?? []).map((item) => [item.parcelId, item.work.filter((work) => isRunActive(work, now)).length]));
    return new Map(
      (parcels.data ?? []).map((parcel) => [parcel.id, fieldStatus({ tasks: byParcel.get(parcel.id) ?? [], activeWork: active.get(parcel.id) ?? 0, now })]),
    );
  }, [parcels.data, tasks.data, overview.data, now]);
}

export function useFieldStatus(parcelId: string): FieldStatus | undefined {
  return useFieldStatuses().get(parcelId);
}

/** State badge: icon and text in the state colour, so it reads without colour too. */
export function FieldStatusBadge({ status, compact }: { status: FieldStatus; compact?: boolean }) {
  const color = FIELD_STATE_COLORS[status.state];
  return (
    <View style={[styles.badge, compact && styles.badgeCompact, { borderColor: color, backgroundColor: `${color}26` }]} accessibilityLabel={`${FIELD_STATE_LABELS[status.state]}: ${status.badge}`}>
      <MaterialCommunityIcons name={FIELD_STATE_ICONS[status.state]} size={compact ? 11 : 14} color={color} />
      <Txt variant="small" color={colors.chaff} numberOfLines={1} style={[styles.badgeText, compact && styles.badgeTextCompact]}>{status.badge}</Txt>
    </View>
  );
}

/** Field Completion Score: share of the season's planned tasks that are done. */
export function CompletionScore({ status, compact }: { status: FieldStatus; compact?: boolean }) {
  const score = status.completionScore;
  return (
    <View style={{ gap: 3 }} accessibilityLabel={score == null ? 'No tasks planned this season' : `Field completion ${score}%`}>
      <View style={styles.scoreHead}>
        <Txt variant="small" style={compact ? styles.small : undefined}>Field completion</Txt>
        <Txt variant="small" color={colors.chaff} style={[styles.scoreValue, compact && styles.small]}>
          {score == null ? 'No tasks' : `${score}%`}
        </Txt>
      </View>
      <View style={[styles.track, compact && styles.trackCompact]}>
        <View style={[styles.fill, { width: `${score ?? 0}%`, backgroundColor: FIELD_STATE_COLORS.COMPLETE }]} />
      </View>
      {!compact && score != null ? <Txt variant="small">{`${status.done} of ${status.total} planned tasks done this season`}</Txt> : null}
    </View>
  );
}

/** Plan status in the parcel panel and sheet. */
export function FieldStatusSection({ parcelId }: { parcelId: string }) {
  const status = useFieldStatus(parcelId);
  if (!status) return null;
  return (
    <Section title="Field status">
      <Panel>
        <FieldStatusBadge status={status} />
        <Txt variant="small">
          {status.state === 'COMPLETE'
            ? status.later > 0 && status.nextDue
              ? `All planned work is complete. Next: ${status.nextDue.title}, ${shortDate(status.nextDue.dueDate)}.`
              : 'All planned work has been completed.'
            : [
                status.overdue ? `${status.overdue} overdue` : null,
                status.dueToday ? `${status.dueToday} due today` : null,
                status.upcoming ? `${status.upcoming} in the next 7 days` : null,
              ]
                .filter(Boolean)
                .join(', ') || FIELD_STATE_LABELS[status.state]}
        </Txt>
        {status.nextDue && status.state !== 'COMPLETE' ? <Txt variant="small" color={colors.chaff}>{`Next: ${status.nextDue.title}, ${shortDate(status.nextDue.dueDate)}`}</Txt> : null}
        <CompletionScore status={status} />
      </Panel>
    </Section>
  );
}

/** Operations at a glance on the dashboard: finished, in progress, waiting, late and urgent parcels. */
export function OperationalSection({ onOpenParcel }: { onOpenParcel: (parcel: Parcel) => void }) {
  const parcels = useParcels();
  const statuses = useFieldStatuses();
  const list = (parcels.data ?? []).filter((parcel) => !parcel.archivedAt);
  const items = list.flatMap((parcel) => {
    const status = statuses.get(parcel.id);
    return status ? [{ parcelId: parcel.id, name: parcel.name, status }] : [];
  });
  if (items.length === 0) return null;
  const summary = summarizeFieldStatus(items);
  const byId = new Map(list.map((parcel) => [parcel.id, parcel]));
  const total = items.length;
  const counts: Record<FieldState, number> = { OVERDUE: 0, DUE_TODAY: 0, ACTIVE: 0, SCHEDULED: 0, COMPLETE: 0 };
  items.forEach((item) => (counts[item.status.state] += 1));

  return (
    <Section title="Field operations">
      <Panel>
        {/* Parcels by state, in the validated order, with a 2px gap and a legend below. */}
        <View style={styles.stack}>
          {(Object.keys(counts) as FieldState[]).filter((state) => counts[state] > 0).map((state) => (
            <View key={state} style={{ flex: counts[state] / total, backgroundColor: FIELD_STATE_COLORS[state] }} />
          ))}
        </View>
        <View style={styles.legend}>
          {(Object.keys(counts) as FieldState[]).map((state) => (
            <View key={state} style={styles.legendItem}>
              <MaterialCommunityIcons name={FIELD_STATE_ICONS[state]} size={13} color={FIELD_STATE_COLORS[state]} />
              <Txt variant="small">{`${FIELD_STATE_LABELS[state]} ${counts[state]}`}</Txt>
            </View>
          ))}
        </View>
        <View style={styles.stats}>
          <Stat value={String(summary.complete)} label="Fully completed" color={FIELD_STATE_COLORS.COMPLETE} />
          <Stat value={String(summary.active)} label="In progress" color={FIELD_STATE_COLORS.ACTIVE} />
          <Stat value={String(summary.pending)} label="With work pending" />
          <Stat value={String(summary.overdue)} label="With overdue work" color={summary.overdue ? FIELD_STATE_COLORS.OVERDUE : undefined} />
          <Stat value={String(summary.next7Days)} label="Tasks in the next 7 days" />
          <Stat value={summary.averageScore == null ? '-' : `${summary.averageScore}%`} label="Average field completion" />
        </View>
      </Panel>
      {summary.urgent.length > 0 ? (
        <Panel style={{ borderWidth: 1, borderColor: `${FIELD_STATE_COLORS.OVERDUE}88` }}>
          <Txt variant="subtitle">Needs attention now</Txt>
          {summary.urgent.slice(0, 5).map((item) => {
            const parcel = byId.get(item.parcelId);
            return (
              <Pressable key={item.parcelId} accessibilityRole="button" onPress={() => parcel && onOpenParcel(parcel)} style={styles.row}>
                <Txt color={colors.chaff} style={{ flex: 1 }} numberOfLines={1}>{item.name}</Txt>
                <FieldStatusBadge status={item.status} compact />
              </Pressable>
            );
          })}
        </Panel>
      ) : null}
      {summary.mostAdvanced.length > 0 ? (
        <Panel>
          <Txt variant="subtitle">Most advanced parcels</Txt>
          {summary.mostAdvanced.map((item) => {
            const parcel = byId.get(item.parcelId);
            const status = statuses.get(item.parcelId)!;
            return (
              <Pressable key={item.parcelId} accessibilityRole="button" onPress={() => parcel && onOpenParcel(parcel)} style={styles.rankRow}>
                <Txt color={colors.chaff} numberOfLines={1}>{item.name}</Txt>
                <CompletionScore status={status} compact />
              </Pressable>
            );
          })}
        </Panel>
      ) : null}
    </Section>
  );
}

const styles = StyleSheet.create({
  badge: { flexDirection: 'row', alignItems: 'center', gap: 5, alignSelf: 'flex-start', paddingHorizontal: 8, minHeight: 26, borderRadius: 13, borderWidth: 1, maxWidth: '100%' },
  badgeCompact: { minHeight: 20, paddingHorizontal: 6, gap: 3, borderRadius: 10 },
  badgeText: { fontFamily: fonts.semibold, fontSize: 12, lineHeight: 15, flexShrink: 1 },
  badgeTextCompact: { fontSize: 10, lineHeight: 12 },
  scoreHead: { flexDirection: 'row', justifyContent: 'space-between' },
  scoreValue: { fontFamily: fonts.semibold },
  small: { fontSize: 10.5, lineHeight: 13 },
  track: { height: 8, borderRadius: 4, backgroundColor: colors.ridge, overflow: 'hidden' },
  trackCompact: { height: 4, borderRadius: 2 },
  fill: { height: '100%', borderRadius: 4 },
  stack: { flexDirection: 'row', height: 12, gap: 2, borderRadius: 6, overflow: 'hidden', backgroundColor: colors.ridge },
  legend: { flexDirection: 'row', flexWrap: 'wrap', columnGap: space.md, rowGap: 4 },
  legendItem: { flexDirection: 'row', alignItems: 'center', gap: 4 },
  stats: { flexDirection: 'row', flexWrap: 'wrap', rowGap: space.md },
  row: { flexDirection: 'row', alignItems: 'center', gap: space.sm, minHeight: 44 },
  rankRow: { gap: 4, paddingVertical: space.xs },
});
