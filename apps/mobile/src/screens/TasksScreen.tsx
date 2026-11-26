import { checkTaskCompatibility } from '@fermapp/domain';
import { useRoute } from '@react-navigation/native';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';
import { Button } from 'react-native-paper';
import { apiGet, apiSend } from '../api/client';
import type { Task, TaskPriority, TaskStatus, TeamMember } from '../api/types';
import { ParcelPicker } from '../components/pickers';
import { CalendarList } from '../features/plan/PlanViews';
import { CompatibilityNotice, useParcelById } from '../features/tillage';
import { Choice, Empty, Field, Panel, QueryState, Screen, Section, Txt } from '../components/ui';
import { parseRoDate, PRIORITIES, shortDate, TASK_TYPES, toRoDate } from '../lib/format';
import { useAction } from '../lib/hooks';
import { canPlan, useAuth } from '../store/auth';
import { colors, space } from '../theme';

type TaskView = TaskStatus | 'CALENDAR';

const STATUS: { value: TaskView; label: string }[] = [
  { value: 'TODO', label: 'To Do' },
  { value: 'IN_PROGRESS', label: 'In Progress' },
  { value: 'DONE', label: 'Done' },
  { value: 'CALENDAR', label: 'Calendar' },
];
const NEXT: Record<TaskStatus, { status: TaskStatus; label: string }> = {
  TODO: { status: 'IN_PROGRESS', label: 'Start' },
  IN_PROGRESS: { status: 'DONE', label: 'Complete' },
  DONE: { status: 'TODO', label: 'Reopen' },
};
const PRIORITY_COLOR: Record<TaskPriority, string> = { LOW: colors.straw, MEDIUM: colors.water, HIGH: colors.amber, URGENT: colors.rust };

export function TasksScreen() {
  const { role, user } = useAuth();
  const tasks = useQuery({ queryKey: ['tasks'], queryFn: () => apiGet<Task[]>('/tasks') });
  const team = useQuery({ queryKey: ['team'], queryFn: () => apiGet<TeamMember[]>('/team') });
  const [filter, setFilter] = useState<TaskView>('TODO');
  const [type, setType] = useState<string | null>(null);
  const [priority, setPriority] = useState<TaskPriority>('MEDIUM');
  const [due, setDue] = useState(toRoDate(new Date(Date.now() + 86_400_000)));
  const [parcelId, setParcelId] = useState<string | null>(null);
  const preselected = (useRoute().params as { parcelId?: string } | undefined)?.parcelId;
  useEffect(() => {
    if (preselected) setParcelId(preselected);
  }, [preselected]);
  const [assigneeId, setAssigneeId] = useState<string | null>(null);
  const taskParcel = useParcelById(parcelId);

  const add = useAction<void>(
    async () => {
      await apiSend('POST', '/tasks', { type, priority, dueDate: parseRoDate(due), parcelId: parcelId ?? undefined, assigneeId: assigneeId ?? undefined });
    },
    [['tasks'], ['dashboard']],
    () => {
      setType(null);
      setParcelId(null);
      setAssigneeId(null);
    },
  );

  const move = useAction<{ id: string; status: TaskStatus }>(
    async ({ id, status }) => {
      await apiSend('POST', `/tasks/${id}/status`, { status });
    },
    [['tasks'], ['dashboard']],
  );

  const visible = (tasks.data ?? []).filter((task) => task.status === filter);
  const mayMove = (task: Task) => role !== 'OPERATOR' || task.assignee?.id === user?.id;

  return (
    <Screen title="To Do" onRefresh={() => void tasks.refetch()} refreshing={tasks.isRefetching}>
      <Choice options={STATUS} value={filter} onChange={setFilter} />
      <QueryState query={tasks}>
        {(items) =>
          filter === 'CALENDAR' ? (
            items.length === 0 ? (
              <Empty icon="calendar-blank-outline" title="No scheduled activities" />
            ) : (
              <Panel>
                <CalendarList
                  entries={items.map((task) => ({
                    id: task.id,
                    date: task.dueDate,
                    title: task.title,
                    subtitle: task.parcel?.name,
                    done: task.status === 'DONE',
                  }))}
                />
              </Panel>
            )
          ) : visible.length === 0 ? (
            <Empty icon="checkbox-marked-circle-outline" title="Nothing in this column" />
          ) : (
            <>
              {visible.map((task) => {
                const overdue = task.status !== 'DONE' && new Date(task.dueDate) < new Date();
                return (
                  <Panel key={task.id} style={{ borderLeftWidth: 4, borderLeftColor: PRIORITY_COLOR[task.priority] }}>
                    <View style={{ flexDirection: 'row', gap: space.md }}>
                      <View style={{ flex: 1, gap: 2 }}>
                        {task.source === 'AI' ? (
                          <View style={styles.aiBadge} accessibilityLabel="AI generated task">
                            <Txt variant="small" color={colors.wheat} style={styles.aiBadgeText}>🤖 AI Generated</Txt>
                          </View>
                        ) : null}
                        <Txt variant="subtitle">{task.title}</Txt>
                        <Txt variant="small">
                          {[task.parcel?.name, task.assignee?.fullName, task.machine?.name, `${PRIORITIES[task.priority]} priority`].filter(Boolean).join(', ')}
                        </Txt>
                        <Txt variant="small" color={overdue ? colors.rust : undefined}>
                          {`${overdue ? 'Overdue' : 'Due'}: ${shortDate(task.dueDate)}`}
                          {task.windowStart && task.windowEnd ? `  ·  window ${shortDate(task.windowStart)}–${shortDate(task.windowEnd)}` : ''}
                        </Txt>
                        {task.aiReason ? <Txt variant="small" color={colors.chaff}>{task.aiReason}</Txt> : null}
                      </View>
                      {mayMove(task) ? (
                        <Pressable
                          accessibilityRole="button"
                          onPress={() => move.mutate({ id: task.id, status: NEXT[task.status].status })}
                          style={{ minHeight: 44, justifyContent: 'center', paddingHorizontal: space.sm }}
                        >
                          <Txt color={colors.wheat}>{NEXT[task.status].label}</Txt>
                        </Pressable>
                      ) : null}
                    </View>
                  </Panel>
                );
              })}
            </>
          )
        }
      </QueryState>

      {canPlan(role) ? (
        <Section title="Add a task">
          <Panel>
            <Choice label="Task" options={Object.entries(TASK_TYPES).map(([value, label]) => ({ value, label }))} value={type} onChange={setType} />
            <Choice label="Priority" options={(Object.keys(PRIORITIES) as TaskPriority[]).map((value) => ({ value, label: PRIORITIES[value]! }))} value={priority} onChange={setPriority} />
            <Field label="Deadline (DD.MM.YYYY)" value={due} onChangeText={setDue} />
            <ParcelPicker value={parcelId} onChange={setParcelId} />
            <CompatibilityNotice check={checkTaskCompatibility(taskParcel?.tillageSystem, type)} />
            <Choice label="Assignee" options={(team.data ?? []).map((member) => ({ value: member.userId, label: member.fullName }))} value={assigneeId} onChange={setAssigneeId} />
            <Button mode="contained" disabled={!type || !parseRoDate(due) || add.isPending} loading={add.isPending} onPress={() => add.mutate()}>
              Save task
            </Button>
          </Panel>
        </Section>
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  aiBadge: { alignSelf: 'flex-start', paddingHorizontal: 8, paddingVertical: 1, borderRadius: 10, borderWidth: 1, borderColor: colors.wheat },
  aiBadgeText: { fontSize: 11, lineHeight: 16 },
});
