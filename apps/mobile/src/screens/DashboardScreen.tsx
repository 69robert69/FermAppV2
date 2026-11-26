import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useNavigation, type NavigationProp } from '@react-navigation/native';
import { analyzeTillage, FIELD_STATE_COLORS, formatEuro, TILLAGE_LIST, TILLAGE_SYSTEMS } from '@fermapp/domain';
import { useQuery } from '@tanstack/react-query';
import { useMemo } from 'react';
import { Pressable, View } from 'react-native';
import { Button } from 'react-native-paper';
import { apiGet } from '../api/client';
import type { Dashboard } from '../api/types';
import { ParcelShape } from '../components/ParcelShape';
import { RunCard } from '../components/RunCard';
import { Empty, IconButton, Panel, ProgressTrack, QueryState, Screen, Section, Stat, Txt } from '../components/ui';
import { IRRIGATION_TYPES, number, WORK_TYPES } from '../lib/format';
import { useParcels } from '../lib/hooks';
import type { RootStackParams } from '../navigation/RootNavigator';
import { useTillageSettings } from '../features/tillage';
import { useFarmEconomics } from '../features/economics/api';
import { OperationalSection, useFieldStatuses } from '../features/fieldStatus';
import { isRunActive } from '../features/operations/finish';
import { canPlan, useAuth } from '../store/auth';
import { colors, space } from '../theme';

const SEVERITY_COLOR = { INFO: colors.water, WARNING: colors.amber, SEVERE: colors.rust } as const;

/** Real cost per hectare and profit of the farm this season, with a link to the financial dashboard. */
function FinancialCard({ onOpen }: { onOpen: () => void }) {
  const year = new Date().getFullYear();
  const economics = useFarmEconomics(year);
  const summary = economics.data?.summary;
  return (
    <Pressable accessibilityRole="button" accessibilityLabel="Open the financial dashboard" onPress={onOpen}>
      <Panel>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: space.sm }}>
          <MaterialCommunityIcons name="cash-multiple" size={20} color={colors.wheat} />
          <Txt variant="subtitle" style={{ flex: 1 }}>{`Economics ${year}`}</Txt>
          <MaterialCommunityIcons name="chevron-right" size={22} color={colors.straw} />
        </View>
        {!summary || summary.cost <= 0 ? (
          <Txt variant="small">Add seed, fertiliser and crop protection on a parcel to see the real cost per hectare.</Txt>
        ) : (
          <View style={{ flexDirection: 'row', flexWrap: 'wrap', rowGap: space.md }}>
            <Stat value={formatEuro(summary.costPerHa, 0)} unit="/ha" label="Average cost" color={colors.wheat} />
            <Stat
              value={summary.profitPerHa == null ? '-' : formatEuro(summary.profitPerHa, 0)}
              unit={summary.profitPerHa == null ? undefined : '/ha'}
              label="Average profit"
              color={summary.profitPerHa == null ? undefined : summary.profitPerHa >= 0 ? colors.leaf : colors.rust}
            />
            <Stat value={formatEuro(summary.cost, 0)} label="Season cost" />
          </View>
        )}
      </Panel>
    </Pressable>
  );
}

