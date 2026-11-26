import { MaterialCommunityIcons } from '@expo/vector-icons';
import { HEALTH_COLORS, ndviColor, proposeTask, type FieldZone, type SatelliteFinding } from '@fermapp/domain';
import { useNavigation, useRoute, type NavigationProp, type RouteProp } from '@react-navigation/native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { ActivityIndicator, Image, Pressable, StyleSheet, useWindowDimensions, View } from 'react-native';
import Svg, { Circle, Defs, LinearGradient, Path, Polygon, Polyline, Rect, Stop, Text as SvgText } from 'react-native-svg';
import { Button } from 'react-native-paper';
import { Choice, Empty, Panel, Screen, Section, Txt, type IconName } from '../../components/ui';
import { buildFarmSnapshot, createTaskFromProposal } from '../../firebase/copilot';
import { usesFirebase } from '../../firebase/config';
import { shortDate } from '../../lib/format';
import { errorMessage, useParcels } from '../../lib/hooks';
import type { RootStackParams } from '../../navigation/RootNavigator';
import { canPlan, useAuth } from '../../store/auth';
import { colors, fonts, space } from '../../theme';
import { useCopilot } from '../copilot/store';
import { CircularProgress } from '../intelligence/CircularProgress';
import { monitorField, ringOf, type FieldMonitoring } from './analysis';
import { FIELD_STRESS_SCALE, fieldImageUrl, IMAGE_LAYERS, LAYER_SCALES, type ImageLayer } from './planetary';
import { useSatelliteSettings } from './prefs';

type Period = '7' | '30' | '90' | 'SEASON' | 'YEAR';
const PERIODS: { value: Period; label: string }[] = [
  { value: '7', label: '7 days' },
  { value: '30', label: '30 days' },
  { value: '90', label: '90 days' },
  { value: 'SEASON', label: 'Season' },
  { value: 'YEAR', label: 'Last year' },
];
const SEVERITY_COLOR = { INFO: colors.water, WARNING: colors.amber, CRITICAL: colors.rust } as const;
const ZONE_TASKS: { type: string; label: string; icon: IconName; priority: 'MEDIUM' | 'HIGH' }[] = [
  { type: 'FIELD_INSPECTION', label: 'Inspect field zone', icon: 'magnify', priority: 'MEDIUM' },
  { type: 'NITROGEN', label: 'Apply nitrogen', icon: 'sprout', priority: 'MEDIUM' },
  { type: 'IRRIGATION_CHECK', label: 'Check irrigation', icon: 'water-check-outline', priority: 'HIGH' },
  { type: 'CROP_SCOUTING', label: 'Field visit', icon: 'walk', priority: 'MEDIUM' },
];

const fixed = (value: number | null | undefined, digits = 2) => (value == null ? '–' : value.toFixed(digits));
const signed = (value: number) => `${value > 0 ? '+' : ''}${value.toFixed(2)}`;

