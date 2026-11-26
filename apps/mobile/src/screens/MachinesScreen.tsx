import { FIELD_STATE_COLORS, fuelLitersPerHour, machineTillageSystems, type TillageSystem } from '@fermapp/domain';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { View } from 'react-native';
import { Button } from 'react-native-paper';
import { apiGet, apiSend } from '../api/client';
import type { Machine, TeamMember, WorkSession } from '../api/types';
import { Choice, Empty, Field, IconButton, Panel, QueryState, Screen, Section, Txt } from '../components/ui';
import type { CatalogTractor } from '../features/catalog/api';
import { TractorWizard } from '../features/catalog/TractorWizard';
import { isRunActive } from '../features/operations/finish';
import { TillageChips } from '../features/tillage';
import { usesFirebase } from '../firebase/config';
import { isPositive, MACHINE_TYPES, parseNumber } from '../lib/format';
import { useNavigation, type NavigationProp } from '@react-navigation/native';
import { useAction } from '../lib/hooks';
import type { RootStackParams } from '../navigation/RootNavigator';
import { canManage, useAuth } from '../store/auth';
import { colors, space } from '../theme';

const typeOptions = Object.entries(MACHINE_TYPES).map(([value, label]) => ({ value, label }));
const BRANDS = ['John Deere', 'Fendt', 'Case IH', 'New Holland', 'Claas'];

const toggled = (list: readonly TillageSystem[], system: TillageSystem) =>
  list.includes(system) ? list.filter((item) => item !== system) : [...list, system];

/** The technologies a machine suits, edited in place. Its type decides them until the user changes them. */
function MachineTillage({ machine, editable }: { machine: Machine; editable: boolean }) {
  const current = machineTillageSystems(machine);
  const save = useAction<TillageSystem[]>(async (tillageSystems) => {
    await apiSend('PATCH', `/machines/${machine.id}`, { tillageSystems });
  }, [['machines']]);
  return (
    <View style={{ gap: 4 }}>
      <Txt variant="small">{machine.tillageSystems?.length ? 'Suitable for' : 'Suitable for (default for its type)'}</Txt>
      <TillageChips
        value={current}
        disabled={!editable || save.isPending}
        onToggle={(system) => {
          const next = toggled(current, system);
          if (next.length) save.mutate(next);
        }}
      />
    </View>
  );
}

