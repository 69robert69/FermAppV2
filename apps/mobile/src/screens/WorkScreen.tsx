import { checkMachineCompatibility, checkWorkCompatibility, estimateWork, TILLAGE_SYSTEMS } from '@fermapp/domain';
import { useNavigation, useRoute, type NavigationProp } from '@react-navigation/native';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { View } from 'react-native';
import { Button } from 'react-native-paper';
import { apiGet, apiSend, isQueued } from '../api/client';
import type { Machine, WorkSession } from '../api/types';
import { ParcelPicker } from '../components/pickers';
import { RunCard } from '../components/RunCard';
import { Choice, Empty, Field, IconButton, Panel, QueryState, Row, Screen, Section, Stat, Txt } from '../components/ui';
import { machineFuelLPerH, WorkCostPanel } from '../features/catalog/WorkCostPanel';
import { isRunActive } from '../features/operations/finish';
import { CompatibilityNotice, TillageBadge } from '../features/tillage';
import { usesFirebase } from '../firebase/config';
import { clock, isPositive, number, parseNumber, shortDate, WORK_TYPES } from '../lib/format';
import { useAction, useParcels } from '../lib/hooks';
import type { RootStackParams } from '../navigation/RootNavigator';
import { canOperate, useAuth } from '../store/auth';
import { useCatalogPrefs } from '../store/catalogPrefs';
import { colors, space } from '../theme';

const typeOptions = Object.entries(WORK_TYPES).map(([value, label]) => ({ value, label }));

