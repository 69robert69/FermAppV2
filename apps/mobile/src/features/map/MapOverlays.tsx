import { buildFieldPath, positionOnPath, type CropCode, type Position } from '@fermapp/domain';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { StyleSheet, View } from 'react-native';
import { Marker, Polyline, type MapMarkerProps } from 'react-native-maps';
import type { Parcel } from '../../api/types';
import { Txt, type IconName } from '../../components/ui';
import { clock, hoursMinutes, number } from '../../lib/format';
import { runProgressAt } from '../../lib/hooks';
import { colors, fonts, space } from '../../theme';
import type { ActiveIrrigation, ActiveWork } from '../intelligence/api';
import type { MapParcelInfo } from './api';

/** O poziție GPS mai veche de două minute nu mai descrie locul în care se află utilajul. */
const GPS_FRESH_MS = 120_000;
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

/** Android redesenează conținutul unui marker doar cât timp urmărește schimbările. */
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

export function WorkOverlay({
  parcel,
  work,
  now,
  detailed,
  onPress,
}: {
  parcel: Parcel;
  work: ActiveWork;
  now: Date;
  detailed: boolean;
  onPress: () => void;
}) {
  const path = useMemo(
    () => buildFieldPath(outerRing(parcel), { workingWidthM: work.workingWidthM }),
    [parcel, work.workingWidthM],
  );
  const progress = runProgressAt(work, now);
  const estimated = positionOnPath(path, progress.percent);
  const location = work.lastLocation;
  const gps = location && now.getTime() - new Date(location.recordedAt).getTime() < GPS_FRESH_MS ? location : null;
  const latitude = gps?.lat ?? estimated?.lat;
  const longitude = gps?.lng ?? estimated?.lng;
  if (latitude === undefined || longitude === undefined) return null;

  const paused = work.status === 'PAUSED';
  const doneHa = (work.areaHa * progress.percent) / 100;
  const color = paused ? colors.straw : colors.wheat;
  const name = work.machine?.name ?? 'Utilaj';

  return (
    <>
      {estimated && !gps && estimated.worked.length > 1 ? (
        <Polyline
          coordinates={estimated.worked.map(([lng, lat]) => ({ latitude: lat, longitude: lng }))}
          strokeColor={`${colors.wheat}CC`}
          strokeWidth={2}
          zIndex={3}
        />
      ) : null}
      <LiveMarker
        version={`${Math.floor(progress.percent)}-${work.status}-${detailed}-${Math.round(estimated?.headingDeg ?? 0)}`}
        coordinate={{ latitude, longitude }}
        anchor={{ x: 0.5, y: detailed ? 0.16 : 0.5 }}
        zIndex={6}
        onPress={onPress}
        accessibilityLabel={`${name}, ${Math.floor(progress.percent)}% realizat`}
      >
        <View style={styles.machineWrap}>
          <View style={[styles.machine, { backgroundColor: color }]}>
            <MaterialCommunityIcons name="tractor-variant" size={20} color={colors.wheatInk} />
            {estimated ? (
              <View style={styles.heading}>
                <MaterialCommunityIcons
                  name="navigation"
                  size={11}
                  color={colors.chaff}
                  style={{ transform: [{ rotate: `${Math.round(estimated.headingDeg)}deg` }] }}
                />
              </View>
            ) : null}
          </View>
          {detailed ? (
            <View style={styles.card}>
              <Txt variant="small" color={colors.chaff} style={styles.cardTitle} numberOfLines={1}>{name}</Txt>
              <MiniBar percent={progress.percent} color={color} />
              <CardLine label="Realizat" value={`${Math.floor(progress.percent)}%`} />
              <CardLine label="Finalizat" value={`${number(doneHa)} ha`} />
              <CardLine label="Rămas" value={`${number(Math.max(0, work.areaHa - doneHa))} ha`} />
              <CardLine label="ETA" value={paused ? 'În pauză' : progress.eta ? clock(progress.eta) : '-'} />
            </View>
          ) : null}
        </View>
      </LiveMarker>
    </>
  );
}

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
      accessibilityLabel={`${run.label}, acoperire ${Math.floor(progress.percent)}%. Deschide Irigații`}
    >
      <View style={styles.machineWrap}>
        <View style={[styles.machine, { backgroundColor: color }]}>
          <MaterialCommunityIcons name="water" size={20} color={colors.loam} />
        </View>
        {detailed ? (
          <View style={styles.card}>
            <Txt variant="small" color={colors.chaff} style={styles.cardTitle} numberOfLines={1}>{run.label}</Txt>
            <MiniBar percent={progress.percent} color={color} />
            <CardLine label="Acoperire" value={`${Math.floor(progress.percent)}%`} />
            <CardLine label="Rămas" value={hoursMinutes(progress.remainingSeconds)} />
            <CardLine label="ETA" value={paused ? 'În pauză' : progress.eta ? clock(progress.eta) : '-'} />
          </View>
        ) : null}
      </View>
    </LiveMarker>
  );
}

