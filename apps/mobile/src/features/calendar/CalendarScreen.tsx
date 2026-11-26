import { MaterialCommunityIcons } from '@expo/vector-icons';
import {
  CALENDAR_CATEGORIES,
  calendarDashboard,
  calendarSuggestions,
  categoryOf,
  dayDiff,
  detectConflicts,
  fieldTimeline,
  isOpen,
  monthGrid,
  seasonRange,
  shiftDay,
  weatherWarnings,
  weekDays,
  type CalendarDay,
  type CalendarTask,
  type TaskProposal,
} from '@fermapp/domain';
import { useQuery } from '@tanstack/react-query';
import { useCallback, useMemo, useRef, useState, type ComponentRef } from 'react';
import { ActivityIndicator, Modal, Pressable, StyleSheet, useWindowDimensions, View } from 'react-native';
import { ScrollView } from 'react-native-gesture-handler';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Choice, Empty, OfflineBanner, Txt, type IconName } from '../../components/ui';
import { farmForecastDays, loadCalendar, updateTask, type CalendarData } from '../../firebase/calendar';
import { buildFarmSnapshot, createTaskFromProposal, localDay } from '../../firebase/copilot';
import { usesFirebase } from '../../firebase/config';
import { shortDate } from '../../lib/format';
import { useNow } from '../../lib/hooks';
import { canPlan, useAuth } from '../../store/auth';
import { colors, fonts, space } from '../../theme';
import { useCopilot } from '../copilot/store';
import { Legend, namesOf, TaskChip, TaskSheet, type DragApi, type Names } from './parts';
import { suggestionKey, useCalendarPrefs, type CalendarView } from './prefs';

const VIEWS: { value: CalendarView; label: string }[] = [
  { value: 'DAY', label: 'Day' },
  { value: 'WEEK', label: 'Week' },
  { value: 'MONTH', label: 'Month' },
  { value: 'SEASON', label: 'Season' },
];
const WEEKDAY = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const MONTHS = ['Sep', 'Oct', 'Nov', 'Dec', 'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug'];

const weekdayName = (day: string) => new Date(`${day}T12:00:00Z`).toLocaleDateString('en-GB', { weekday: 'long', day: 'numeric', month: 'short', timeZone: 'UTC' });

function WeatherMini({ day }: { day: CalendarDay | undefined }) {
  if (!day) return null;
  const icon: IconName = day.precipitationMm >= 2 ? 'weather-rainy' : (day.windMaxKmh ?? 0) > 25 ? 'weather-windy' : 'weather-partly-cloudy';
  return (
    <View style={styles.weather}>
      <MaterialCommunityIcons name={icon} size={14} color={colors.straw} />
      <Txt variant="small">{`${Math.round(day.tMaxC)}°/${Math.round(day.tMinC)}°`}</Txt>
      {day.precipitationMm >= 0.5 ? <Txt variant="small" color={colors.water}>{`${Math.round(day.precipitationMm)} mm`}</Txt> : null}
      {(day.windMaxKmh ?? 0) > 15 ? <Txt variant="small" color={(day.windMaxKmh ?? 0) > 25 ? colors.amber : colors.straw}>{`${Math.round(day.windMaxKmh!)} km/h`}</Txt> : null}
    </View>
  );
}

function StatCard({ label, value, tone, active, onPress, sub }: { label: string; value: number; tone?: string; active: boolean; onPress: () => void; sub?: string }) {
  return (
    <Pressable accessibilityRole="button" accessibilityState={{ selected: active }} onPress={onPress} style={[styles.stat, active && styles.statActive]}>
      <Txt variant="figure" color={tone ?? colors.chaff}>{String(value)}</Txt>
      <Txt variant="small" color={colors.chaff}>{label}</Txt>
      {sub ? <Txt variant="small" numberOfLines={1}>{sub}</Txt> : null}
    </Pressable>
  );
}

