import { MaterialCommunityIcons } from '@expo/vector-icons';
import {
  estimateParcelTillage,
  formatEuro,
  parseTillageHistory,
  TILLAGE_BASELINE,
  TILLAGE_SYSTEMS,
  tillageRecommendations,
  waterRetentionMessage,
  type CropCode,
  type TillageSystem,
} from '@fermapp/domain';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Alert, StyleSheet, View } from 'react-native';
import { Button } from 'react-native-paper';
import { apiGet } from '../../api/client';
import type { Parcel, Task } from '../../api/types';
import { Choice, Panel, Section, Txt, type IconName } from '../../components/ui';
import { number, shortDate, WORK_TYPES } from '../../lib/format';
import { useToast } from '../../store/toast';
import { colors, fonts, space } from '../../theme';
import { useGenerateTillageTasks, useSetTillage, useTillageConflicts } from './api';
import { SoilHealthMeter, TillageBadge, TillagePicker, TillageTimeline, WaterStars } from './components';
import { useTillageSettings } from './prefs';

export const WORK_ICON: Record<string, IconName> = {
  PLOWING: 'shovel',
  DISCING: 'disc',
  SUBSOILING: 'terrain',
  SOWING: 'seed-outline',
  FERTILIZING: 'sprinkler-variant',
  SPRAYING: 'spray',
  HARVESTING: 'barley',
};

const ADVICE_ICON: Record<'info' | 'warning' | 'success', { icon: IconName; color: string }> = {
  info: { icon: 'information-outline', color: colors.water },
  warning: { icon: 'alert-outline', color: colors.amber },
  success: { icon: 'check-circle-outline', color: colors.leaf },
};

function Metric({ label, value, detail, color }: { label: string; value: string; detail?: string; color?: string }) {
  return (
    <View style={styles.metric}>
      <Txt variant="small">{label}</Txt>
      <Txt variant="subtitle" color={color} style={styles.metricValue}>{value}</Txt>
      {detail ? <Txt variant="small" style={styles.metricDetail}>{detail}</Txt> : null}
    </View>
  );
}

/** A saving reads as a plain amount; an extra cost carries a plus sign. */
const signed = (value: number) => `${value >= 0 ? '' : '+'}${formatEuro(Math.abs(value), 0)}`;