/** Farm statistics by tillage technology, with a link to the full analysis. */
function TillageCard({ onOpen }: { onOpen: () => void }) {
  const parcels = useParcels();
  const settings = useTillageSettings();
  const analysis = useMemo(
    () => analyzeTillage((parcels.data ?? []).filter((parcel) => !parcel.archivedAt).map((parcel) => ({ areaHa: parcel.areaHa, system: parcel.tillageSystem ?? null })), settings),
    [parcels.data, settings],
  );
  const total = analysis.totalAreaHa || 1;
  return (
    <Pressable accessibilityRole="button" accessibilityLabel="Open the tillage analysis" onPress={onOpen}>
      <Panel>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: space.sm }}>
          <MaterialCommunityIcons name="tractor-variant" size={20} color={colors.wheat} />
          <Txt variant="subtitle" style={{ flex: 1 }}>Tillage technologies</Txt>
          <MaterialCommunityIcons name="chevron-right" size={22} color={colors.straw} />
        </View>
        {analysis.assignedAreaHa === 0 ? (
          <Txt variant="small">Set a technology on your parcels to compare costs, diesel and soil health.</Txt>
        ) : (
          <>
            {/* One bar split by area share; segments keep a 2px gap and the legend names each one. */}
            <View style={{ flexDirection: 'row', height: 12, borderRadius: 6, overflow: 'hidden', gap: 2, backgroundColor: colors.ridge }}>
              {analysis.bySystem.filter((row) => row.areaHa > 0).map((row) => (
                <View key={row.system} style={{ flex: row.areaHa / total, backgroundColor: TILLAGE_SYSTEMS[row.system].color }} />
              ))}
              {analysis.unassignedAreaHa > 0 ? <View style={{ flex: analysis.unassignedAreaHa / total }} /> : null}
            </View>
            <View style={{ flexDirection: 'row', flexWrap: 'wrap', columnGap: space.md, rowGap: 2 }}>
              {TILLAGE_LIST.map((profile) => {
                const row = analysis.bySystem.find((item) => item.system === profile.code);
                return row && row.areaHa > 0 ? (
                  <View key={profile.code} style={{ flexDirection: 'row', alignItems: 'center', gap: 4 }}>
                    <View style={{ width: 8, height: 8, borderRadius: 2, backgroundColor: profile.color }} />
                    <Txt variant="small">{`${profile.badge} ${number(row.areaHa)} ha`}</Txt>
                  </View>
                ) : null;
              })}
            </View>
            <View style={{ flexDirection: 'row', flexWrap: 'wrap', rowGap: space.md }}>
              <Stat value={formatEuro(analysis.costSavings, 0)} label="Saved per year" color={analysis.costSavings > 0 ? colors.leaf : undefined} />
              <Stat value={number(analysis.fuelSavingsL, 0)} unit="l" label="Less diesel" />
              <Stat value={analysis.avgSoilHealth != null ? String(analysis.avgSoilHealth) : '-'} unit="/100" label="Soil health" />
            </View>
          </>
        )}
      </Panel>
    </Pressable>
  );
}