function SuggestionCard({ proposal, names, days, canApprove, onApprove, onReject }: { proposal: TaskProposal; names: Names; days: readonly CalendarDay[]; canApprove: boolean; onApprove: () => Promise<void>; onReject: () => void }) {
  const [busy, setBusy] = useState(false);
  const style = CALENDAR_CATEGORIES[categoryOf(proposal.type)];
  const warnings = weatherWarnings({ type: proposal.type, day: proposal.dueDate }, days).filter((warning) => warning.level === 'WARNING');
  return (
    <View style={[styles.suggestion, { borderColor: style.color }]}>
      <View style={styles.rowCenter}>
        <MaterialCommunityIcons name="robot-outline" size={15} color={colors.wheat} />
        <Txt variant="small" color={colors.wheat} style={{ fontFamily: fonts.semibold }}>{`AI suggestion · ${shortDate(proposal.dueDate)}`}</Txt>
      </View>
      <Txt variant="subtitle">{proposal.title}</Txt>
      <Txt variant="small">{[names.parcel(proposal.parcelId), `window ${shortDate(proposal.window.start)}–${shortDate(proposal.window.end)}`].filter(Boolean).join(' · ')}</Txt>
      <Txt variant="small" color={colors.chaff}>{proposal.reason}</Txt>
      {warnings.map((warning) => <Txt key={warning.title} variant="small" color={colors.amber}>{`⚠ ${warning.title}`}</Txt>)}
      {canApprove ? (
        <View style={styles.rowCenter}>
          <Pressable accessibilityRole="button" disabled={busy} onPress={() => { setBusy(true); void onApprove().finally(() => setBusy(false)); }} style={[styles.pill, styles.pillPrimary]}>
            {busy ? <ActivityIndicator size="small" color={colors.wheatInk} /> : <MaterialCommunityIcons name="check" size={15} color={colors.wheatInk} />}
            <Txt variant="small" color={colors.wheatInk} style={{ fontFamily: fonts.semibold }}>Approve</Txt>
          </Pressable>
          <Pressable accessibilityRole="button" onPress={onReject} style={styles.pill}>
            <MaterialCommunityIcons name="close" size={15} color={colors.straw} />
            <Txt variant="small">Reject</Txt>
          </Pressable>
        </View>
      ) : null}
    </View>
  );
}