/** The tillage technology of a parcel and everything it drives: works, costs, diesel, soil, water and advice. */
export function TillageSection({ parcel, canEdit, crop }: { parcel: Parcel; canEdit: boolean; crop?: CropCode | null }) {
  const settings = useTillageSettings();
  const thisYear = new Date().getFullYear();
  const [year, setYear] = useState(String(thisYear));
  const history = parseTillageHistory(parcel.tillageHistory);
  const system = parcel.tillageSystem ?? null;
  const tasks = useQuery({ queryKey: ['tasks'], staleTime: 30_000, queryFn: () => apiGet<Task[]>('/tasks') });
  const conflicts = useTillageConflicts(parcel.id, system);
  const generate = useGenerateTillageTasks(parcel.id, (created) =>
    useToast.getState().show(created ? `${created} recommended operations added to To Do.` : 'All recommended operations are already planned.'),
  );
  const setTillage = useSetTillage(parcel.id);

  const choose = (next: TillageSystem) => {
    const profile = TILLAGE_SYSTEMS[next];
    const target = Number(year);
    Alert.alert(`${profile.name} in ${target}`, `${profile.description}\n\nAdd its recommended operations to To Do as well?`, [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Set only', onPress: () => setTillage.mutate({ system: next, year: target }) },
      {
        text: 'Set and plan',
        onPress: () =>
          setTillage.mutate(
            { system: next, year: target },
            { onSuccess: () => generate.mutate({ system: next, start: new Date(Date.now() + 86_400_000), existing: tasks.data ?? [] }) },
          ),
      },
    ]);
  };

  const yearOptions = [thisYear - 1, thisYear, thisYear + 1].map((value) => ({ value: String(value), label: String(value) }));
  const yearSystem = history.find((entry) => entry.year === Number(year))?.system ?? (Number(year) === thisYear ? system : null);
  const estimate = system ? estimateParcelTillage(system, parcel.areaHa, settings, parcel.seasons?.length || 1) : null;
  const profile = system ? TILLAGE_SYSTEMS[system] : null;
  const isBaseline = system === TILLAGE_BASELINE;

  return (
    <Section title="Tillage technology" action={<TillageBadge system={system} />}>
      <Panel>
        {profile ? (
          <Txt variant="small">{profile.description}</Txt>
        ) : (
          <Txt variant="small">No technology set. Choose one to get recommended operations, cost and diesel estimates and soil indicators.</Txt>
        )}
        {canEdit ? (
          <View style={{ gap: space.sm }}>
            <Choice label="Season" options={yearOptions} value={year} onChange={setYear} />
            <TillagePicker value={yearSystem} onChange={choose} disabled={setTillage.isPending} />
          </View>
        ) : null}
      </Panel>

      {conflicts.length > 0 ? (
        <Panel style={styles.alertPanel}>
          <View style={styles.inline}>
            <MaterialCommunityIcons name="alert" size={18} color={colors.amber} />
            <Txt variant="subtitle">{`${conflicts.length} ${conflicts.length === 1 ? 'item contradicts' : 'items contradict'} the technology`}</Txt>
          </View>
          {conflicts.map((item) => (
            <View key={item.id} style={{ gap: 2 }}>
              <Txt>{`${item.kind === 'work' ? 'Running: ' : 'Task: '}${item.title}`}</Txt>
              <Txt variant="small">{item.message}</Txt>
            </View>
          ))}
        </Panel>
      ) : null}

      {estimate && profile ? (
        <>
          <Panel>
            <SoilHealthMeter score={estimate.soilHealth} />
            <View style={styles.divider} />
            <View style={styles.scoreRow}>
              <Txt variant="small">Water retention</Txt>
              <WaterStars value={estimate.waterRetention} />
            </View>
            <Txt variant="small">{waterRetentionMessage(profile.code)}</Txt>
          </Panel>

          <Panel>
            <Txt variant="subtitle">Estimated economics</Txt>
            <View style={styles.metrics}>
              <Metric label="Parcel cost" value={formatEuro(estimate.totalCost, 0)} detail={`${formatEuro(estimate.costPerHa, 0)}/ha`} />
              <Metric
                label="vs Conventional"
                value={isBaseline ? 'Reference' : signed(estimate.costSavings)}
                detail={isBaseline ? undefined : estimate.costSavings >= 0 ? 'saved' : 'extra cost'}
                color={!isBaseline && estimate.costSavings > 0 ? colors.leaf : undefined}
              />
              <Metric label="Diesel, season" value={`${number(estimate.seasonFuelL, 0)} l`} detail={`${number(estimate.fuelPerHa, 0)} l/ha`} />
              <Metric label="Diesel, year" value={`${number(estimate.annualFuelL, 0)} l`} detail={`${parcel.seasons?.length || 1} season${(parcel.seasons?.length || 1) > 1 ? 's' : ''}`} />
            </View>
            {!isBaseline && estimate.annualFuelSavingsL > 0 ? (
              <View style={styles.saving}>
                <MaterialCommunityIcons name="gas-station-outline" size={18} color={colors.leaf} />
                <Txt color={colors.chaff} style={{ flex: 1 }}>{`Estimated savings: ${number(estimate.annualFuelSavingsL, 0)} litres of diesel and ${estimate.passesSaved} fewer passes`}</Txt>
              </View>
            ) : null}
          </Panel>

          <Panel>
            <View style={styles.inline}>
              <Txt variant="subtitle" style={{ flex: 1 }}>Recommended operations</Txt>
              <Txt variant="small">{`${profile.passes} pass${profile.passes > 1 ? 'es' : ''}`}</Txt>
            </View>
            {profile.operations.map((operation, index) => (
              <View key={operation.key} style={styles.operation}>
                <View style={[styles.stepNo, { borderColor: profile.color }]}>
                  <Txt variant="small" color={colors.chaff} style={styles.stepText}>{String(index + 1)}</Txt>
                </View>
                <MaterialCommunityIcons name={WORK_ICON[operation.workType] ?? 'tractor-variant'} size={18} color={colors.straw} />
                <Txt style={{ flex: 1 }}>{operation.label}</Txt>
                <Txt variant="small">{index === 0 ? 'Start' : `+${operation.offsetDays} d`}</Txt>
              </View>
            ))}
            {Object.keys(profile.discouragedWork).length > 0 ? (
              <Txt variant="small">{`Not recommended: ${Object.keys(profile.discouragedWork).map((type) => WORK_TYPES[type] ?? type).join(', ')}.`}</Txt>
            ) : null}
            {canEdit ? (
              <Button
                mode="outlined"
                icon="playlist-plus"
                loading={generate.isPending}
                disabled={generate.isPending}
                onPress={() => generate.mutate({ system: profile.code, start: new Date(Date.now() + 86_400_000), existing: tasks.data ?? [] })}
              >
                {`Plan from ${shortDate(new Date(Date.now() + 86_400_000))}`}
              </Button>
            ) : null}
          </Panel>

          <Panel>
            <Txt variant="subtitle">Recommendations</Txt>
            {tillageRecommendations(profile.code, { crop }).map((item) => (
              <View key={item.message} style={styles.inline}>
                <MaterialCommunityIcons name={ADVICE_ICON[item.tone].icon} size={18} color={ADVICE_ICON[item.tone].color} />
                <Txt variant="small" color={colors.chaff} style={{ flex: 1 }}>{item.message}</Txt>
              </View>
            ))}
          </Panel>
        </>
      ) : null}

      <Panel>
        <Txt variant="subtitle">History</Txt>
        <TillageTimeline history={history} />
      </Panel>
    </Section>
  );
}

const styles = StyleSheet.create({
  inline: { flexDirection: 'row', alignItems: 'center', gap: space.sm },
  alertPanel: { borderWidth: 1, borderColor: `${colors.amber}88` },
  divider: { height: StyleSheet.hairlineWidth, backgroundColor: colors.line },
  scoreRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  metrics: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  metric: { width: '48.5%', padding: space.md, borderRadius: 10, backgroundColor: colors.ridge, gap: 2 },
  metricValue: { fontFamily: fonts.figure, fontSize: 20, lineHeight: 24 },
  metricDetail: { fontSize: 11.5, lineHeight: 15 },
  saving: { flexDirection: 'row', alignItems: 'center', gap: space.sm, padding: space.md, borderRadius: 8, backgroundColor: `${colors.leaf}1F` },
  operation: { flexDirection: 'row', alignItems: 'center', gap: space.sm, minHeight: 36 },
  stepNo: { width: 22, height: 22, borderRadius: 11, borderWidth: 1.5, alignItems: 'center', justifyContent: 'center' },
  stepText: { fontFamily: fonts.bold, fontSize: 11, lineHeight: 13 },
});