/** Draws the field image with the outline, a darkened surround and tappable zones. */
function FieldImage({ data, layer, selected, onSelect, width }: { data: FieldMonitoring; layer: ImageLayer; selected: string | null; onSelect: (id: string | null) => void; width: number }) {
  const [minLng, minLat, maxLng, maxLat] = data.bbox;
  const latRad = (((minLat + maxLat) / 2) * Math.PI) / 180;
  const aspect = Math.min(1.6, Math.max(0.45, (maxLat - minLat) / ((maxLng - minLng) * Math.cos(latRad))));
  const height = Math.round(width * aspect);
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);
  const px = 512;
  const uri = fieldImageUrl(data.current.sceneId, data.bbox, layer, { width: px, height: Math.round(px * aspect) }, { min: data.current.ndvi.p2, max: data.current.ndvi.p98 });
  const point = ([lng, lat]: readonly number[]) => `${(((lng! - minLng) / (maxLng - minLng)) * width).toFixed(1)},${(((maxLat - lat!) / (maxLat - minLat)) * height).toFixed(1)}`;
  // Everything outside the field is darkened: a full-frame rectangle with the outline cut out (even-odd fill).
  const mask = useMemo(() => `M0,0 H${width} V${height} H0 Z M${data.ring.map(point).join(' L')} Z`, [data.ring, width, height]); // eslint-disable-line react-hooks/exhaustive-deps
  const problems = new Set(data.findings.map((finding) => finding.zoneId).filter((id): id is string => Boolean(id)));
  return (
    <View style={{ width, height, borderRadius: 8, overflow: 'hidden', backgroundColor: colors.ridge }}>
      {failed ? (
        <View style={styles.imageError}>
          <Txt variant="small">The image could not be loaded. Pull down to retry.</Txt>
        </View>
      ) : (
        <Image
          key={uri}
          source={{ uri }}
          style={{ width, height }}
          resizeMode="stretch"
          onLoadStart={() => setLoading(true)}
          onLoadEnd={() => setLoading(false)}
          onError={() => setFailed(true)}
          accessibilityLabel={`${layer} satellite image of the field from ${data.current.date}`}
        />
      )}
      <Svg width={width} height={height} style={StyleSheet.absoluteFill}>
        <Path d={mask} fill="rgba(24,27,19,0.72)" fillRule="evenodd" />
        <Polygon points={data.ring.map(point).join(' ')} fill="none" stroke={colors.chaff} strokeWidth={2} />
        {data.zones.map((zone: FieldZone) => {
          const observed = data.current.zones.find((item) => item.zoneId === zone.id);
          const isProblem = problems.has(zone.id);
          const isSelected = selected === zone.id;
          return (
            <Polygon
              key={zone.id}
              points={zone.ring.map(point).join(' ')}
              fill={isSelected ? 'rgba(226,178,61,0.18)' : isProblem ? 'rgba(224,88,74,0.20)' : 'rgba(0,0,0,0.001)'}
              stroke={isSelected ? colors.wheat : isProblem ? HEALTH_COLORS.critical : 'rgba(240,236,219,0.45)'}
              strokeWidth={isSelected ? 3 : isProblem ? 2 : 0.8}
              strokeDasharray={isProblem && !isSelected ? '6,4' : undefined}
              onPress={() => onSelect(isSelected ? null : zone.id)}
              accessibilityLabel={`${zone.name} zone, NDVI ${fixed(observed?.ndvi)}`}
            />
          );
        })}
      </Svg>
      {loading && !failed ? <ActivityIndicator style={StyleSheet.absoluteFill} color={colors.wheat} /> : null}
    </View>
  );
}

function Legend({ layer }: { layer: ImageLayer }) {
  const legend = layer === 'STRESS' ? FIELD_STRESS_SCALE : LAYER_SCALES[layer];
  if (!legend) return null;
  return (
    <View style={{ gap: 4 }}>
      <Svg height={10} width="100%">
        <Defs>
          <LinearGradient id="legend" x1="0" y1="0" x2="1" y2="0">
            {legend.colors.map((color, index) => <Stop key={color} offset={index / (legend.colors.length - 1)} stopColor={color} />)}
          </LinearGradient>
        </Defs>
        <Rect width="100%" height={10} rx={5} fill="url(#legend)" />
      </Svg>
      <View style={styles.row}>
        <Txt variant="small" style={{ flex: 1 }}>{legend.low}</Txt>
        <Txt variant="small">{legend.high}</Txt>
      </View>
    </View>
  );
}

function HistoryChart({ points, width }: { points: { date: string; ndvi: number }[]; width: number }) {
  if (points.length < 2) return <Txt variant="small">More clear images are needed to draw the trend.</Txt>;
  const height = 120;
  const pad = 24;
  const times = points.map((item) => Date.parse(item.date));
  const [t0, t1] = [Math.min(...times), Math.max(...times)];
  const x = (date: string) => pad + ((Date.parse(date) - t0) / Math.max(1, t1 - t0)) * (width - pad * 2);
  const y = (ndvi: number) => height - pad - (Math.max(0, Math.min(1, ndvi)) / 1) * (height - pad * 2);
  return (
    <Svg width={width} height={height}>
      {[0, 0.5, 1].map((level) => (
        <SvgText key={level} x={2} y={y(level) + 4} fill={colors.straw} fontSize={10}>{level.toFixed(1)}</SvgText>
      ))}
      <Polyline points={points.map((item) => `${x(item.date)},${y(item.ndvi)}`).join(' ')} fill="none" stroke={colors.leaf} strokeWidth={2} />
      {points.map((item) => <Circle key={item.date} cx={x(item.date)} cy={y(item.ndvi)} r={3.5} fill={ndviColor(item.ndvi)} stroke={colors.loam} strokeWidth={1} />)}
      <SvgText x={pad} y={height - 6} fill={colors.straw} fontSize={10}>{shortDate(points[0]!.date)}</SvgText>
      <SvgText x={width - pad} y={height - 6} fill={colors.straw} fontSize={10} textAnchor="end">{shortDate(points[points.length - 1]!.date)}</SvgText>
    </Svg>
  );
}

