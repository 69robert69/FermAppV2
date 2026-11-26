import {
  defaultSettings,
  formatEuro,
  HOURS_PER_MONTH,
  laborHourlyRate,
  machineHourlyCost,
  type EconomicSettings,
  type MachineCostProfile,
} from '@fermapp/domain';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { Button } from 'react-native-paper';
import { apiGet } from '../../api/client';
import type { Machine, TeamMember } from '../../api/types';
import { Field, Panel, QueryState, Screen, Section, Txt } from '../../components/ui';
import { MACHINE_TYPES, parseNumber, ROLE_LABELS } from '../../lib/format';
import { canManage, useAuth } from '../../store/auth';
import { colors, space } from '../../theme';
import { useEconomicSettings, useSaveSettings } from './api';

function Money({ label, value, onChange, disabled }: { label: string; value: number | null | undefined; onChange: (value: number | null) => void; disabled?: boolean }) {
  const [text, setText] = useState(value == null ? '' : String(value).replace('.', ','));
  return (
    <Field
      style={styles.flex}
      label={label}
      value={text}
      editable={!disabled}
      keyboardType="decimal-pad"
      onChangeText={(next) => {
        setText(next);
        const parsed = parseNumber(next);
        onChange(next.trim() === '' || !Number.isFinite(parsed) ? null : parsed);
      }}
    />
  );
}

const EMPTY_MACHINE: MachineCostProfile = { purchasePrice: 0, residualPct: 20, depreciationYears: 10, annualHours: 500, serviceCostPerYear: 0, maintenanceCostPerYear: 0 };