export function MachinesScreen() {
  const role = useAuth((state) => state.role);
  const navigation = useNavigation<NavigationProp<RootStackParams>>();
  const machines = useQuery({ queryKey: ['machines'], queryFn: () => apiGet<Machine[]>('/machines') });
  const team = useQuery({ queryKey: ['team'], queryFn: () => apiGet<TeamMember[]>('/team') });
  const work = useQuery({ queryKey: ['work'], queryFn: () => apiGet<WorkSession[]>('/work') });
  // A machine is busy only while its run is active; at 100% it is free for the next job.
  const busy = new Map((work.data ?? []).filter((run) => run.machine && isRunActive(run)).map((run) => [run.machine!.id, run.parcel.name]));
  const [name, setName] = useState('');
  const [type, setType] = useState<string | null>(null);
  const [brand, setBrand] = useState('');
  const [model, setModel] = useState('');
  const [power, setPower] = useState('');
  const [consumption, setConsumption] = useState('');
  const [operatorId, setOperatorId] = useState<string | null>(null);
  const [catalogTractor, setCatalogTractor] = useState<CatalogTractor | null>(null);
  const [tillage, setTillage] = useState<TillageSystem[] | null>(null);
  const tillageValue = tillage ?? machineTillageSystems({ type: type ?? 'TRACTOR' });

  function applyCatalog(tractor: CatalogTractor) {
    setCatalogTractor(tractor);
    setName(`${tractor.brand} ${tractor.model}`);
    setType('TRACTOR');
    setBrand(tractor.brand);
    setModel(tractor.model);
    setPower(String(tractor.horsePower));
    setConsumption(String(fuelLitersPerHour(tractor.fuelConsumptionMin, tractor.fuelConsumptionMax)));
  }

  const optionalValid = (!power || isPositive(power)) && (!consumption || isPositive(consumption));

  const add = useAction<void>(
    async () => {
      await apiSend('POST', '/machines', {
        name: name.trim(),
        type,
        brand: brand.trim() || undefined,
        model: model.trim() || undefined,
        powerHp: power ? parseNumber(power) : undefined,
        avgConsumptionLh: consumption ? parseNumber(consumption) : undefined,
        operatorId: operatorId ?? undefined,
        tillageSystems: tillage ?? undefined,
        ...(catalogTractor
          ? {
              catalogId: catalogTractor.id,
              fuelConsumptionMin: catalogTractor.fuelConsumptionMin,
              fuelConsumptionMax: catalogTractor.fuelConsumptionMax,
              tankCapacity: catalogTractor.tankCapacity,
              weight: catalogTractor.weight,
            }
          : {}),
      });
    },
    [['machines']],
    () => {
      setCatalogTractor(null);
      setName('');
      setType(null);
      setBrand('');
      setModel('');
      setPower('');
      setConsumption('');
      setOperatorId(null);
      setTillage(null);
    },
  );

  const remove = useAction<string>(async (id) => {
    await apiSend('DELETE', `/machines/${id}`);
  }, [['machines']]);

  return (
    <Screen onRefresh={() => void machines.refetch()} refreshing={machines.isRefetching}>
      {canManage(role) ? (
        <Button mode="outlined" icon="calculator-variant-outline" onPress={() => navigation.navigate('EconomicSettings')}>
          Ownership, service and labour costs
        </Button>
      ) : null}
      <QueryState query={machines}>
        {(items) =>
          items.length === 0 ? (
            <Empty icon="tractor" title="No machines in the fleet" hint="Add the tractors and equipment used for field work." />
          ) : (
            <>
              {(['working', 'available'] as const).map((group) => {
                const members = items.filter((machine) => (group === 'working') === busy.has(machine.id));
                return members.length === 0 ? null : (
                  <Section key={group} title={group === 'working' ? `In work (${members.length})` : `Available (${members.length})`}>
              {members.map((machine) => (
                <Panel key={machine.id} style={{ flexDirection: 'row', alignItems: 'center' }}>
                  <View style={{ flex: 1, gap: space.sm }}>
                    <View style={{ flexDirection: 'row', alignItems: 'center', gap: space.sm }}>
                      <Txt variant="subtitle" style={{ flex: 1 }}>{machine.name}</Txt>
                      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 4 }}>
                        <View style={{ width: 8, height: 8, borderRadius: 4, backgroundColor: busy.has(machine.id) ? FIELD_STATE_COLORS.ACTIVE : colors.leaf }} />
                        <Txt variant="small" color={colors.chaff}>{busy.has(machine.id) ? `Working, ${busy.get(machine.id)}` : 'Available'}</Txt>
                      </View>
                    </View>
                    <Txt variant="small">
                      {[
                        MACHINE_TYPES[machine.type],
                        [machine.brand, machine.model].filter(Boolean).join(' '),
                        machine.powerHp ? `${machine.powerHp} HP` : null,
                        machine.avgConsumptionLh ? `${machine.avgConsumptionLh} l/hour` : null,
                        machine.operator?.fullName,
                      ].filter(Boolean).join(', ')}
                    </Txt>
                    <MachineTillage machine={machine} editable={canManage(role)} />
                  </View>
                  {canManage(role) ? (
                    <IconButton icon="archive-outline" label={`Archive ${machine.name}`} color={colors.straw} onPress={() => remove.mutate(machine.id)} />
                  ) : null}
                </Panel>
              ))}
                  </Section>
                );
              })}
            </>
          )
        }
      </QueryState>

      {canManage(role) ? (
        <Section title="Add a machine">
          {usesFirebase ? <TractorWizard onSelect={applyCatalog} /> : null}
          <Panel>
            <Field label="Name" value={name} onChangeText={setName} />
            <Choice label="Type" options={typeOptions} value={type} onChange={setType} />
            <Choice label="Brand" options={BRANDS.map((value) => ({ value, label: value }))} value={brand} onChange={setBrand} />
            <Field label="Other brand" value={brand} onChangeText={setBrand} />
            <Field label="Model" value={model} onChangeText={setModel} />
            <View style={{ flexDirection: 'row', gap: space.sm }}>
              <Field style={{ flex: 1 }} label="Power (HP)" value={power} onChangeText={setPower} keyboardType="decimal-pad" />
              <Field style={{ flex: 1 }} label="Consumption (l/hour)" value={consumption} onChangeText={setConsumption} keyboardType="decimal-pad" />
            </View>
            <View style={{ gap: 4 }}>
              <Txt variant="small">{tillage ? 'Suitable tillage technologies' : 'Suitable tillage technologies (default for the type)'}</Txt>
              <TillageChips
                value={tillageValue}
                onToggle={(system) => {
                  const next = toggled(tillageValue, system);
                  if (next.length) setTillage(next);
                }}
              />
            </View>
            <Choice
              label="Operator"
              options={(team.data ?? []).map((member) => ({ value: member.userId, label: member.fullName }))}
              value={operatorId}
              onChange={setOperatorId}
            />
            <Button mode="contained" disabled={!name.trim() || !type || !optionalValid || add.isPending} loading={add.isPending} onPress={() => add.mutate()}>
              Save machine
            </Button>
          </Panel>
        </Section>
      ) : null}
    </Screen>
  );
}