export function WorkScreen() {
  const navigation = useNavigation<NavigationProp<RootStackParams>>();
  const { role, user } = useAuth();
  const work = useQuery({ queryKey: ['work'], queryFn: () => apiGet<WorkSession[]>('/work') });
  const machines = useQuery({ queryKey: ['machines'], queryFn: () => apiGet<Machine[]>('/machines') });
  const parcels = useParcels();

  const [parcelId, setParcelId] = useState<string | null>(null);
  // The map action wheel can preselect the parcel, the machine and the operation.
  const routeParams = useRoute().params as { parcelId?: string; machineId?: string; type?: string } | undefined;
  const preselected = routeParams?.parcelId;
  useEffect(() => {
    if (preselected) setParcelId(preselected);
  }, [preselected]);
  const [machineId, setMachineId] = useState<string | null>(null);
  const [type, setType] = useState<string | null>(null);
  useEffect(() => {
    if (routeParams?.machineId) setMachineId(routeParams.machineId);
    if (routeParams?.type) setType(routeParams.type);
  }, [routeParams?.machineId, routeParams?.type]);
  const [width, setWidth] = useState('');
  const [speed, setSpeed] = useState('');
  const [efficiency, setEfficiency] = useState('80');
  const [queuedNote, setQueuedNote] = useState(false);

  const parcel = parcels.data?.find((item) => item.id === parcelId);
  const selectedMachine = machines.data?.find((item) => item.id === machineId);
  const validEfficiency = isPositive(efficiency) && parseNumber(efficiency) <= 100;
  const inputsValid = isPositive(width) && isPositive(speed) && validEfficiency;
  const estimate = useMemo(
    () =>
      parcel && inputsValid
        ? estimateWork(parcel.areaHa, {
            workingWidthM: parseNumber(width),
            speedKmh: parseNumber(speed),
            efficiencyPct: parseNumber(efficiency),
          })
        : null,
    [parcel, inputsValid, width, speed, efficiency],
  );

  const start = useAction<void, unknown>(
    () =>
      apiSend('POST', '/work', {
        parcelId,
        machineId: machineId ?? undefined,
        type,
        workingWidthM: parseNumber(width),
        speedKmh: parseNumber(speed),
        efficiencyPct: parseNumber(efficiency),
        fuelLPerH: machineFuelLPerH(selectedMachine, type),
        fuelPricePerL: useCatalogPrefs.getState().fuelPricePerL,
      }),
    [['work'], ['dashboard']],
    (result) => {
      setQueuedNote(isQueued(result));
      setParcelId(null);
      setType(null);
    },
  );

  const control = useAction<{ id: string; action: string }>(
    async ({ id, action }) => {
      await apiSend('POST', `/work/${id}/${action}`);
    },
    [['work'], ['dashboard']],
  );

  const items = work.data ?? [];
  // A run that reached 100% is finished even before the server closes it.
  const active = items.filter((item) => isRunActive(item));
  const history = items.filter((item) => item.status === 'COMPLETED' || item.status === 'CANCELLED').slice(0, 15);
  const mayControl = (item: WorkSession) => canOperate(role) && (role !== 'OPERATOR' || item.operator.id === user?.id);

  return (
    <Screen
      title="Work"
      action={
        <View style={{ flexDirection: 'row' }}>
          {usesFirebase ? <IconButton icon="book-open-variant" label="Farm catalog" onPress={() => navigation.navigate('Catalog')} /> : null}
          <IconButton icon="tractor" label="Machines" onPress={() => navigation.navigate('Machines')} />
        </View>
      }
      onRefresh={() => void work.refetch()}
      refreshing={work.isRefetching}
    >
      <QueryState query={work}>
        {() =>
          active.length > 0 ? (
            <Section title="Active operations">
              {active.map((item) => (
                <RunCard
                  key={item.id}
                  run={item}
                  title={`${WORK_TYPES[item.type]}, ${item.parcel.name}`}
                  subtitle={`${item.machine?.name ?? 'No machine'}, ${item.capacityHaH} ha/hour`}
                  canControl={mayControl(item)}
                  busy={control.isPending}
                  onAction={(action) => control.mutate({ id: item.id, action })}
                />
              ))}
            </Section>
          ) : null
        }
      </QueryState>

      {canOperate(role) ? (
        <Section title="Start an operation">
          <Panel>
            <ParcelPicker value={parcelId} onChange={setParcelId} />
            {parcel?.tillageSystem ? (
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: space.sm }}>
                <TillageBadge system={parcel.tillageSystem} size="sm" />
                <Txt variant="small" style={{ flex: 1 }}>
                  {`Recommended: ${TILLAGE_SYSTEMS[parcel.tillageSystem].operations.map((operation) => operation.label).join(', ')}`}
                </Txt>
              </View>
            ) : null}
            <Choice label="Operation" options={typeOptions} value={type} onChange={setType} />
            <CompatibilityNotice check={checkWorkCompatibility(parcel?.tillageSystem, type)} />
            <Choice
              label="Machine (optional)"
              options={(machines.data ?? []).map((machine) => ({ value: machine.id, label: machine.name }))}
              value={machineId}
              onChange={setMachineId}
            />
            <CompatibilityNotice check={checkMachineCompatibility(selectedMachine, parcel?.tillageSystem)} />
            <View style={{ flexDirection: 'row', gap: space.sm }}>
              <Field style={{ flex: 1 }} label="Width (m)" value={width} onChangeText={setWidth} keyboardType="decimal-pad" />
              <Field style={{ flex: 1 }} label="Speed (km/h)" value={speed} onChangeText={setSpeed} keyboardType="decimal-pad" />
              <Field style={{ flex: 1 }} label="Efficiency (%)" value={efficiency} onChangeText={setEfficiency} keyboardType="decimal-pad" />
            </View>
            {estimate ? (
              <View style={{ flexDirection: 'row' }}>
                <Stat value={number(estimate.capacityHaH, 2)} unit="ha/hour" label="Capacity" color={colors.wheat} />
                <Stat value={number(estimate.durationHours)} unit="hours" label="Duration" />
                <Stat value={clock(estimate.eta)} label={`ETA, ${shortDate(estimate.eta)}`} />
              </View>
            ) : null}
            {usesFirebase ? (
              <WorkCostPanel
                parcelId={parcelId}
                areaHa={parcel?.areaHa ?? null}
                operation={type}
                machine={selectedMachine}
                durationHours={estimate?.durationHours ?? null}
                onApply={(best) => {
                  if (best.owned) setMachineId(best.tractorId);
                  setWidth(String(best.workingWidthM));
                  setSpeed(String(best.speedKmh));
                  setEfficiency(String(best.efficiencyPct));
                }}
              />
            ) : null}
            <Button mode="contained" disabled={!parcelId || !type || !inputsValid || start.isPending} loading={start.isPending} onPress={() => start.mutate()}>
              Start operation
            </Button>
            {queuedNote ? <Txt variant="small" color={colors.wheat}>The operation starts on the server when the connection returns.</Txt> : null}
          </Panel>
        </Section>
      ) : null}

      <Section title="History">
        {history.length === 0 ? (
          <Empty icon="history" title="No completed operations" />
        ) : (
          <Panel>
            {history.map((item) => (
              <Row
                key={item.id}
                label={`${shortDate(item.startedAt)}, ${WORK_TYPES[item.type]}, ${item.parcel.name}`}
                value={item.status === 'CANCELLED' ? 'Cancelled' : `${Math.round(item.progressPct)}%`}
              />
            ))}
          </Panel>
        )}
      </Section>
    </Screen>
  );
}