/** Diesel price, labour rates and machine ownership costs used to price every operation. */
export function EconomicSettingsScreen() {
  const role = useAuth((state) => state.role);
  const editable = canManage(role);
  const settings = useEconomicSettings();
  const team = useQuery({ queryKey: ['team'], queryFn: () => apiGet<TeamMember[]>('/team') });
  const machines = useQuery({ queryKey: ['machines'], queryFn: () => apiGet<Machine[]>('/machines') });
  const [draft, setDraft] = useState<EconomicSettings>(defaultSettings());
  const [dirty, setDirty] = useState(false);
  const save = useSaveSettings(() => setDirty(false));

  useEffect(() => {
    if (settings.data) {
      setDraft(settings.data);
      setDirty(false);
    }
  }, [settings.data]);

  const change = (next: (current: EconomicSettings) => EconomicSettings) => {
    setDraft(next);
    setDirty(true);
  };
  const setMachine = (id: string, patch: Partial<MachineCostProfile>) =>
    change((current) => ({ ...current, machines: { ...current.machines, [id]: { ...EMPTY_MACHINE, ...current.machines[id], ...patch } } }));

  return (
    <Screen>
      <QueryState query={settings}>
        {() => (
          <View key={settings.dataUpdatedAt} style={{ gap: space.xl }}>
            <Section title="Diesel and labour">
              <Panel>
                <View style={styles.inputs}>
                  <Money label="Diesel price (€/l)" value={draft.fuelPricePerL} disabled={!editable} onChange={(value) => change((current) => ({ ...current, fuelPricePerL: value ?? 0 }))} />
                  <Money label="Default labour (€/h)" value={draft.defaultLaborRate} disabled={!editable} onChange={(value) => change((current) => ({ ...current, defaultLaborRate: value ?? 0 }))} />
                </View>
                <Txt variant="small">The default rate is used for operators without their own salary or rate.</Txt>
              </Panel>
            </Section>

            <Section title="Operators">
              <Panel>
                {(team.data ?? []).map((member) => {
                  const profile = draft.labor[member.userId] ?? {};
                  const rate = laborHourlyRate(profile, draft.defaultLaborRate);
                  return (
                    <View key={member.userId} style={styles.block}>
                      <View style={styles.head}>
                        <Txt color={colors.chaff} style={styles.flex}>{member.fullName}</Txt>
                        <Txt variant="small">{ROLE_LABELS[member.role]}</Txt>
                      </View>
                      <View style={styles.inputs}>
                        <Money
                          label="Monthly salary (€)"
                          value={profile.monthlySalary}
                          disabled={!editable}
                          onChange={(value) => change((current) => ({ ...current, labor: { ...current.labor, [member.userId]: { ...current.labor[member.userId], monthlySalary: value } } }))}
                        />
                        <Money
                          label="Hourly rate (€/h)"
                          value={profile.hourlyRate}
                          disabled={!editable}
                          onChange={(value) => change((current) => ({ ...current, labor: { ...current.labor, [member.userId]: { ...current.labor[member.userId], hourlyRate: value } } }))}
                        />
                      </View>
                      <Txt variant="small">{`Charged to operations at ${formatEuro(rate, 2)}/h${profile.hourlyRate ? '' : profile.monthlySalary ? ` (salary over ${HOURS_PER_MONTH} h)` : ' (default)'}`}</Txt>
                    </View>
                  );
                })}
              </Panel>
            </Section>

            <Section title="Machines">
              <QueryState query={machines}>
                {(items) => (
                  <Panel>
                    {items.length === 0 ? <Txt variant="small">No machines in the fleet yet.</Txt> : null}
                    {items.map((machine) => {
                      const profile = draft.machines[machine.id];
                      const cost = machineHourlyCost(profile, machine.type);
                      return (
                        <View key={machine.id} style={styles.block}>
                          <View style={styles.head}>
                            <Txt color={colors.chaff} style={styles.flex}>{machine.name}</Txt>
                            <Txt variant="small">{MACHINE_TYPES[machine.type] ?? machine.type}</Txt>
                          </View>
                          <View style={styles.inputs}>
                            <Money label="Purchase cost (€)" value={profile?.purchasePrice} disabled={!editable} onChange={(value) => setMachine(machine.id, { purchasePrice: value ?? 0 })} />
                            <Money label="Residual value (%)" value={profile?.residualPct} disabled={!editable} onChange={(value) => setMachine(machine.id, { residualPct: value ?? 20 })} />
                          </View>
                          <View style={styles.inputs}>
                            <Money label="Depreciation (years)" value={profile?.depreciationYears} disabled={!editable} onChange={(value) => setMachine(machine.id, { depreciationYears: value ?? 10 })} />
                            <Money label="Hours per year" value={profile?.annualHours} disabled={!editable} onChange={(value) => setMachine(machine.id, { annualHours: value ?? 500 })} />
                          </View>
                          <View style={styles.inputs}>
                            <Money label="Service (€/year)" value={profile?.serviceCostPerYear} disabled={!editable} onChange={(value) => setMachine(machine.id, { serviceCostPerYear: value ?? 0 })} />
                            <Money label="Maintenance (€/year)" value={profile?.maintenanceCostPerYear} disabled={!editable} onChange={(value) => setMachine(machine.id, { maintenanceCostPerYear: value ?? 0 })} />
                          </View>
                          <Txt variant="small" color={cost.estimated ? undefined : colors.chaff}>
                            {cost.estimated
                              ? `Typical ${formatEuro(cost.perHour, 0)}/h for this type until its costs are entered.`
                              : `Real cost ${formatEuro(cost.perHour, 2)}/h: depreciation ${formatEuro(cost.depreciation, 2)}, service ${formatEuro(cost.service, 2)}, maintenance ${formatEuro(cost.maintenance, 2)}.`}
                          </Txt>
                        </View>
                      );
                    })}
                  </Panel>
                )}
              </QueryState>
            </Section>

            {editable ? (
              <Button mode="contained" icon="content-save-outline" disabled={!dirty || save.isPending} loading={save.isPending} onPress={() => save.mutate(draft)}>
                {dirty ? 'Save and recalculate' : 'Saved'}
              </Button>
            ) : (
              <Txt variant="small">Only administrators and farm managers can change these figures.</Txt>
            )}
          </View>
        )}
      </QueryState>
    </Screen>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  inputs: { flexDirection: 'row', gap: space.sm },
  block: { gap: space.sm, paddingVertical: space.sm, borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: colors.line },
  head: { flexDirection: 'row', alignItems: 'center', gap: space.sm },
});