function useCreateTask(parcelId: string) {
  const [state, setState] = useState<Record<string, 'busy' | 'done' | string>>({});
  const create = async (key: string, task: { type: string; title: string; reason: string; priority: 'LOW' | 'MEDIUM' | 'HIGH' }) => {
    setState((current) => ({ ...current, [key]: 'busy' }));
    try {
      const snapshot = await buildFarmSnapshot();
      const proposal = proposeTask(snapshot, { ...task, parcelId, dueDate: snapshot.now.slice(0, 10), windowDays: 3, withFollowUps: false });
      const action = await createTaskFromProposal(proposal, false);
      useCopilot.getState().log(action);
      setState((current) => ({ ...current, [key]: 'done' }));
    } catch (error) {
      setState((current) => ({ ...current, [key]: errorMessage(error) }));
    }
  };
  return { state, create };
}

function TaskButton({ id, label, icon, onPress, state }: { id: string; label: string; icon: IconName; onPress: () => void; state: Record<string, string> }) {
  const value = state[id];
  if (value === 'done') return <Txt variant="small" color={colors.leaf}>✓ Added to To-Do</Txt>;
  return (
    <View style={{ gap: 2 }}>
      <Pressable accessibilityRole="button" disabled={value === 'busy'} onPress={onPress} style={styles.taskButton}>
        {value === 'busy' ? <ActivityIndicator size="small" color={colors.wheat} /> : <MaterialCommunityIcons name={icon} size={15} color={colors.wheat} />}
        <Txt variant="small" color={colors.wheat} style={{ fontFamily: fonts.semibold }}>{label}</Txt>
      </Pressable>
      {value && value !== 'busy' ? <Txt variant="small" color={colors.rust}>{value}</Txt> : null}
    </View>
  );
}

function FindingCard({ finding, zones, tasks, canCreate }: { finding: SatelliteFinding; zones: FieldZone[]; tasks: ReturnType<typeof useCreateTask>; canCreate: boolean }) {
  const [open, setOpen] = useState(false);
  const zone = zones.find((item) => item.id === finding.zoneId);
  return (
    <View style={[styles.finding, { borderLeftColor: SEVERITY_COLOR[finding.severity] }]}>
      <Pressable accessibilityRole="button" onPress={() => setOpen((value) => !value)} style={{ gap: 2 }}>
        <Txt variant="subtitle">{finding.title}</Txt>
        <Txt variant="small" color={colors.chaff}>{finding.message}</Txt>
        <Txt variant="small" color={colors.wheat}>{open ? 'Hide explanation' : 'Why?'}</Txt>
      </Pressable>
      {open ? <Txt variant="small">{finding.explanation}</Txt> : null}
      {finding.task && canCreate ? (
        <TaskButton id={finding.key} label={finding.task.title} icon="plus-circle-outline" state={tasks.state} onPress={() => void tasks.create(finding.key, finding.task!)} />
      ) : null}
      {zone ? <Txt variant="small">Zone: {zone.name} ({Math.round(zone.share * 100)}% of the field)</Txt> : null}
    </View>
  );
}