export function DashboardScreen() {
  const navigation = useNavigation<NavigationProp<RootStackParams & Record<string, undefined>>>();
  const farm = useAuth((state) => state.farm);
  const role = useAuth((state) => state.role);
  const statuses = useFieldStatuses();
  const dashboard = useQuery({ queryKey: ['dashboard'], queryFn: () => apiGet<Dashboard>('/dashboard') });
  const parcels = useParcels();

  return (
    <Screen
      title={farm?.name ?? 'My farm'}
      action={<IconButton icon="cog-outline" label="Settings" onPress={() => navigation.navigate('Settings')} />}
      onRefresh={() => {
        void dashboard.refetch();
        void parcels.refetch();
      }}
      refreshing={dashboard.isRefetching}
    >
      <QueryState query={dashboard}>
        {(data) =>
          data.parcels.count === 0 ? (
            <Empty
              icon="vector-polygon"
              title="The farm has no parcels yet"
              hint="Draw the first parcel on the map or import the APIA file."
              action={<Button mode="contained" onPress={() => navigation.navigate('ParcelEditor')}>Add a parcel</Button>}
            />
          ) : (
            <>
              <Panel>
                <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.sm }}>
                  {(parcels.data ?? []).slice(0, 24).map((parcel) => (
                    <Pressable
                      key={parcel.id}
                      accessibilityRole="button"
                      accessibilityLabel={`Parcel ${parcel.name}${statuses.get(parcel.id) ? `, ${statuses.get(parcel.id)!.badge}` : ''}`}
                      onPress={() => navigation.navigate('ParcelDetail', { parcelId: parcel.id, name: parcel.name })}
                      style={{ width: 52, alignItems: 'center' }}
                    >
                      {/* Each parcel takes its plan status colour, with its field completion score below. */}
                      <ParcelShape geometry={parcel.geometry} size={46} color={statuses.get(parcel.id) ? FIELD_STATE_COLORS[statuses.get(parcel.id)!.state] : colors.wheat} />
                      <Txt variant="small" style={{ fontSize: 10, lineHeight: 12 }}>
                        {statuses.get(parcel.id)?.completionScore != null ? `${statuses.get(parcel.id)!.completionScore}%` : '-'}
                      </Txt>
                    </Pressable>
                  ))}
                </View>
                <View style={{ flexDirection: 'row', flexWrap: 'wrap', rowGap: space.md }}>
                  <Stat value={String(data.parcels.count)} label="Parcels" />
                  <Stat value={number(data.parcels.totalAreaHa)} unit="ha" label="Total area" />
                  <Stat value={number(data.parcels.cultivatedAreaHa)} unit="ha" label={`Cultivated in ${data.year}`} color={colors.leaf} />
                  <Stat value={number(data.parcels.freeAreaHa)} unit="ha" label="Free area" />
                </View>
              </Panel>

              <OperationalSection onOpenParcel={(parcel) => navigation.navigate('ParcelDetail', { parcelId: parcel.id, name: parcel.name })} />

              {data.alerts.length > 0 ? (
                <Section title="Weather alerts">
                  {data.alerts.slice(0, 3).map((alert) => (
                    <Panel key={alert.id} style={{ flexDirection: 'row', gap: space.md }}>
                      <MaterialCommunityIcons name="weather-lightning-rainy" size={22} color={SEVERITY_COLOR[alert.severity]} />
                      <View style={{ flex: 1 }}>
                        <Txt variant="subtitle">{alert.title}</Txt>
                        <Txt variant="small">{alert.message}</Txt>
                      </View>
                    </Panel>
                  ))}
                </Section>
              ) : null}

              {data.activeWork.filter((work) => isRunActive(work)).length + data.activeIrrigation.length > 0 ? (
                <Section title="In progress">
                  {data.activeWork.filter((work) => isRunActive(work)).map((work) => (
                    <RunCard key={work.id} run={work} title={`${WORK_TYPES[work.type]}, ${work.parcel.name}`} subtitle={work.operator.fullName} />
                  ))}
                  {data.activeIrrigation.map((run) => (
                    <RunCard key={run.id} run={run} color={colors.water} title={`Irrigation, ${run.parcel.name}`} subtitle={IRRIGATION_TYPES[run.systemType] ?? ''} />
                  ))}
                </Section>
              ) : null}

              {canPlan(role) ? <FinancialCard onOpen={() => navigation.navigate('FinancialDashboard')} /> : null}
              <TillageCard onOpen={() => navigation.navigate('TillageAnalysis')} />

              <Panel style={{ flexDirection: 'row' }}>
                <Stat value={String(data.tasks.open)} label="Open tasks" />
                <Stat value={String(data.tasks.overdue)} label="Overdue" color={data.tasks.overdue ? colors.rust : undefined} />
              </Panel>

              {data.crops.length > 0 ? (
                <Section title={`Crops in ${data.year}`}>
                  <Panel>
                    {data.crops.map((crop) => (
                      <View key={crop.crop} style={{ gap: space.xs }}>
                        <View style={{ flexDirection: 'row', justifyContent: 'space-between' }}>
                          <Txt>{crop.name}</Txt>
                          <Txt variant="small">{`${number(crop.areaHa)} ha`}</Txt>
                        </View>
                        <ProgressTrack percent={(crop.areaHa / data.parcels.totalAreaHa) * 100} color={colors.leaf} />
                      </View>
                    ))}
                  </Panel>
                </Section>
              ) : null}
            </>
          )
        }
      </QueryState>
    </Screen>
  );
}
