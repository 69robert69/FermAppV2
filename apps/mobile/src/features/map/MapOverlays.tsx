import { buildFieldPath, FIELD_STATE_COLORS, positionOnPath, type CropCode, type FieldStatus, type Position } from '@fermapp/domain';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { StyleSheet, View } from 'react-native';
import { Marker, type MapMarkerProps } from 'react-native-maps';
import type { Parcel } from '../../api/types';
import { Txt, type IconName } from '../../components/ui';
import { clock, hoursMinutes, number } from '../../lib/format';
import { runProgressAt } from '../../lib/hooks';
import { colors, fonts, space } from '../../theme';
import type { ActiveIrrigation } from '../intelligence/api';
import { FieldStatusBadge } from '../fieldStatus';
import { TillageBadge } from '../tillage/components';
import type { MapParcelInfo } from './api';

const REDRAW_MS = 700;

const CROP_ICONS: Partial<Record<CropCode, IconName>> = {
  WHEAT: 'barley',
  BARLEY: 'barley',
  OAT: 'barley',
  CORN: 'corn',
  SUNFLOWER: 'flower',
  SOY: 'seed-outline',
  RAPESEED: 'flower-outline',
  ALFALFA: 'grass',
  POTATO: 'food-apple-outline',
  BEET: 'sprout',
  VEGETABLES: 'carrot',
};

/** Android redraws a marker's content only while it tracks changes. */
function LiveMarker({ version, children, ...props }: MapMarkerProps & { version: string; children: ReactNode }) {
  const [tracking, setTracking] = useState(true);
  useEffect(() => {
    setTracking(true);
    const timer = setTimeout(() => setTracking(false), REDRAW_MS);
    return () => clearTimeout(timer);
  }, [version]);
  return (
    <Marker {...props} tracksViewChanges={tracking}>
      {children}
    </Marker>
  );
}

function MiniBar({ percent, color }: { percent: number; color: string }) {
  return (
    <View style={styles.track}>
      <View style={[styles.fill, { width: `${Math.min(100, percent)}%`, backgroundColor: color }]} />
    </View>
  );
}

function CardLine({ label, value }: { label: string; value: string }) {
  return (
    <View style={styles.line}>
      <Txt variant="small" style={styles.lineLabel}>{label}</Txt>
      <Txt variant="small" color={colors.chaff} style={styles.lineValue}>{value}</Txt>
    </View>
  );
}

const outerRing = (parcel: Parcel): Position[] => parcel.geometry.coordinates[0] ?? [];

export function IrrigationOverlay({
  parcel,
  run,
  now,
  detailed,
  onPress,
}: {
  parcel: Parcel;
  run: ActiveIrrigation;
  now: Date;
  detailed: boolean;
  onPress: () => void;
}) {
  const path = useMemo(() => buildFieldPath(outerRing(parcel), { passes: 1 }), [parcel]);
  const progress = runProgressAt(run, now);
  const position = positionOnPath(path, progress.percent);
  const latitude = position?.lat ?? parcel.centerLat;
  const longitude = position?.lng ?? parcel.centerLng;
  const paused = run.status === 'PAUSED';
  const color = paused ? colors.straw : colors.water;

  return (
    <LiveMarker
      version={`${Math.floor(progress.percent)}-${run.status}-${detailed}`}
      coordinate={{ latitude, longitude }}
      anchor={{ x: 0.5, y: detailed ? 0.2 : 0.5 }}
      zIndex={5}
      onPress={onPress}
      accessibilityLabel={`${run.label}, coverage ${Math.floor(progress.percent)}%. Open Irrigation`}
    >
      <View style={styles.machineWrap}>
        <View style={[styles.machine, { backgroundColor: color }]}>
          <MaterialCommunityIcons name="water" size={20} color={colors.loam} />
        </View>
        {detailed ? (
          <View style={styles.card}>
            <Txt variant="small" color={colors.chaff} style={styles.cardTitle} numberOfLines={1}>{run.label}</Txt>
            <MiniBar percent={progress.percent} color={color} />
            <CardLine label="Coverage" value={`${Math.floor(progress.percent)}%`} />
            <CardLine label="Remaining" value={hoursMinutes(progress.remainingSeconds)} />
            <CardLine label="ETA" value={paused ? 'Paused' : progress.eta ? clock(progress.eta) : '-'} />
          </View>
        ) : null}
      </View>
    </LiveMarker>
  );
}

/** When the map is zoomed out, small parcels stay easy to tap via a dot at their centre. */
export function ParcelTapTarget({ parcel, status, onPress }: { parcel: Parcel; status?: FieldStatus; onPress: () => void }) {
  return (
    <LiveMarker
      version={`${parcel.id}-${parcel.tillageSystem ?? ''}-${status?.state ?? ''}`}
      coordinate={{ latitude: parcel.centerLat, longitude: parcel.centerLng }}
      // With the badge under it, the dot sits in the top part of the marker (22 of 60 dp).
      anchor={{ x: 0.5, y: parcel.tillageSystem ? 0.37 : 0.5 }}
      zIndex={3}
      onPress={onPress}
      accessibilityLabel={`Open parcel ${parcel.name}${parcel.tillageSystem ? `, ${parcel.tillageSystem.replace(/_/g, " ").toLowerCase()}` : ""}`}
    >
      <View style={styles.tapWrap}>
        <View style={styles.tapTarget}>
          {/* The dot takes the plan status colour, so the state reads even when zoomed out. */}
          <View style={[styles.tapDot, status ? { backgroundColor: FIELD_STATE_COLORS[status.state] } : null]} />
        </View>
        {/* The technology reads at a glance even when the parcel cards are hidden. */}
        {parcel.tillageSystem ? <TillageBadge system={parcel.tillageSystem} size="sm" /> : null}
      </View>
    </LiveMarker>
  );
}

