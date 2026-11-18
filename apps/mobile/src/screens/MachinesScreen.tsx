import { fuelLitersPerHour } from '@fermapp/domain';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { View } from 'react-native';
import { Button } from 'react-native-paper';
import { apiGet, apiSend } from '../api/client';
import type { Machine, TeamMember } from '../api/types';
import { Choice, Empty, Field, IconButton, Panel, QueryState, Screen, Section, Txt } from '../components/ui';
import type { CatalogTractor } from '../features/catalog/api';
import { TractorWizard } from '../features/catalog/TractorWizard';
import { usesFirebase } from '../firebase/config';
import { isPositive, MACHINE_TYPES, parseNumber } from '../lib/format';
import { useAction } from '../lib/hooks';
import { canManage, useAuth } from '../store/auth';
import { colors, space } from '../theme';

const typeOptions = Object.entries(MACHINE_TYPES).map(([value, label]) => ({ value, label }));
const BRANDS = ['John Deere', 'Fendt', 'Case IH', 'New Holland', 'Claas'];

export function MachinesScreen() {
  const role = useAuth((state) => state.role);
  const machines = useQuery({ queryKey: ['machines'], queryFn: () => apiGet<Machine[]>('/machines') });
  const team = useQuery({ queryKey: ['team'], queryFn: () => apiGet<TeamMember[]>('/team') });
  const [name, setName] = useState('');
  const [type, setType] = useState<string | null>(null);
  const [brand, setBrand] = useState('');
  const [model, setModel] = useState('');
  const [power, setPower] = useState('');
  const [consumption, setConsumption] = useState('');
  const [operatorId, setOperatorId] = useState<string | null>(null);
  const [catalogTractor, setCatalogTractor] = useState<CatalogTractor | null>(null);

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
    },
  );

  const remove = useAction<string>(async (id) => {
    await apiSend('DELETE', `/machines/${id}`);
  }, [['machines']]);

  return (
    <Screen onRefresh={() => void machines.refetch()} refreshing={machines.isRefetching}>
      <QueryState query={machines}>
        {(items) =>
          items.length === 0 ? (
            <Empty icon="tractor" title="Niciun utilaj în parc" hint="Adaugă tractoarele și echipamentele folosite la lucrări." />
          ) : (
            <>
              {items.map((machine) => (
                <Panel key={machine.id} style={{ flexDirection: 'row', alignItems: 'center' }}>
                  <View style={{ flex: 1 }}>
                    <Txt variant="subtitle">{machine.name}</Txt>
                    <Txt variant="small">
                      {[
                        MACHINE_TYPES[machine.type],
                        [machine.brand, machine.model].filter(Boolean).join(' '),
                        machine.powerHp ? `${machine.powerHp} CP` : null,
                        machine.avgConsumptionLh ? `${machine.avgConsumptionLh} l/oră` : null,
                        machine.operator?.fullName,
                      ].filter(Boolean).join(', ')}
                    </Txt>
                  </View>
                  {canManage(role) ? (
                    <IconButton icon="archive-outline" label={`Arhivează ${machine.name}`} color={colors.straw} onPress={() => remove.mutate(machine.id)} />
                  ) : null}
                </Panel>
              ))}
            </>
          )
        }
      </QueryState>

      {canManage(role) ? (
        <Section title="Adaugă un utilaj">
          {usesFirebase ? <TractorWizard onSelect={applyCatalog} /> : null}
          <Panel>
            <Field label="Nume" value={name} onChangeText={setName} />
            <Choice label="Tip" options={typeOptions} value={type} onChange={setType} />
            <Choice label="Marcă" options={BRANDS.map((value) => ({ value, label: value }))} value={brand} onChange={setBrand} />
            <Field label="Altă marcă" value={brand} onChangeText={setBrand} />
            <Field label="Model" value={model} onChangeText={setModel} />
            <View style={{ flexDirection: 'row', gap: space.sm }}>
              <Field style={{ flex: 1 }} label="Putere (CP)" value={power} onChangeText={setPower} keyboardType="decimal-pad" />
              <Field style={{ flex: 1 }} label="Consum (l/oră)" value={consumption} onChangeText={setConsumption} keyboardType="decimal-pad" />
            </View>
            <Choice
              label="Operator"
              options={(team.data ?? []).map((member) => ({ value: member.userId, label: member.fullName }))}
              value={operatorId}
              onChange={setOperatorId}
            />
            <Button mode="contained" disabled={!name.trim() || !type || !optionalValid || add.isPending} loading={add.isPending} onPress={() => add.mutate()}>
              Salvează utilajul
            </Button>
          </Panel>
        </Section>
      ) : null}
    </Screen>
  );
}
