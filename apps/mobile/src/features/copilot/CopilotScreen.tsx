import { MaterialCommunityIcons } from '@expo/vector-icons';
import { CATEGORY_LABELS, farmInsights, type CopilotAnswer, type CopilotInsight, type TaskChange, type TaskProposal } from '@fermapp/domain';
import { useQuery } from '@tanstack/react-query';
import { useRef, useState, type ComponentRef } from 'react';
import { ActivityIndicator, KeyboardAvoidingView, Platform, Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { apiGet } from '../../api/client';
import type { Machine, TeamMember } from '../../api/types';
import { Field, IconButton, OfflineBanner, Txt, type IconName } from '../../components/ui';
import { buildFarmSnapshot, createTaskFromProposal } from '../../firebase/copilot';
import { shortDate } from '../../lib/format';
import { useParcels } from '../../lib/hooks';
import { colors, fonts, space } from '../../theme';
import { applyChange, askQuestion, createProposal, effectiveMode, undo } from './controller';
import { geminiConfigured } from './gemini';
import { AUTOMATION_OPTIONS, useCopilot, type ChatMessage, type ItemState } from './store';

const PRIORITY_COLOR = { LOW: colors.straw, MEDIUM: colors.water, HIGH: colors.amber, URGENT: colors.rust } as const;
const SEVERITY_COLOR = { INFO: colors.water, WARNING: colors.amber, CRITICAL: colors.rust } as const;
const INSIGHT_ICON: Record<CopilotInsight['kind'], IconName> = {
  OVERDUE: 'clock-alert-outline',
  UPCOMING: 'calendar-clock',
  WEATHER: 'weather-cloudy-alert',
  MACHINERY: 'tractor-variant',
  FERTILIZATION: 'sprout',
  HARVEST: 'barley',
  IRRIGATION: 'water-outline',
  PROFIT: 'cash-minus',
  DATA: 'database-alert-outline',
  SATELLITE: 'satellite-variant',
};

const FARM_QUESTIONS = ['Which field should I prioritize?', 'What operations are scheduled this week?', 'Which field has the highest ROI?', 'How many fields have overdue operations?'];
const FIELD_QUESTIONS = ['What should I plant here next year?', 'When should I fertilize?', 'Should I irrigate this week?', 'What yield can I expect?', 'When should I harvest?'];

/** Names for ids in suggestions: parcels, operators and machines. */
function useNames() {
  const parcels = useParcels();
  const team = useQuery({ queryKey: ['team'], queryFn: () => apiGet<TeamMember[]>('/team') });
  const machines = useQuery({ queryKey: ['machines'], queryFn: () => apiGet<Machine[]>('/machines') });
  return {
    parcel: (id: string | null) => (id ? parcels.data?.find((item) => item.id === id)?.name ?? null : null),
    member: (id: string | null) => (id ? team.data?.find((item) => item.userId === id)?.fullName ?? null : null),
    machine: (id: string | null) => (id ? machines.data?.find((item) => item.id === id)?.name ?? null : null),
  };
}
type Names = ReturnType<typeof useNames>;

function Section({ icon, title, items, color = colors.straw }: { icon: IconName; title: string; items: string[]; color?: string }) {
  if (!items.length) return null;
  return (
    <View style={styles.section}>
      <View style={styles.sectionHead}>
        <MaterialCommunityIcons name={icon} size={15} color={color} />
        <Txt variant="small" style={styles.sectionTitle} color={color}>{title}</Txt>
      </View>
      {items.map((item, index) => (
        <View key={index} style={styles.bullet}>
          <Txt variant="small" color={colors.straw}>•</Txt>
          <Txt variant="small" color={colors.chaff} style={{ flex: 1 }}>{item}</Txt>
        </View>
      ))}
    </View>
  );
}

function ActionButton({ label, icon, onPress, busy, tone = 'primary' }: { label: string; icon: IconName; onPress: () => void; busy?: boolean; tone?: 'primary' | 'quiet' }) {
  return (
    <Pressable accessibilityRole="button" disabled={busy} onPress={onPress} style={[styles.button, tone === 'quiet' && styles.buttonQuiet]}>
      {busy ? <ActivityIndicator size="small" color={tone === 'primary' ? colors.wheatInk : colors.wheat} /> : <MaterialCommunityIcons name={icon} size={16} color={tone === 'primary' ? colors.wheatInk : colors.wheat} />}
      <Txt variant="small" style={styles.buttonText} color={tone === 'primary' ? colors.wheatInk : colors.wheat}>{label}</Txt>
    </Pressable>
  );
}

function ItemStatus({ item, onUndo }: { item: ItemState | undefined; onUndo: () => void }) {
  if (!item) return null;
  if (item.state === 'failed') return <Txt variant="small" color={colors.rust}>{item.error}</Txt>;
  return (
    <View style={styles.status}>
      <MaterialCommunityIcons name={item.state === 'done' ? 'check-circle' : 'undo-variant'} size={16} color={item.state === 'done' ? colors.leaf : colors.straw} />
      <Txt variant="small" color={item.state === 'done' ? colors.leaf : colors.straw} style={{ flex: 1 }}>{item.state === 'done' ? 'Done' : 'Undone'}</Txt>
      {item.state === 'done' && item.actionId ? <Pressable accessibilityRole="button" onPress={onUndo} hitSlop={8}><Txt variant="small" color={colors.wheat}>Undo</Txt></Pressable> : null}
    </View>
  );
}

function ProposalCard({ proposal, message, names, nested }: { proposal: TaskProposal; message: ChatMessage; names: Names; nested?: boolean }) {
  const mode = effectiveMode();
  const item = message.items[proposal.key];
  const [busy, setBusy] = useState(false);
  const actions = useCopilot((state) => state.actions);
  const who = [names.member(proposal.assigneeId), names.machine(proposal.machineId)].filter(Boolean).join(' · ');
  const run = async (fn: () => Promise<unknown>) => {
    setBusy(true);
    await fn();
    setBusy(false);
  };
  const created = item?.state === 'done';
  const pendingFollowUps = proposal.followUps.filter((next) => message.items[next.key]?.state !== 'done');
  return (
    <View style={[styles.card, nested && styles.cardNested, { borderLeftColor: PRIORITY_COLOR[proposal.priority] }]}>
      <View style={styles.cardHead}>
        <MaterialCommunityIcons name="robot-outline" size={16} color={colors.wheat} />
        <Txt variant="small" color={colors.wheat} style={styles.cardKicker}>{nested ? 'Follow-up' : 'Suggested task'} · {CATEGORY_LABELS[proposal.category]}</Txt>
      </View>
      <Txt variant="subtitle">{proposal.title}</Txt>
      <Txt variant="small">
        {[names.parcel(proposal.parcelId), `Due ${shortDate(proposal.dueDate)}`, `window ${shortDate(proposal.window.start)}–${shortDate(proposal.window.end)}`, `${proposal.priority.toLowerCase()} priority`].filter(Boolean).join(' · ')}
      </Txt>
      {who ? <Txt variant="small">{who}</Txt> : null}
      <Txt variant="small" color={colors.chaff}>{proposal.reason}</Txt>
      {item ? (
        <ItemStatus item={item} onUndo={() => { const action = actions.find((entry) => entry.id === item.actionId); if (action) void undo(action); }} />
      ) : mode === 'RECOMMEND' ? (
        <Txt variant="small" color={colors.straw}>Recommendation only: add it from the To-Do tab, or change the automation in Settings.</Txt>
      ) : (
        <ActionButton label="Create task" icon="plus-circle-outline" busy={busy} onPress={() => void run(() => createProposal(message.id, proposal))} />
      )}
      {created && proposal.followUps.length ? (
        <View style={styles.followUps}>
          <Txt variant="small" color={colors.chaff}>
            {`After ${proposal.title.split(' – ')[0]!.toLowerCase()}, I recommend ${proposal.followUps.map((next) => next.title.split(' – ')[0]!.toLowerCase()).join(' and ')}.`}
          </Txt>
          {pendingFollowUps.length && mode !== 'RECOMMEND' ? (
            <ActionButton label="Create follow-up tasks" icon="playlist-plus" tone="quiet" busy={busy} onPress={() => void run(async () => { for (const next of pendingFollowUps) await createProposal(message.id, next); })} />
          ) : null}
          {proposal.followUps.map((next) => (message.items[next.key] ? <ProposalCard key={next.key} proposal={next} message={message} names={names} nested /> : null))}
        </View>
      ) : null}
    </View>
  );
}

function ChangeCard({ change, message, names }: { change: TaskChange; message: ChatMessage; names: Names }) {
  const mode = effectiveMode();
  const item = message.items[change.key];
  const [busy, setBusy] = useState(false);
  const actions = useCopilot((state) => state.actions);
  const what =
    change.kind === 'CANCEL'
      ? 'Cancel task'
      : change.kind === 'PRIORITY'
        ? `Priority ${change.fromPriority.toLowerCase()} → ${change.toPriority?.toLowerCase()}`
        : `Move ${shortDate(change.fromDate)} → ${shortDate(change.toDate!)}`;
  return (
    <View style={[styles.card, { borderLeftColor: change.kind === 'CANCEL' ? colors.rust : colors.water }]}>
      <View style={styles.cardHead}>
        <MaterialCommunityIcons name={change.kind === 'CANCEL' ? 'close-circle-outline' : change.kind === 'PRIORITY' ? 'flag-outline' : 'calendar-arrow-right'} size={16} color={colors.wheat} />
        <Txt variant="small" color={colors.wheat} style={styles.cardKicker}>{what}</Txt>
      </View>
      <Txt variant="subtitle">{change.title}</Txt>
      {names.parcel(change.parcelId) && !change.title.includes(names.parcel(change.parcelId)!) ? <Txt variant="small">{names.parcel(change.parcelId)}</Txt> : null}
      <Txt variant="small" color={colors.chaff}>{change.reason}</Txt>
      {item ? (
        <ItemStatus item={item} onUndo={() => { const action = actions.find((entry) => entry.id === item.actionId); if (action) void undo(action); }} />
      ) : mode === 'RECOMMEND' ? (
        <Txt variant="small" color={colors.straw}>Recommendation only: change it in the To-Do tab.</Txt>
      ) : (
        <ActionButton label="Apply" icon="check" busy={busy} onPress={() => { setBusy(true); void applyChange(message.id, change).finally(() => setBusy(false)); }} />
      )}
    </View>
  );
}

const CONFIDENCE = { HIGH: 'High confidence', MEDIUM: 'Medium confidence', LOW: 'Low confidence' } as const;

function AnswerView({ message, names }: { message: ChatMessage; names: Names }) {
  const answer = message.answer as CopilotAnswer;
  const actions = useCopilot((state) => state.actions);
  const autoApplied = Object.values(message.items).some((item) => item.state === 'done' && actions.some((action) => action.auto && action.id === item.actionId));
  return (
    <View style={styles.answer}>
      <Txt style={styles.direct}>{answer.direct}</Txt>
      <Section icon="lightbulb-on-outline" title="REASONING" items={answer.reasoning} />
      <Section icon="alert-outline" title="RISKS" items={answer.risks} color={colors.amber} />
      <Section icon="swap-horizontal" title="ALTERNATIVES" items={answer.alternatives} />
      <Section icon="cash" title="ECONOMIC IMPACT" items={answer.economics} color={colors.leaf} />
      <Section icon="arrow-right-circle-outline" title="NEXT STEPS" items={answer.nextSteps} color={colors.wheat} />
      <Section icon="database-alert-outline" title="MISSING DATA" items={answer.dataGaps} />
      {autoApplied ? <Txt variant="small" color={colors.leaf}>Applied automatically. Every action is in the AI activity log in Settings.</Txt> : null}
      {answer.proposals.map((proposal) => <ProposalCard key={proposal.key} proposal={proposal} message={message} names={names} />)}
      {answer.changes.map((change) => <ChangeCard key={change.key} change={change} message={message} names={names} />)}
      <Txt variant="small" style={styles.source}>
        {`${CONFIDENCE[answer.confidence]} · ${message.source === 'gemini' ? 'Worded by Gemini from your farm data' : 'Computed on your phone from your farm data'}`}
      </Txt>
      {message.note ? <Txt variant="small" color={colors.amber}>{message.note}</Txt> : null}
    </View>
  );
}

function Insights({ onAsk }: { onAsk: (question: string) => void }) {
  const insights = useQuery({ queryKey: ['copilot-insights'], queryFn: async () => farmInsights(await buildFarmSnapshot()), staleTime: 5 * 60_000 });
  const [created, setCreated] = useState<Record<string, boolean>>({});
  const mode = effectiveMode();
  if (insights.isPending) return <ActivityIndicator color={colors.wheat} style={{ alignSelf: 'flex-start' }} />;
  const items = insights.data ?? [];
  if (!items.length) return <Txt variant="small">Nothing needs your attention right now.</Txt>;
  return (
    <View style={{ gap: space.sm }}>
      <Txt variant="small" style={styles.sectionTitle}>COPILOT NOTICED</Txt>
      {items.slice(0, 6).map((insight) => (
        <View key={insight.key} style={[styles.insight, { borderLeftColor: SEVERITY_COLOR[insight.severity] }]}>
          <View style={styles.cardHead}>
            <MaterialCommunityIcons name={INSIGHT_ICON[insight.kind]} size={18} color={SEVERITY_COLOR[insight.severity]} />
            <Txt variant="small" color={colors.chaff} style={{ flex: 1 }}>{insight.message}</Txt>
          </View>
          <View style={styles.insightActions}>
            {insight.ask ? <ActionButton label="Ask" icon="chat-question-outline" tone="quiet" onPress={() => onAsk(insight.ask!)} /> : null}
            {insight.proposal && mode !== 'RECOMMEND' ? (
              created[insight.key] ? (
                <Txt variant="small" color={colors.leaf}>Task created</Txt>
              ) : (
                <ActionButton
                  label="Create task"
                  icon="plus-circle-outline"
                  tone="quiet"
                  onPress={() => {
                    void createTaskFromProposal(insight.proposal!, false).then((action) => {
                      useCopilot.getState().log(action);
                      setCreated((state) => ({ ...state, [insight.key]: true }));
                    });
                  }}
                />
              )
            ) : null}
          </View>
        </View>
      ))}
    </View>
  );
}

export function CopilotScreen() {
  const insets = useSafeAreaInsets();
  const scroll = useRef<ComponentRef<typeof ScrollView>>(null);
  const messages = useCopilot((state) => state.messages);
  const memory = useCopilot((state) => state.memory);
  const mode = useCopilot((state) => state.mode);
  const clear = useCopilot((state) => state.clear);
  const names = useNames();
  const [draft, setDraft] = useState('');
  const [thinking, setThinking] = useState(false);
  const focus = names.parcel(memory.parcelId);

  async function send(text: string) {
    const question = text.trim();
    if (!question || thinking) return;
    setDraft('');
    setThinking(true);
    await askQuestion(question);
    setThinking(false);
  }

  const suggestions = focus ? FIELD_QUESTIONS.map((question) => question.replace('here', `on ${focus}`)) : FARM_QUESTIONS;
  const modeLabel = AUTOMATION_OPTIONS.find((option) => option.value === effectiveMode())?.label ?? '';

  return (
    <KeyboardAvoidingView style={styles.screen} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <View style={{ paddingTop: insets.top }}>
        <OfflineBanner />
        <View style={styles.head}>
          <View style={{ flex: 1 }}>
            <Txt variant="display">Copilot</Txt>
            <Txt variant="small">
              {[focus ? `Talking about ${focus}` : 'Whole farm', modeLabel, geminiConfigured ? 'Gemini' : null].filter(Boolean).join(' · ')}
            </Txt>
          </View>
          {messages.length ? <IconButton icon="broom" label="Start a new conversation" onPress={clear} /> : null}
        </View>
      </View>

      <ScrollView ref={scroll} style={{ flex: 1 }} contentContainerStyle={styles.thread} keyboardShouldPersistTaps="handled" onContentSizeChange={() => scroll.current?.scrollToEnd({ animated: true })}>
        {messages.length === 0 ? (
          <View style={{ gap: space.lg }}>
            <Txt color={colors.straw}>Ask about any field, crop, task, machine, the weather or money. I read the farm’s data first, and I can turn advice into tasks{mode === 'AUTO' ? ' automatically' : ''}.</Txt>
            <Insights onAsk={(question) => void send(question)} />
          </View>
        ) : null}
        {messages.map((message) =>
          message.role === 'user' ? (
            <View key={message.id} style={[styles.bubble, styles.mine]}>
              <Txt color={colors.wheatInk}>{message.text}</Txt>
            </View>
          ) : (
            <View key={message.id} style={[styles.bubble, styles.theirs]}>
              {message.answer ? <AnswerView message={message} names={names} /> : <Txt>{message.text}</Txt>}
            </View>
          ),
        )}
        {thinking ? (
          <View style={styles.thinking}>
            <ActivityIndicator color={colors.wheat} />
            <Txt variant="small">Reading fields, tasks, machines, weather and costs…</Txt>
          </View>
        ) : null}
        <ScrollView horizontal showsHorizontalScrollIndicator={false} keyboardShouldPersistTaps="handled" contentContainerStyle={styles.chips}>
          {suggestions.map((suggestion) => (
            <Pressable key={suggestion} accessibilityRole="button" style={styles.chip} onPress={() => void send(suggestion)}>
              <Txt variant="small" color={colors.chaff}>{suggestion}</Txt>
            </Pressable>
          ))}
        </ScrollView>
      </ScrollView>

      <View style={styles.composer}>
        <Field style={{ flex: 1 }} label="Ask the copilot" value={draft} onChangeText={setDraft} multiline maxLength={2000} />
        <IconButton icon="send" label="Send question" color={colors.wheat} onPress={() => void send(draft)} />
      </View>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.loam },
  head: { flexDirection: 'row', alignItems: 'center', padding: space.lg, paddingBottom: space.sm, gap: space.sm },
  thread: { paddingHorizontal: space.lg, paddingBottom: space.lg, gap: space.md },
  bubble: { borderRadius: 10, maxWidth: '94%' },
  mine: { backgroundColor: colors.wheat, alignSelf: 'flex-end', padding: space.md },
  theirs: { backgroundColor: colors.furrow, alignSelf: 'stretch', padding: space.md },
  answer: { gap: space.md },
  direct: { fontFamily: fonts.semibold, fontSize: 16, lineHeight: 23, color: colors.chaff },
  section: { gap: 4 },
  sectionHead: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  sectionTitle: { fontFamily: fonts.semibold, fontSize: 11, letterSpacing: 0.8 },
  bullet: { flexDirection: 'row', gap: 6, paddingLeft: 4 },
  card: { backgroundColor: colors.ridge, borderRadius: 8, padding: space.md, gap: 4, borderLeftWidth: 4 },
  cardNested: { backgroundColor: colors.furrow },
  cardHead: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  cardKicker: { fontFamily: fonts.semibold, fontSize: 12 },
  button: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 6, alignSelf: 'flex-start', minHeight: 40, paddingHorizontal: 14, borderRadius: 20, backgroundColor: colors.wheat, marginTop: space.xs },
  buttonQuiet: { backgroundColor: 'transparent', borderWidth: 1, borderColor: colors.line },
  buttonText: { fontFamily: fonts.semibold, fontSize: 13 },
  status: { flexDirection: 'row', alignItems: 'center', gap: 6, marginTop: space.xs },
  followUps: { gap: space.sm, marginTop: space.sm, paddingTop: space.sm, borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: colors.line },
  source: { color: colors.straw, fontSize: 11 },
  insight: { backgroundColor: colors.furrow, borderRadius: 8, padding: space.md, gap: space.sm, borderLeftWidth: 4 },
  insightActions: { flexDirection: 'row', alignItems: 'center', gap: space.sm, flexWrap: 'wrap' },
  thinking: { flexDirection: 'row', alignItems: 'center', gap: space.sm },
  chips: { gap: space.sm, paddingVertical: space.xs },
  chip: { minHeight: 36, justifyContent: 'center', paddingHorizontal: 12, borderRadius: 18, borderWidth: 1, borderColor: colors.line },
  composer: { flexDirection: 'row', alignItems: 'center', gap: space.xs, padding: space.sm, backgroundColor: colors.furrow },
});