/** The parcel status, visible directly on the map. */
export function ParcelInfoOverlay({
  parcel,
  info,
  now,
  selected,
  favorite = false,
  status,
  onPress,
}: {
  parcel: Parcel;
  info: MapParcelInfo;
  now: Date;
  selected: boolean;
  /** Priority parcels carry a star next to their name. */
  favorite?: boolean;
  /** Plan status of the parcel, shown as a badge with the field completion score. */
  status?: FieldStatus;
  onPress: () => void;
}) {
  const run = info.irrigation[0];
  const irrigationPct = run ? Math.floor(runProgressAt(run, now).percent) : null;
  const icon = info.crop ? (CROP_ICONS[info.crop.code] ?? 'sprout') : 'help-circle-outline';
  const cropLabel = info.crop ? `${info.crop.name}${info.crop.planned ? ` ${info.crop.year}` : ''}` : 'No crop';

  return (
    <LiveMarker
      version={[info.crop?.code, info.growthPct, irrigationPct, info.openTasks, info.ndvi, selected, favorite, parcel.tillageSystem, status?.state, status?.badge, status?.completionScore].join('-')}
      coordinate={{ latitude: parcel.centerLat, longitude: parcel.centerLng }}
      anchor={{ x: 0.5, y: 1 }}
      zIndex={4}
      onPress={onPress}
      accessibilityLabel={`Parcel ${parcel.name}, ${cropLabel}, ${info.openTasks} tasks`}
    >
      <View style={[styles.info, selected && styles.infoSelected]}>
        <View style={styles.cropRow}>
          <Txt variant="small" color={colors.chaff} style={[styles.cardTitle, { flex: 1 }]} numberOfLines={1}>{parcel.name}</Txt>
          {favorite ? <MaterialCommunityIcons name="star" size={13} color={colors.wheat} /> : null}
        </View>
        {parcel.tillageSystem ? <TillageBadge system={parcel.tillageSystem} size="sm" /> : null}
        {status ? <FieldStatusBadge status={status} compact /> : null}
        <View style={styles.cropRow}>
          <MaterialCommunityIcons name={icon} size={14} color={colors.wheat} />
          <Txt variant="small" color={colors.chaff} style={styles.cropName} numberOfLines={1}>{cropLabel}</Txt>
        </View>
        {info.growthPct !== null ? <CardLine label="Growth" value={`${info.growthPct}%`} /> : null}
        {irrigationPct !== null ? <CardLine label="Irrigation" value={`${irrigationPct}%`} /> : null}
        <CardLine label="Tasks" value={String(info.openTasks)} />
        {status?.completionScore != null ? <CardLine label="Completion" value={`${status.completionScore}%`} /> : null}
        {info.ndvi !== null ? <CardLine label="NDVI" value={number(info.ndvi, 2)} /> : null}
      </View>
    </LiveMarker>
  );
}

const styles = StyleSheet.create({
  tapWrap: { alignItems: 'center', marginTop: -2 },
  tapTarget: { width: 44, height: 44, alignItems: 'center', justifyContent: 'center' },
  tapDot: { width: 14, height: 14, borderRadius: 7, borderWidth: 2, borderColor: colors.loam, backgroundColor: colors.wheat },
  machineWrap: { alignItems: 'center', width: 136 },
  machine: { width: 36, height: 36, borderRadius: 18, borderWidth: 2, borderColor: colors.loam, alignItems: 'center', justifyContent: 'center' },
  card: { width: 136, marginTop: space.xs, padding: space.sm, gap: 3, borderRadius: 6, backgroundColor: `${colors.loam}F2` },
  cardTitle: { fontFamily: fonts.semibold, fontSize: 13, lineHeight: 16 },
  track: { height: 5, borderRadius: 3, backgroundColor: colors.ridge, overflow: 'hidden' },
  fill: { height: '100%', borderRadius: 3 },
  line: { flexDirection: 'row', justifyContent: 'space-between', gap: space.xs },
  lineLabel: { fontSize: 11, lineHeight: 14 },
  lineValue: { fontFamily: fonts.medium, fontSize: 11, lineHeight: 14 },
  info: { width: 150, padding: space.sm, gap: 2, borderRadius: 6, borderWidth: 1, borderColor: colors.line, backgroundColor: `${colors.furrow}F2` },
  infoSelected: { borderColor: colors.chaff },
  cropRow: { flexDirection: 'row', alignItems: 'center', gap: space.xs, marginBottom: 2 },
  cropName: { flex: 1, fontFamily: fonts.medium, fontSize: 12, lineHeight: 15 },
});