export function CalendarScreen() {
  const insets = useSafeAreaInsets();
  const { width } = useWindowDimensions();
  const role = useAuth((state) => state.role);
  const canEdit = usesFirebase && canPlan(role);
  const now = useNow(60_000);
  const today = localDay(now.toISOString());
  const view = useCalendarPrefs((state) => state.view);
  const setView = useCalendarPrefs((state) => state.setView);
  const rejected = useCalendarPrefs((state) => state.rejected);
  const reject = useCalendarPrefs((state) => state.reject);
  const [cursor, setCursor] = useState(today);
  const [open, setOpen] = useState<CalendarTask | null>(null);
  const [card, setCard] = useState<string | null>(null);
  const [timelineParcel, setTimelineParcel] = useState<string | null>(null);
  const [dragging, setDragging] = useState(false);
  const [hoverDay, setHoverDay] = useState<string | null>(null);

  const calendar = useQuery({ queryKey: ['calendar'], queryFn: loadCalendar, enabled: usesFirebase });
  const weather = useQuery({ queryKey: ['calendar-weather'], queryFn: farmForecastDays, enabled: usesFirebase, staleTime: 30 * 60_000 });
  const suggestions = useQuery({ queryKey: ['calendar-suggestions'], queryFn: async () => calendarSuggestions(await buildFarmSnapshot()), enabled: usesFirebase, staleTime: 10 * 60_000 });
  const data: CalendarData | undefined = calendar.data;
  const days = weather.data ?? [];
  const names = namesOf(data);
  const tasks = useMemo(() => data?.tasks ?? [], [data]);

  const conflicts = useMemo(
    () => (data ? detectConflicts({ tasks, runs: data.runs, machines: data.machines, members: data.members, parcelName: (id) => names.parcel(id) ?? 'farm-wide' }) : []),
    [data, tasks], // eslint-disable-line react-hooks/exhaustive-deps
  );
  const upcomingConflicts = conflicts.filter((conflict) => conflict.day >= today && dayDiff(today, conflict.day) <= 14);
  const dashboard = useMemo(() => (data ? calendarDashboard({ tasks, runs: data.runs, machines: data.machines, operators: data.operators, today }) : null), [data, tasks, today]);
  const pendingSuggestions = (suggestions.data ?? []).filter((item) => !rejected.includes(suggestionKey(item)));

  // Drag and drop: rows report their position when a drag starts; the drop lands on the row under the finger.
  const rows = useRef(new Map<string, ComponentRef<typeof View> | null>());
  const frames = useRef(new Map<string, { y: number; h: number }>());
  const measureRows = useCallback(() => {
    for (const [day, row] of rows.current) row?.measureInWindow((_x: number, y: number, _w: number, h: number) => frames.current.set(day, { y, h }));
  }, []);
  const dayAt = (absoluteY: number) => [...frames.current.entries()].find(([, frame]) => absoluteY >= frame.y && absoluteY <= frame.y + frame.h)?.[0] ?? null;
  const drag: DragApi | null = canEdit
    ? {
        onStart: () => {
          setDragging(true);
          measureRows();
        },
        onMove: (absoluteY) => setHoverDay(dayAt(absoluteY)),
        onDrop: (task, absoluteY) => {
          const target = dayAt(absoluteY);
          setDragging(false);
          setHoverDay(null);
          if (target && target !== task.day) void updateTask(task.id, { day: target });
        },
      }
    : null;
  const rowRef = (day: string) => (node: ComponentRef<typeof View> | null) => {
    rows.current.set(day, node);
  };

  const approve = async (proposal: TaskProposal) => {
    const action = await createTaskFromProposal(proposal, false);
    useCopilot.getState().log(action);
  };

  if (!usesFirebase) {
    return (
      <View style={[styles.screen, { paddingTop: insets.top }]}>
        <Empty icon="calendar-month-outline" title="The agricultural calendar runs in Firebase mode" hint="Start the app with Start-FermApp-Firebase.bat to plan operations here." />
      </View>
    );
  }

  const step = view === 'DAY' ? 1 : view === 'WEEK' ? 7 : 0;
  const move = (direction: -1 | 1) => {
    if (view === 'MONTH') {
      const [y, m] = cursor.split('-').map(Number);
      const next = new Date(Date.UTC(y!, m! - 1 + direction, 1, 12));
      setCursor(next.toISOString().slice(0, 10));
    } else if (view === 'SEASON') {
      setCursor(shiftDay(cursor, direction * 365));
    } else {
      setCursor(shiftDay(cursor, direction * step));
    }
  };
  const title =
    view === 'DAY' ? weekdayName(cursor)
      : view === 'WEEK' ? `${shortDate(weekDays(cursor)[0]!)} – ${shortDate(weekDays(cursor)[6]!)}`
        : view === 'MONTH' ? new Date(`${cursor}T12:00:00Z`).toLocaleDateString('en-GB', { month: 'long', year: 'numeric', timeZone: 'UTC' })
          : `Season ${seasonRange(cursor).label}`;

  const tasksOn = (day: string) => tasks.filter((task) => task.day === day).sort((a, b) => Number(b.status !== 'DONE') - Number(a.status !== 'DONE') || a.title.localeCompare(b.title));
  const suggestionsOn = (day: string) => pendingSuggestions.filter((item) => item.dueDate === day);
  const cardTasks = card && dashboard ? (dashboard as unknown as Record<string, CalendarTask[]>)[card] : null;

  const dayRow = (day: string, expanded: boolean) => {
    const list = tasksOn(day);
    const ghosts = suggestionsOn(day);
    const isToday = day === today;
    return (
      <View key={day} ref={rowRef(day)} collapsable={false} style={[styles.dayRow, isToday && styles.today, hoverDay === day && styles.dropTarget]}>
        <View style={styles.dayHead}>
          <Pressable onPress={() => { setCursor(day); setView('DAY'); }} accessibilityRole="button" style={{ flex: 1 }}>
            <Txt variant="subtitle" color={isToday ? colors.wheat : colors.chaff}>{expanded ? weekdayName(day) : `${WEEKDAY[(new Date(`${day}T12:00:00Z`).getUTCDay() + 6) % 7]} ${Number(day.slice(8))}`}</Txt>
          </Pressable>
          <WeatherMini day={days.find((item) => item.date === day)} />
        </View>
        {list.length === 0 && ghosts.length === 0 ? <Txt variant="small">{dragging ? 'Drop here' : 'Nothing planned'}</Txt> : null}
        {list.map((task) => <TaskChip key={task.id} task={task} names={names} days={days} conflicts={conflicts} onPress={() => setOpen(task)} drag={drag} expanded={expanded} />)}
        {ghosts.map((proposal) => (
          <Pressable key={proposal.key} onPress={() => { setCursor(day); setView('DAY'); }} style={[styles.ghost, { borderColor: CALENDAR_CATEGORIES[categoryOf(proposal.type)].color }]}>
            <Txt variant="small" color={colors.wheat}>{`🤖 Suggested: ${proposal.title}`}</Txt>
          </Pressable>
        ))}
      </View>
    );
  };

  const cell = (Math.min(width, 720) - space.lg * 2) / 7;
  const season = seasonRange(cursor);
  const seasonSpan = dayDiff(season.start, season.end);

  return (
    <View style={[styles.screen, { paddingTop: insets.top }]}>
      <OfflineBanner />
      <ScrollView scrollEnabled={!dragging} contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled" onScrollEndDrag={measureRows}>
        <View style={styles.rowCenter}>
          <Txt variant="display" style={{ flex: 1 }}>Calendar</Txt>
          {calendar.isFetching ? <ActivityIndicator color={colors.wheat} /> : null}
        </View>

        {dashboard ? (
          <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: space.sm }}>
            <StatCard label="Today" value={dashboard.today.length} active={card === 'today'} onPress={() => setCard(card === 'today' ? null : 'today')} />
            <StatCard label="This week" value={dashboard.thisWeek.length} active={card === 'thisWeek'} onPress={() => setCard(card === 'thisWeek' ? null : 'thisWeek')} />
            <StatCard label="Delayed" value={dashboard.delayed.length} tone={dashboard.delayed.length ? colors.rust : undefined} active={card === 'delayed'} onPress={() => setCard(card === 'delayed' ? null : 'delayed')} />
            <StatCard label="Critical soon" value={dashboard.critical.length} tone={dashboard.critical.length ? colors.amber : undefined} active={card === 'critical'} onPress={() => setCard(card === 'critical' ? null : 'critical')} />
            <StatCard label="Machines free" value={dashboard.availableMachines.length} sub={dashboard.availableMachines.map((item) => item.name).join(', ')} active={card === 'machines'} onPress={() => setCard(card === 'machines' ? null : 'machines')} />
            <StatCard label="Operators free" value={dashboard.availableOperators.length} sub={dashboard.availableOperators.map((item) => item.name).join(', ')} active={card === 'operators'} onPress={() => setCard(card === 'operators' ? null : 'operators')} />
          </ScrollView>
        ) : null}
        {card && dashboard ? (
          <View style={styles.panel}>
            {card === 'machines' || card === 'operators' ? (
              (card === 'machines' ? dashboard.availableMachines : dashboard.availableOperators).length ? (
                (card === 'machines' ? dashboard.availableMachines : dashboard.availableOperators).map((item) => <Txt key={item.id} variant="small" color={colors.chaff}>{`• ${item.name}`}</Txt>)
              ) : (
                <Txt variant="small">Everyone is busy today.</Txt>
              )
            ) : cardTasks && cardTasks.length ? (
              cardTasks.map((task) => (
                <Pressable key={task.id} onPress={() => setOpen(task)} style={styles.rowCenter}>
                  <View style={[styles.dot, { backgroundColor: CALENDAR_CATEGORIES[categoryOf(task.type)].color }]} />
                  <Txt variant="small" color={colors.chaff} style={{ flex: 1 }}>{`${task.title} · ${shortDate(task.day)}`}</Txt>
                </Pressable>
              ))
            ) : (
              <Txt variant="small">Nothing here.</Txt>
            )}
          </View>
        ) : null}

        {upcomingConflicts.length ? (
          <View style={[styles.panel, styles.conflicts]}>
            <Txt variant="subtitle" color={colors.rust}>⚠ Resource Conflict Detected</Txt>
            {upcomingConflicts.map((conflict) => (
              <Txt key={conflict.key} variant="small" color={colors.chaff}>{`${shortDate(conflict.day)} · ${conflict.title}: ${conflict.reason}`}</Txt>
            ))}
          </View>
        ) : null}

        {pendingSuggestions.length ? (
          <View style={{ gap: space.sm }}>
            <Txt variant="small" style={styles.kicker}>{`AI CALENDAR SUGGESTIONS (${pendingSuggestions.length})`}</Txt>
            <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={{ gap: space.sm }}>
              {pendingSuggestions.slice(0, 8).map((proposal) => (
                <View key={suggestionKey(proposal)} style={{ width: Math.min(300, width - space.lg * 3) }}>
                  <SuggestionCard proposal={proposal} names={names} days={days} canApprove={canEdit} onApprove={() => approve(proposal)} onReject={() => reject(suggestionKey(proposal))} />
                </View>
              ))}
            </ScrollView>
          </View>
        ) : null}

        <Choice options={VIEWS} value={view} onChange={setView} />
        <View style={styles.rowCenter}>
          <Pressable accessibilityRole="button" accessibilityLabel="Previous" onPress={() => move(-1)} hitSlop={8} style={styles.nav}>
            <MaterialCommunityIcons name="chevron-left" size={24} color={colors.chaff} />
          </Pressable>
          <Txt variant="subtitle" style={{ flex: 1, textAlign: 'center' }}>{title}</Txt>
          <Pressable accessibilityRole="button" accessibilityLabel="Next" onPress={() => move(1)} hitSlop={8} style={styles.nav}>
            <MaterialCommunityIcons name="chevron-right" size={24} color={colors.chaff} />
          </Pressable>
          {cursor !== today ? (
            <Pressable accessibilityRole="button" onPress={() => setCursor(today)} style={styles.pill}>
              <Txt variant="small" color={colors.wheat}>Today</Txt>
            </Pressable>
          ) : null}
        </View>
        <Legend />
        {canEdit && (view === 'WEEK' || view === 'DAY') ? <Txt variant="small">Long-press a task and drag it to another day. Tap it to change priority, operator or machine.</Txt> : null}

        {calendar.isPending ? <ActivityIndicator color={colors.wheat} /> : null}

        {view === 'DAY' ? (
          <View style={{ gap: space.sm }}>
            {dayRow(shiftDay(cursor, -1), false)}
            {dayRow(cursor, true)}
            {dayRow(shiftDay(cursor, 1), false)}
            {suggestionsOn(cursor).map((proposal) => (
              <SuggestionCard key={proposal.key} proposal={proposal} names={names} days={days} canApprove={canEdit} onApprove={() => approve(proposal)} onReject={() => reject(suggestionKey(proposal))} />
            ))}
          </View>
        ) : null}

        {view === 'WEEK' ? <View style={{ gap: space.sm }}>{weekDays(cursor).map((day) => dayRow(day, false))}</View> : null}

        {view === 'MONTH' ? (
          <View>
            <View style={styles.monthRow}>
              {WEEKDAY.map((name) => <Txt key={name} variant="small" style={{ width: cell, textAlign: 'center' }}>{name}</Txt>)}
            </View>
            {monthGrid(cursor).map((week) => (
              <View key={week[0]} style={styles.monthRow}>
                {week.map((day) => {
                  const list = tasksOn(day).filter(isOpen);
                  const inMonth = day.slice(0, 7) === cursor.slice(0, 7);
                  const warn = list.some((task) => weatherWarnings(task, days).some((warning) => warning.level === 'WARNING'));
                  const conflict = conflicts.some((item) => item.day === day);
                  return (
                    <Pressable key={day} accessibilityRole="button" accessibilityLabel={`${day}: ${list.length} operations`} onPress={() => { setCursor(day); setView('DAY'); }} style={[styles.monthCell, { width: cell, height: cell * 1.15 }, day === today && styles.today, !inMonth && { opacity: 0.35 }]}>
                      <View style={styles.rowCenter}>
                        <Txt variant="small" color={day === today ? colors.wheat : colors.chaff} style={{ flex: 1 }}>{String(Number(day.slice(8)))}</Txt>
                        {conflict ? <MaterialCommunityIcons name="alert" size={10} color={colors.rust} /> : warn ? <MaterialCommunityIcons name="weather-cloudy-alert" size={10} color={colors.amber} /> : null}
                      </View>
                      {list.slice(0, 3).map((task) => <View key={task.id} style={[styles.bar, { backgroundColor: CALENDAR_CATEGORIES[categoryOf(task.type)].color }]} />)}
                      {list.length > 3 ? <Txt variant="small" style={{ fontSize: 10, lineHeight: 12 }}>{`+${list.length - 3}`}</Txt> : null}
                      {suggestionsOn(day).length ? <View style={[styles.bar, styles.barGhost]} /> : null}
                    </Pressable>
                  );
                })}
              </View>
            ))}
          </View>
        ) : null}

        {view === 'SEASON' ? (
          <View style={{ gap: space.sm }}>
            <View style={[styles.rowCenter, { paddingLeft: 96 }]}>
              {MONTHS.map((month) => <Txt key={month} variant="small" style={{ flex: 1, fontSize: 10 }}>{month}</Txt>)}
            </View>
            {(data?.parcels ?? []).map((parcel) => {
              const entries = fieldTimeline({ parcelId: parcel.id, tasks, runs: data?.runs ?? [], seasons: data?.seasons[parcel.id] ?? [], from: season.start, to: season.end, today });
              return (
                <Pressable key={parcel.id} accessibilityRole="button" accessibilityLabel={`${parcel.name} timeline, ${entries.length} entries`} onPress={() => setTimelineParcel(parcel.id)} style={styles.seasonRow}>
                  <Txt variant="small" color={colors.chaff} numberOfLines={1} style={{ width: 88 }}>{parcel.name}</Txt>
                  <View style={styles.track}>
                    {today >= season.start && today <= season.end ? <View style={[styles.todayLine, { left: `${(dayDiff(season.start, today) / seasonSpan) * 100}%` }]} /> : null}
                    {entries.map((entry) => (
                      <View key={entry.id} style={[styles.mark, { left: `${(dayDiff(season.start, entry.day) / seasonSpan) * 100}%`, backgroundColor: CALENDAR_CATEGORIES[entry.category].color, opacity: entry.state === 'PLANNED' ? 0.55 : 1 }, entry.state === 'OVERDUE' && styles.markLate]} />
                    ))}
                  </View>
                </Pressable>
              );
            })}
            <Txt variant="small">Tap a field to see its whole season. Faded marks are planned; outlined in red are late.</Txt>
          </View>
        ) : null}
      </ScrollView>

      <TaskSheet task={open} data={data} days={days} conflicts={conflicts} canEdit={canEdit} onClose={() => setOpen(null)} />

      <Modal visible={timelineParcel != null} transparent animationType="slide" onRequestClose={() => setTimelineParcel(null)}>
        <Pressable style={styles.backdrop} onPress={() => setTimelineParcel(null)} />
        <View style={styles.sheet}>
          <Txt variant="title">{`${names.parcel(timelineParcel) ?? ''} · season ${season.label}`}</Txt>
          <ScrollView contentContainerStyle={{ gap: space.sm, paddingVertical: space.md }}>
            {timelineParcel
              ? fieldTimeline({ parcelId: timelineParcel, tasks, runs: data?.runs ?? [], seasons: data?.seasons[timelineParcel] ?? [], from: season.start, to: season.end, today }).map((entry) => (
                  <View key={entry.id} style={styles.rowCenter}>
                    <View style={[styles.dot, { backgroundColor: CALENDAR_CATEGORIES[entry.category].color }]} />
                    <Txt variant="small" style={{ width: 64 }}>{shortDate(entry.day)}</Txt>
                    <Txt variant="small" color={colors.chaff} style={{ flex: 1 }}>{entry.label}</Txt>
                    <Txt variant="small" color={entry.state === 'OVERDUE' ? colors.rust : entry.state === 'DONE' ? colors.leaf : entry.state === 'RUNNING' ? colors.wheat : colors.straw}>
                      {entry.state === 'DONE' ? 'Done' : entry.state === 'RUNNING' ? 'Running' : entry.state === 'OVERDUE' ? 'Late' : 'Planned'}
                    </Txt>
                  </View>
                ))
              : null}
          </ScrollView>
        </View>
      </Modal>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.loam },
  content: { padding: space.lg, gap: space.md, paddingBottom: space.xxl },
  rowCenter: { flexDirection: 'row', alignItems: 'center', gap: space.sm },
  kicker: { fontFamily: fonts.semibold, letterSpacing: 0.8, fontSize: 11 },
  stat: { minWidth: 96, padding: space.md, borderRadius: 8, backgroundColor: colors.furrow, gap: 0, maxWidth: 160 },
  statActive: { borderWidth: 1, borderColor: colors.wheat },
  panel: { backgroundColor: colors.furrow, borderRadius: 8, padding: space.md, gap: space.xs },
  conflicts: { borderLeftWidth: 4, borderLeftColor: colors.rust },
  suggestion: { backgroundColor: colors.furrow, borderRadius: 8, padding: space.md, gap: 4, borderWidth: 1, borderStyle: 'dashed' },
  pill: { flexDirection: 'row', alignItems: 'center', gap: 4, minHeight: 34, paddingHorizontal: 12, borderRadius: 17, borderWidth: 1, borderColor: colors.line },
  pillPrimary: { backgroundColor: colors.wheat, borderColor: colors.wheat },
  nav: { width: 40, height: 40, alignItems: 'center', justifyContent: 'center' },
  dayRow: { backgroundColor: colors.furrow, borderRadius: 8, padding: space.sm, gap: 6, minHeight: 64 },
  today: { borderWidth: 1, borderColor: colors.wheat },
  dropTarget: { backgroundColor: colors.ridge, borderWidth: 1, borderColor: colors.leaf, borderStyle: 'dashed' },
  dayHead: { flexDirection: 'row', alignItems: 'center', gap: space.sm },
  weather: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  ghost: { borderWidth: 1, borderStyle: 'dashed', borderRadius: 6, paddingVertical: 4, paddingHorizontal: 8 },
  dot: { width: 10, height: 10, borderRadius: 5 },
  monthRow: { flexDirection: 'row' },
  monthCell: { padding: 3, gap: 2, borderWidth: StyleSheet.hairlineWidth, borderColor: colors.line, backgroundColor: colors.furrow },
  bar: { height: 4, borderRadius: 2 },
  barGhost: { borderWidth: 1, borderStyle: 'dashed', borderColor: colors.wheat, backgroundColor: 'transparent' },
  seasonRow: { flexDirection: 'row', alignItems: 'center', gap: space.sm, minHeight: 36 },
  track: { flex: 1, height: 22, borderRadius: 4, backgroundColor: colors.furrow, justifyContent: 'center' },
  todayLine: { position: 'absolute', top: 0, bottom: 0, width: 2, backgroundColor: colors.wheat },
  mark: { position: 'absolute', width: 8, height: 14, borderRadius: 3, marginLeft: -4 },
  markLate: { borderWidth: 2, borderColor: colors.rust },
  backdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.45)' },
  sheet: { maxHeight: '75%', backgroundColor: colors.furrow, borderTopLeftRadius: 16, borderTopRightRadius: 16, padding: space.lg },
});
