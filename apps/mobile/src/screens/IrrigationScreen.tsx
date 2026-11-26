import { estimateIrrigation } from '@fermapp/domain';
import { useRoute } from '@react-navigation/native';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { View } from 'react-native';
import { Button } from 'react-native-paper';
import { apiGet, apiSend } from '../api/client';
import type { IrrigationRun } from '../api/types';
import { ParcelPicker } from '../components/pickers';
import { RunCard } from '../components/RunCard';
import { Choice, Empty, Field, Panel, ProgressTrack, QueryState, Row, Screen, Section, Stat, Txt } from '../components/ui';
import { clock, hoursMinutes, IRRIGATION_TYPES, isPositive, number, parseNumber, shortDate } from '../lib/format';
import { useAction, useLiveProgress } from '../lib/hooks';
import { canOperate, useAuth } from '../store/auth';
import { colors, space } from '../theme';

const systems = Object.entries(IRRIGATION_TYPES).map(([value, label]) => ({ value, label }));

/** The irrigation system chosen on the map. */
function FocusedRun({ run, label }: { run: IrrigationRun; label: string }) {
  const progress = useLiveProgress(run);
  const paused = run.status === 'PAUSED';
  return (
    <Panel style={{ borderWidth: 1, borderColor: colors.water }}>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: space.md }}>
        <View style={{ flex: 1 }}>
          <Txt variant="subtitle">{label}</Txt>
          <Txt variant="small">{run.parcel.name}</Txt>
        </View>
        <Txt variant="figure" color={colors.water}>{`${Math.floor(progress.percent)}%`}</Txt>
      </View>
      <ProgressTrack percent={progress.percent} color={paused ? colors.straw : colors.water} />
      <Row label="Equipment type" value={IRRIGATION_TYPES[run.systemType]} />
      <Row label="Coverage" value={`${Math.floor(progress.percent)}%`} />
      <Row label="Travel speed" value={`${number(run.speedMPerH, 0)} m/hour`} />
      <Row label="Time left" value={hoursMinutes(progress.remainingSeconds)} />
      <Row label="Completion ETA" value={paused ? 'Paused' : progress.eta ? clock(progress.eta) : '-'} />
    </Panel>
  );
}

export function IrrigationScreen() {
  const role = useAuth((state) => state.role);
  const runs = useQuery({ queryKey: ['irrigation'], queryFn: () => apiGet<IrrigationRun[]>('/irrigation') });
  const [parcelId, setParcelId] = useState<string | null>(null);
  const routeParams = useRoute().params as { parcelId?: string; runId?: string; label?: string } | undefined;
  const preselected = routeParams?.parcelId;
  useEffect(() => {
    if (preselected) setParcelId(preselected);
  }, [preselected]);
  const [system, setSystem] = useState<string | null>(null);
  const [length, setLength] = useState('');
  const [speed, setSpeed] = useState('');
  const [dose, setDose] = useState('');

  const valid = isPositive(length) && isPositive(speed) && (!dose || isPositive(dose));
  const estimate = useMemo(
    () => (isPositive(length) && isPositive(speed) ? estimateIrrigation(parseNumber(length), parseNumber(speed)) : null),
    [length, speed],
  );

  const start = useAction<void>(
    async () => {
      await apiSend('POST', '/irrigation', {
        parcelId,
        systemType: system,
        routeLengthM: parseNumber(length),
        speedMPerH: parseNumber(speed),
        doseMm: dose ? parseNumber(dose) : undefined,
      });
    },
    [['irrigation'], ['dashboard']],
    () => {
      setParcelId(null);
      setLength('');
      setSpeed('');
      setDose('');
    },
  );

  const control = useAction<{ id: string; action: string }>(
    async ({ id, action }) => {
      await apiSend('POST', `/irrigation/${id}/${action}`);
    },
    [['irrigation'], ['dashboard']],
  );

  const items = runs.data ?? [];
  const active = items.filter((run) => run.status === 'RUNNING' || run.status === 'PAUSED');
  const history = items.filter((run) => run.status === 'COMPLETED' || run.status === 'CANCELLED').slice(0, 15);
  const focused = active.find((run) => run.id === routeParams?.runId);

  return (
    <Screen title="Irrigation" onRefresh={() => void runs.refetch()} refreshing={runs.isRefetching}>
      {focused ? <FocusedRun run={focused} label={routeParams?.label ?? IRRIGATION_TYPES[focused.systemType]!} /> : null}
      <QueryState query={runs}>
        {() =>
          active.length > 0 ? (
            <Section title="Active irrigations">
              {active.map((run) => (
                <RunCard
                  key={run.id}
                  run={run}
                  color={colors.water}
                  title={run.parcel.name}
                  subtitle={`${IRRIGATION_TYPES[run.systemType]}, ${number(run.routeLengthM, 0)} m at ${number(run.speedMPerH, 0)} m/hour`}
                  canControl={canOperate(role)}
                  busy={control.isPending}
                  onAction={(action) => control.mutate({ id: run.id, action })}
                />
              ))}
            </Section>
          ) : null
        }
      </QueryState>

      {canOperate(role) ? (
        <Section title="Start an irrigation">
          <Panel>
            <ParcelPicker value={parcelId} onChange={setParcelId} />
            <Choice label="System" options={systems} value={system} onChange={setSystem} />
            <View style={{ flexDirection: 'row', gap: space.sm }}>
              <Field style={{ flex: 1 }} label="Route (m)" value={length} onChangeText={setLength} keyboardType="decimal-pad" />
              <Field style={{ flex: 1 }} label="Speed (m/hour)" value={speed} onChangeText={setSpeed} keyboardType="decimal-pad" />
            </View>
            <Field label="Applied dose (mm, optional)" value={dose} onChangeText={setDose} keyboardType="decimal-pad" />
            {estimate ? (
              <View style={{ flexDirection: 'row' }}>
                <Stat value={number(estimate.durationHours)} unit="hours" label="Total duration" color={colors.water} />
                <Stat value={clock(estimate.eta)} label={`ETA, ${shortDate(estimate.eta)}`} />
              </View>
            ) : null}
            <Button mode="contained" disabled={!parcelId || !system || !valid || start.isPending} loading={start.isPending} onPress={() => start.mutate()}>
              Start irrigation
            </Button>
          </Panel>
        </Section>
      ) : null}

      <Section title="History">
        {history.length === 0 ? (
          <Empty icon="water-off-outline" title="No completed irrigations" />
        ) : (
          <Panel>
            {history.map((run) => (
              <Row
                key={run.id}
                label={`${shortDate(run.startedAt)}, ${run.parcel.name}`}
                value={run.status === 'CANCELLED' ? 'Cancelled' : `${Math.round(run.progressPct)}%`}
              />
            ))}
          </Panel>
        )}
      </Section>
    </Screen>
  );
}