export function SatelliteScreen() {
  const route = useRoute<RouteProp<RootStackParams, 'SatelliteMonitoring'>>();
  const navigation = useNavigation<NavigationProp<RootStackParams>>();
  const { parcelId } = route.params;
  const { width } = useWindowDimensions();
  const settings = useSatelliteSettings();
  const role = useAuth((state) => state.role);
  const parcels = useParcels();
  const parcel = parcels.data?.find((item) => item.id === parcelId);
  const [sceneId, setSceneId] = useState<string | undefined>();
  const [layer, setLayer] = useState<ImageLayer>('NDVI');
  const [zoneId, setZoneId] = useState<string | null>(null);
  const [period, setPeriod] = useState<Period>('30');
  const client = useQueryClient();
  const field = parcel ? { id: parcel.id, name: parcel.name, areaHa: parcel.areaHa, ring: ringOf(parcel.geometry) } : null;
  const monitoring = useQuery({
    queryKey: ['satellite', parcelId, sceneId ?? 'latest'],
    queryFn: () => monitorField(field!, { sceneId }),
    enabled: Boolean(field) && settings.active,
    staleTime: 30 * 60_000,
    retry: 1,
  });
  const tasks = useCreateTask(parcelId);
  const canCreate = usesFirebase && canPlan(role);
  const contentWidth = Math.min(width, 720) - space.lg * 2;

  if (!settings.ready) return <Screen><ActivityIndicator color={colors.wheat} /></Screen>;
  if (!settings.enabled) {
    return (
      <Screen>
        <Empty icon="satellite-variant" title="Satellite monitoring is off" hint="Turn it on in Settings to analyse this field with free Sentinel-2 images." action={<Button mode="contained" onPress={() => navigation.navigate('Settings')}>Open Settings</Button>} />
      </Screen>
    );
  }
  const data = monitoring.data;
  const refresh = async () => {
    setSceneId(undefined);
    await client.fetchQuery({ queryKey: ['satellite', parcelId, 'latest'], queryFn: () => monitorField(field!, { force: true }) });
  };
  const zone = data?.zones.find((item) => item.id === zoneId) ?? null;
  const zoneObs = data?.current.zones.find((item) => item.zoneId === zoneId) ?? null;
  const comparison = data
    ? period === '7' ? data.comparison.d7 : period === '30' ? data.comparison.d30 : period === '90' ? data.comparison.d90 : period === 'YEAR' ? data.comparison.yearAgo : null
    : null;
  const seasonPoints = data ? (period === 'SEASON' ? data.history.filter((point) => point.date >= `${Number(data.current.date.slice(0, 4)) - (Number(data.current.date.slice(5, 7)) >= 9 ? 0 : 1)}-09-01`) : data.history) : [];

  return (
    <Screen onRefresh={() => void refresh()} refreshing={monitoring.isFetching && !monitoring.isPending}>
      {monitoring.isPending ? (
        <Panel style={{ alignItems: 'center' }}>
          <ActivityIndicator color={colors.wheat} />
          <Txt variant="small">Finding the latest clear Sentinel-2 image and analysing the zones…</Txt>
        </Panel>
      ) : monitoring.isError || !data ? (
        <Empty icon="cloud-alert-outline" title="No satellite analysis yet" hint={monitoring.error instanceof Error ? monitoring.error.message : 'The satellite service did not answer.'} action={<Button mode="outlined" onPress={() => void monitoring.refetch()}>Try again</Button>} />
      ) : (
        <>
          <Panel style={styles.header}>
            {data.health.level === 'NO_CROP' ? (
              <MaterialCommunityIcons name="sprout-outline" size={56} color={colors.straw} />
            ) : (
              <CircularProgress percent={data.health.score} size={84} color={data.health.color} icon="satellite-variant" />
            )}
            <View style={{ flex: 1, gap: 2 }}>
              <Txt variant="small" style={styles.kicker}>FIELD HEALTH</Txt>
              <Txt variant="title" color={data.health.color}>
                {data.health.level === 'NO_CROP' ? data.health.label : `${data.health.score}/100 · ${data.health.label}`}
              </Txt>
              <Txt variant="small">{`Image of ${shortDate(data.current.date)} · ${data.current.cloudPct}% cloud over the field`}</Txt>
              {data.context.crop ? <Txt variant="small">{`${data.context.crop}, ${data.context.stagePct ?? '?'}% of season`}</Txt> : null}
            </View>
          </Panel>

          <View style={styles.indices}>
            {[
              { label: 'NDVI', value: data.current.ndvi.mean, color: ndviColor(data.current.ndvi.mean) },
              { label: 'NDRE', value: data.current.ndre, color: colors.chaff },
              { label: 'Moisture', value: data.current.ndmi, color: (data.current.ndmi ?? 0) < -0.05 ? colors.amber : colors.water },
            ].map((item) => (
              <Panel key={item.label} style={styles.index}>
                <Txt variant="small">{item.label}</Txt>
                <Txt variant="figure" color={item.color}>{fixed(item.value)}</Txt>
              </Panel>
            ))}
          </View>

          <Choice options={IMAGE_LAYERS.map(({ value, label }) => ({ value, label }))} value={layer} onChange={setLayer} />
          <Txt variant="small">{IMAGE_LAYERS.find((item) => item.value === layer)?.hint}</Txt>
          <FieldImage data={data} layer={layer} selected={zoneId} onSelect={setZoneId} width={contentWidth} />
          <Legend layer={layer} />
          <Txt variant="small">Tap a zone to see its values and plan work there. Red dashed zones need attention.</Txt>

          {zone ? (
            <Panel>
              <Txt variant="subtitle">{`${zone.name[0]!.toUpperCase()}${zone.name.slice(1)} zone · ${Math.round(zone.share * 100)}% of the field`}</Txt>
              <Txt variant="small">
                {`NDVI ${fixed(zoneObs?.ndvi)} (field ${fixed(data.current.ndvi.mean)}) · NDRE ${fixed(zoneObs?.ndre)} · Moisture ${fixed(zoneObs?.ndmi)}`}
              </Txt>
              {data.findings.filter((finding) => finding.zoneId === zone.id).map((finding) => (
                <Txt key={finding.key} variant="small" color={SEVERITY_COLOR[finding.severity]}>{finding.message}</Txt>
              ))}
              {canCreate ? (
                <View style={styles.taskGrid}>
                  {ZONE_TASKS.map((item) => {
                    const key = `${zone.id}-${item.type}`;
                    return (
                      <TaskButton
                        key={key}
                        id={key}
                        label={item.label}
                        icon={item.icon}
                        state={tasks.state}
                        onPress={() => void tasks.create(key, { type: item.type, priority: item.priority, title: `${item.label} – ${zone.name} zone, ${parcel?.name ?? ''}`, reason: `Satellite image of ${data.current.date}: NDVI ${fixed(zoneObs?.ndvi)} vs ${fixed(data.current.ndvi.mean)} for the field.` })}
                      />
                    );
                  })}
                </View>
              ) : null}
            </Panel>
          ) : null}

          <Section title="AI satellite analysis">
            {settings.aiAnalysis ? (
              data.findings.length ? (
                data.findings.map((finding) => <FindingCard key={finding.key} finding={finding} zones={data.zones} tasks={tasks} canCreate={canCreate} />)
              ) : (
                <Panel><Txt variant="small">No problems detected: vegetation is even across the field.</Txt></Panel>
              )
            ) : (
              <Panel><Txt variant="small">AI analysis is off in Settings.</Txt></Panel>
            )}
          </Section>

          <Section title="History">
            <Choice options={PERIODS} value={period} onChange={setPeriod} />
            <Panel>
              {period === 'SEASON' ? (
                <Txt variant="small">{`${seasonPoints.length} clear images this season.`}</Txt>
              ) : comparison ? (
                <Txt>
                  {`NDVI ${fixed(data.current.ndvi.mean)} now vs ${fixed(comparison.ndvi)} on ${shortDate(comparison.date)}: `}
                  <Txt color={comparison.delta >= 0 ? colors.leaf : colors.rust}>{signed(comparison.delta)}</Txt>
                </Txt>
              ) : (
                <Txt variant="small">No clear image around that date.</Txt>
              )}
              <HistoryChart points={seasonPoints} width={contentWidth - space.lg * 2} />
            </Panel>
          </Section>

          {data.clearScenes.length > 1 ? (
            <Section title="Image date">
              <Choice
                options={data.clearScenes.slice(0, 8).map((scene) => ({ value: scene.id, label: `${shortDate(scene.date)}${scene.cloudPct ? ` · ${scene.cloudPct}%☁` : ''}` }))}
                value={sceneId ?? data.current.sceneId}
                onChange={(id) => setSceneId(id === data.clearScenes[0]?.id ? undefined : id)}
              />
            </Section>
          ) : null}

          <Txt variant="small" style={{ opacity: 0.8 }}>
            {`Contains modified Copernicus Sentinel-2 data (${data.current.date}), processed with Microsoft Planetary Computer. Analysed ${shortDate(data.analysedAt)}.`}
          </Txt>
        </>
      )}
    </Screen>
  );
}

const styles = StyleSheet.create({
  header: { flexDirection: 'row', alignItems: 'center', gap: space.lg },
  kicker: { fontFamily: fonts.semibold, letterSpacing: 0.8, fontSize: 11 },
  indices: { flexDirection: 'row', gap: space.sm },
  index: { flex: 1, padding: space.md, gap: 0, alignItems: 'center' },
  row: { flexDirection: 'row' },
  imageError: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: space.lg },
  finding: { backgroundColor: colors.furrow, borderRadius: 8, padding: space.md, gap: space.xs, borderLeftWidth: 4 },
  taskGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  taskButton: { flexDirection: 'row', alignItems: 'center', gap: 6, minHeight: 36, paddingHorizontal: 12, borderRadius: 18, borderWidth: 1, borderColor: colors.line, alignSelf: 'flex-start' },
});