/** Când harta este departe, parcelele mici rămân ușor de atins printr-un punct în centrul lor. */
export function ParcelTapTarget({ parcel, onPress }: { parcel: Parcel; onPress: () => void }) {
  return (
    <LiveMarker
      version={parcel.id}
      coordinate={{ latitude: parcel.centerLat, longitude: parcel.centerLng }}
      anchor={{ x: 0.5, y: 0.5 }}
      zIndex={3}
      onPress={onPress}
      accessibilityLabel={`Deschide parcela ${parcel.name}`}
    >
      <View style={styles.tapTarget}>
        <View style={styles.tapDot} />
      </View>
    </LiveMarker>
  );
}

/** Situația parcelei, vizibilă direct pe hartă. */
export function ParcelInfoOverlay({
  parcel,
  info,
  now,
  selected,
  onPress,
}: {
  parcel: Parcel;
  info: MapParcelInfo;
  now: Date;
  selected: boolean;
  onPress: () => void;
}) {
  const run = info.irrigation[0];
  const irrigationPct = run ? Math.floor(runProgressAt(run, now).percent) : null;
  const icon = info.crop ? (CROP_ICONS[info.crop.code] ?? 'sprout') : 'help-circle-outline';
  const cropLabel = info.crop ? `${info.crop.name}${info.crop.planned ? ` ${info.crop.year}` : ''}` : 'Fără cultură';

  return (
    <LiveMarker
      version={[info.crop?.code, info.growthPct, irrigationPct, info.openTasks, info.ndvi, selected].join('-')}
      coordinate={{ latitude: parcel.centerLat, longitude: parcel.centerLng }}
      anchor={{ x: 0.5, y: 1 }}
      zIndex={4}
      onPress={onPress}
      accessibilityLabel={`Parcela ${parcel.name}, ${cropLabel}, ${info.openTasks} task-uri`}
    >
      <View style={[styles.info, selected && styles.infoSelected]}>
        <Txt variant="small" color={colors.chaff} style={styles.cardTitle} numberOfLines={1}>{parcel.name}</Txt>
        <View style={styles.cropRow}>
          <MaterialCommunityIcons name={icon} size={14} color={colors.wheat} />
          <Txt variant="small" color={colors.chaff} style={styles.cropName} numberOfLines={1}>{cropLabel}</Txt>
        </View>
        {info.growthPct !== null ? <CardLine label="Creștere" value={`${info.growthPct}%`} /> : null}
        {irrigationPct !== null ? <CardLine label="Irigare" value={`${irrigationPct}%`} /> : null}
        <CardLine label="Task-uri" value={String(info.openTasks)} />
        {info.ndvi !== null ? <CardLine label="NDVI" value={number(info.ndvi, 2)} /> : null}
      </View>
    </LiveMarker>
  );
}

const styles = StyleSheet.create({
  tapTarget: { width: 44, height: 44, alignItems: 'center', justifyContent: 'center' },
  tapDot: { width: 14, height: 14, borderRadius: 7, borderWidth: 2, borderColor: colors.loam, backgroundColor: colors.wheat },
  machineWrap: { alignItems: 'center', width: 136 },
  machine: { width: 36, height: 36, borderRadius: 18, borderWidth: 2, borderColor: colors.loam, alignItems: 'center', justifyContent: 'center' },
  heading: { position: 'absolute', right: -7, top: -7, width: 18, height: 18, borderRadius: 9, backgroundColor: colors.loam, alignItems: 'center', justifyContent: 'center' },
  card: { width: 136, marginTop: space.xs, padding: space.sm, gap: 3, borderRadius: 6, backgroundColor: `${colors.loam}F2` },
  cardTitle: { fontFamily: fonts.semibold, fontSize: 13, lineHeight: 16 },
  track: { height: 5, borderRadius: 3, backgroundColor: colors.ridge, overflow: 'hidden' },
  fill: { height: '100%', borderRadius: 3 },
  line: { flexDirection: 'row', justifyContent: 'space-between', gap: space.xs },
  lineLabel: { fontSize: 11, lineHeight: 14 },
  lineValue: { fontFamily: fonts.medium, fontSize: 11, lineHeight: 14 },
  info: { width: 124, padding: space.sm, gap: 2, borderRadius: 6, borderWidth: 1, borderColor: colors.line, backgroundColor: `${colors.furrow}F2` },
  infoSelected: { borderColor: colors.chaff },
  cropRow: { flexDirection: 'row', alignItems: 'center', gap: space.xs, marginBottom: 2 },
  cropName: { flex: 1, fontFamily: fonts.medium, fontSize: 12, lineHeight: 15 },
});
