import { MaterialCommunityIcons } from '@expo/vector-icons';
import {
  analyzeTillage,
  formatEuro,
  parseTillageHistory,
  TILLAGE_LIST,
  TILLAGE_SYSTEMS,
  tillageTimeline,
  type TillageSystem,
} from '@fermapp/domain';
import { useNavigation, type NavigationProp } from '@react-navigation/native';
import { useMemo, useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';
import { Button } from 'react-native-paper';
import { Empty, Field, Panel, QueryState, Screen, Section, Txt, type IconName } from '../../components/ui';
import { number, parseNumber } from '../../lib/format';
import { useParcels } from '../../lib/hooks';
import type { RootStackParams } from '../../navigation/RootNavigator';
import { canManage, useAuth } from '../../store/auth';
import { colors, fonts, space } from '../../theme';
import { BarList, ChartTableToggle, DataTable, Donut, Legend, StackedColumns, TrendLine } from './charts';
import { SoilHealthMeter, TillageBadge } from './components';
import { useTillagePrefs, useTillageSettings } from './prefs';

const SERIES = TILLAGE_LIST.map((profile) => ({ key: profile.code, label: profile.name, color: profile.color }));
const MAX_YEARS = 6;

function Kpi({ icon, value, unit, label, tone = colors.chaff }: { icon: IconName; value: string; unit?: string; label: string; tone?: string }) {
  return (
    <View style={styles.kpi}>
      <MaterialCommunityIcons name={icon} size={18} color={colors.straw} />
      <Txt variant="figure" color={tone} style={styles.kpiValue}>
        {value}
        {unit ? <Txt variant="small">{` ${unit}`}</Txt> : null}
      </Txt>
      <Txt variant="small" numberOfLines={2}>{label}</Txt>
    </View>
  );
}

function Assumptions() {
  const settings = useTillageSettings();
  const { setValue, reset } = useTillagePrefs();
  const field = (system: TillageSystem, key: 'costPerHa' | 'fuelLPerHa') => {
    const value = settings[system]?.[key];
    return value == null ? '' : String(value);
  };
  const update = (system: TillageSystem, key: 'costPerHa' | 'fuelLPerHa', text: string) => {
    const value = parseNumber(text);
    setValue(system, key, text.trim() === '' || !Number.isFinite(value) || value < 0 ? undefined : value);
  };
  return (
    <Panel>
      <Txt variant="small">Your own figures per hectare. Empty fields use the defaults shown.</Txt>
      {TILLAGE_LIST.map((profile) => (
        <View key={profile.code} style={{ gap: space.xs }}>
          <TillageBadge system={profile.code} size="sm" />
          <View style={styles.inputs}>
            <Field
              style={styles.flex}
              label={`Cost €/ha (${profile.costPerHa})`}
              value={field(profile.code, 'costPerHa')}
              onChangeText={(text) => update(profile.code, 'costPerHa', text)}
              keyboardType="decimal-pad"
            />
            <Field
              style={styles.flex}
              label={`Diesel l/ha (${profile.fuelLPerHa})`}
              value={field(profile.code, 'fuelLPerHa')}
              onChangeText={(text) => update(profile.code, 'fuelLPerHa', text)}
              keyboardType="decimal-pad"
            />
          </View>
        </View>
      ))}
      <Button mode="text" onPress={reset}>Restore defaults</Button>
    </Panel>
  );
}

/** "Tillage analysis": farm-wide figures, executive charts and the editable assumptions. */
export function TillageAnalysisScreen() {
  const navigation = useNavigation<NavigationProp<RootStackParams>>();
  const role = useAuth((state) => state.role);
  const parcels = useParcels();
  const settings = useTillageSettings();
  const [table, setTable] = useState(false);

  const list = useMemo(() => (parcels.data ?? []).filter((parcel) => !parcel.archivedAt), [parcels.data]);
  const inputs = useMemo(
    () => list.map((parcel) => ({ areaHa: parcel.areaHa, system: parcel.tillageSystem ?? null, history: parseTillageHistory(parcel.tillageHistory) })),
    [list],
  );
  const analysis = useMemo(() => analyzeTillage(inputs, settings), [inputs, settings]);
  const years = useMemo(() => {
    const thisYear = new Date().getFullYear();
    const recorded = inputs.flatMap((input) => input.history.map((entry) => entry.year));
    const first = Math.max(Math.min(thisYear - 2, ...recorded), thisYear - MAX_YEARS + 1);
    const last = Math.min(Math.max(thisYear, ...recorded), first + MAX_YEARS - 1);
    return Array.from({ length: last - first + 1 }, (_, index) => first + index);
  }, [inputs]);
  const timeline = useMemo(() => tillageTimeline(inputs, years, settings), [inputs, years, settings]);

  const fuelBaseline = analysis.totalFuelL + analysis.fuelSavingsL;
  const fuelReductionPct = fuelBaseline > 0 ? Math.round((analysis.fuelSavingsL / fuelBaseline) * 100) : 0;
  const rows = analysis.bySystem.map((row) => ({ ...row, profile: TILLAGE_SYSTEMS[row.system] }));

  return (
    <Screen onRefresh={() => void parcels.refetch()} refreshing={parcels.isRefetching}>
      <QueryState query={parcels}>
        {() =>
          analysis.assignedAreaHa === 0 ? (
            <Empty
              icon="tractor-variant"
              title="No parcel has a tillage technology yet"
              hint="Open a parcel and choose Conventional, Minimum Till, Strip Till or No Till."
            />
          ) : (
            <>
              <Section title="Executive summary">
                <View style={styles.kpis}>
                  <Kpi
                    icon="cash-multiple"
                    value={formatEuro(analysis.costSavings, 0)}
                    unit="/yr"
                    label="Estimated savings vs conventional"
                    tone={analysis.costSavings > 0 ? colors.leaf : colors.chaff}
                  />
                  <Kpi icon="gas-station-outline" value={number(analysis.fuelSavingsL, 0)} unit="l" label={`Less diesel, ${fuelReductionPct}% lower`} tone={analysis.fuelSavingsL > 0 ? colors.leaf : colors.chaff} />
                  <Kpi icon="tractor-variant" value={`${analysis.passReductionPct}%`} label="Fewer passes over the fields" />
                  <Kpi icon="water-outline" value={analysis.avgWaterRetention != null ? number(analysis.avgWaterRetention, 1) : '-'} unit="/ 5" label="Average water retention" />
                </View>
                {analysis.avgSoilHealth != null ? (
                  <Panel>
                    <Txt variant="small">Farm average, weighted by area</Txt>
                    <SoilHealthMeter score={analysis.avgSoilHealth} />
                  </Panel>
                ) : null}
              </Section>

              <Section title="Distribution">
                <Panel>
                  <Donut
                    data={rows.map((row) => ({ key: row.system, label: row.profile.name, color: row.profile.color, value: row.areaHa }))}
                    total={analysis.assignedAreaHa}
                    unit="ha"
                    centerLabel="with a technology"
                  />
                  <Legend
                    series={SERIES}
                    values={Object.fromEntries(rows.map((row) => [row.system, `${number(row.areaHa)} ha`]))}
                  />
                  {analysis.unassignedAreaHa > 0 ? (
                    <Txt variant="small">{`${number(analysis.unassignedAreaHa)} ha have no technology set and are left out of the figures.`}</Txt>
                  ) : null}
                </Panel>
              </Section>

              <Section title="By technology" action={<ChartTableToggle table={table} onChange={setTable} />}>
                <Panel>
                  {table ? (
                    <DataTable
                      columns={['Technology', 'ha', 'Cost €', 'Diesel l']}
                      rows={[
                        ...rows.map((row) => [row.profile.badge, number(row.areaHa), formatEuro(row.totalCost, 0), number(row.fuelL, 0)]),
                        ['Total', number(analysis.assignedAreaHa), formatEuro(analysis.totalCost, 0), number(analysis.totalFuelL, 0)],
                      ]}
                    />
                  ) : (
                    <BarList
                      format={(value) => `${number(value)} ha`}
                      data={rows.map((row) => ({
                        key: row.system,
                        label: row.profile.name,
                        color: row.profile.color,
                        value: row.areaHa,
                        detail: row.parcels
                          ? `${row.parcels} parcel${row.parcels > 1 ? 's' : ''}, ${formatEuro(row.totalCost, 0)}, ${number(row.fuelL, 0)} l diesel`
                          : 'Not used',
                      }))}
                    />
                  )}
                  <View style={styles.totals}>
                    <Txt variant="small">{`Total cost ${formatEuro(analysis.totalCost, 0)}`}</Txt>
                    <Txt variant="small">{`Total diesel ${number(analysis.totalFuelL, 0)} l`}</Txt>
                  </View>
                </Panel>
              </Section>

              <Section title="Evolution">
                <Panel>
                  <Txt variant="subtitle">Hectares per technology</Txt>
                  <StackedColumns
                    years={years}
                    series={SERIES}
                    values={timeline.map((point) => point.areaBySystem)}
                    unit="ha"
                  />
                </Panel>
                <Panel>
                  <Txt variant="subtitle">Soil health</Txt>
                  <TrendLine
                    label="Average score, out of 100"
                    color={colors.leaf}
                    domain={[40, 100]}
                    format={(value) => String(Math.round(value))}
                    points={timeline.map((point) => ({ x: point.year, y: point.avgSoilHealth }))}
                  />
                </Panel>
                <Panel>
                  <Txt variant="subtitle">Water retention</Txt>
                  <TrendLine
                    label="Average stars, out of 5"
                    color={colors.water}
                    domain={[1, 5]}
                    format={(value) => number(value, 1)}
                    points={timeline.map((point) => ({ x: point.year, y: point.avgWaterRetention }))}
                  />
                </Panel>
                <Txt variant="small">Years before a parcel’s first recorded technology count as without one.</Txt>
              </Section>

              <Section title="Parcels">
                <Panel>
                  {list.map((parcel) => (
                    <Pressable
                      key={parcel.id}
                      accessibilityRole="button"
                      onPress={() => navigation.navigate('ParcelDetail', { parcelId: parcel.id, name: parcel.name })}
                      style={styles.parcelRow}
                    >
                      <View style={{ flex: 1 }}>
                        <Txt numberOfLines={1}>{parcel.name}</Txt>
                        <Txt variant="small">{`${number(parcel.areaHa)} ha`}</Txt>
                      </View>
                      {parcel.tillageSystem ? <TillageBadge system={parcel.tillageSystem} size="sm" /> : <Txt variant="small">Not set</Txt>}
                      <MaterialCommunityIcons name="chevron-right" size={20} color={colors.straw} />
                    </Pressable>
                  ))}
                </Panel>
              </Section>
            </>
          )
        }
      </QueryState>

      {canManage(role) ? (
        <Section title="Cost and diesel assumptions">
          <Assumptions />
        </Section>
      ) : null}
    </Screen>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  kpis: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  kpi: { width: '48.5%', padding: space.md, gap: 2, borderRadius: 12, backgroundColor: colors.furrow, borderWidth: 1, borderColor: `${colors.chaff}14` },
  kpiValue: { fontSize: 24, lineHeight: 28, fontFamily: fonts.figure },
  inputs: { flexDirection: 'row', gap: space.sm },
  totals: { flexDirection: 'row', justifyContent: 'space-between', flexWrap: 'wrap', gap: space.sm },
  parcelRow: { flexDirection: 'row', alignItems: 'center', gap: space.sm, minHeight: 48 },
});
