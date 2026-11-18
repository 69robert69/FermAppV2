import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useNavigation, type NavigationProp } from '@react-navigation/native';
import { useQuery } from '@tanstack/react-query';
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
import { useAuth } from '../store/auth';
import { colors, healthColor, space } from '../theme';

const SEVERITY_COLOR = { INFO: colors.water, WARNING: colors.amber, SEVERE: colors.rust } as const;

export function DashboardScreen() {
  const navigation = useNavigation<NavigationProp<RootStackParams & Record<string, undefined>>>();
  const farm = useAuth((state) => state.farm);
  const dashboard = useQuery({ queryKey: ['dashboard'], queryFn: () => apiGet<Dashboard>('/dashboard') });
  const parcels = useParcels();

  return (
    <Screen
      title={farm?.name ?? 'Ferma mea'}
      action={<IconButton icon="cog-outline" label="Setări" onPress={() => navigation.navigate('Settings')} />}
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
              title="Ferma nu are încă parcele"
              hint="Desenează prima parcelă pe hartă sau importă fișierul APIA."
              action={<Button mode="contained" onPress={() => navigation.navigate('ParcelEditor')}>Adaugă o parcelă</Button>}
            />
          ) : (
            <>
              <Panel>
                <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.sm }}>
                  {(parcels.data ?? []).slice(0, 24).map((parcel) => (
                    <Pressable
                      key={parcel.id}
                      accessibilityRole="button"
                      accessibilityLabel={`Parcela ${parcel.name}`}
                      onPress={() => navigation.navigate('ParcelDetail', { parcelId: parcel.id, name: parcel.name })}
                    >
                      <ParcelShape geometry={parcel.geometry} size={46} color={healthColor(parcel.satellite?.[0]?.healthClass) === colors.straw ? colors.wheat : healthColor(parcel.satellite?.[0]?.healthClass)} />
                    </Pressable>
                  ))}
                </View>
                <View style={{ flexDirection: 'row', flexWrap: 'wrap', rowGap: space.md }}>
                  <Stat value={String(data.parcels.count)} label="Parcele" />
                  <Stat value={number(data.parcels.totalAreaHa)} unit="ha" label="Suprafață totală" />
                  <Stat value={number(data.parcels.cultivatedAreaHa)} unit="ha" label={`Cultivat în ${data.year}`} color={colors.leaf} />
                  <Stat value={number(data.parcels.freeAreaHa)} unit="ha" label="Suprafață liberă" />
                </View>
              </Panel>

              {data.alerts.length > 0 ? (
                <Section title="Alerte meteo">
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

              {data.activeWork.length + data.activeIrrigation.length > 0 ? (
                <Section title="În desfășurare">
                  {data.activeWork.map((work) => (
                    <RunCard key={work.id} run={work} title={`${WORK_TYPES[work.type]}, ${work.parcel.name}`} subtitle={work.operator.fullName} />
                  ))}
                  {data.activeIrrigation.map((run) => (
                    <RunCard key={run.id} run={run} color={colors.water} title={`Irigare, ${run.parcel.name}`} subtitle={IRRIGATION_TYPES[run.systemType] ?? ''} />
                  ))}
                </Section>
              ) : null}

              <Panel style={{ flexDirection: 'row' }}>
                <Stat value={String(data.tasks.open)} label="Task-uri deschise" />
                <Stat value={String(data.tasks.overdue)} label="Depășite" color={data.tasks.overdue ? colors.rust : undefined} />
              </Panel>

              {data.crops.length > 0 ? (
                <Section title={`Culturi în ${data.year}`}>
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
