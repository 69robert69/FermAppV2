import { MaterialCommunityIcons } from '@expo/vector-icons';
import {
  buildFieldPlan,
  FIELD_STATE_COLORS,
  fieldCoverageAt,
  fieldPositionAt,
  fieldTrailAt,
  pointInRing,
  type FieldPlan,
  type Position,
} from '@fermapp/domain';
import { memo, useEffect, useMemo, useState, type ReactNode } from 'react';
import { Platform, StyleSheet, View } from 'react-native';
import { Marker, Polygon, Polyline, type LatLng, type MapMarkerProps } from 'react-native-maps';
import type { Parcel } from '../../api/types';
import { Txt } from '../../components/ui';
import { hoursMinutes, WORK_TYPES } from '../../lib/format';
import { runProgressAt, useNow } from '../../lib/hooks';
import { colors, fonts, space } from '../../theme';
import type { ActiveWork } from '../intelligence/api';
import { finishOperation } from './finish';
import { useOperations } from './store';
import { TractorGlyph } from './TractorGlyph';

/** Agricultural green for worked ground. */
export const WORKED_GREEN = '#58A23A';
/** A GPS fix older than this no longer says where the machine is. */
const GPS_FRESH_MS = 120_000;
/** Frame interval while animating: about 15 updates a second keeps markers fluid without draining the battery. */
const LIVE_FRAME_MS = 66;
const IDLE_FRAME_MS = 1000;
const LIVE_COVERAGE_MS = 1000;
const IDLE_COVERAGE_MS = 4000;
const DEFAULT_WIDTH_M = 6;
const REDRAW_MS = 700;
const NATIVE_ROTATION = Platform.OS === 'android';

const toLatLng = (points: readonly Position[]): LatLng[] => points.map(([longitude, latitude]) => ({ latitude, longitude }));

/** How the worked ground looks for each operation. */
interface Effect {
  fill: string;
  rows: string | null;
  rowWidth: number;
  dashed?: boolean;
}

const EFFECTS: Record<string, Effect> = {
  SOWING: { fill: `${WORKED_GREEN}8C`, rows: '#2F6B1F', rowWidth: 1 },
  FERTILIZING: { fill: `${WORKED_GREEN}80`, rows: '#CDE8B0', rowWidth: 1, dashed: true },
  SPRAYING: { fill: '#4FB39A66', rows: null, rowWidth: 0 },
  HARVESTING: { fill: `${WORKED_GREEN}8C`, rows: '#E7D28A', rowWidth: 1.5 },
  PLOWING: { fill: `${WORKED_GREEN}8C`, rows: '#5B3A20', rowWidth: 1.5 },
  DISCING: { fill: `${WORKED_GREEN}8C`, rows: '#6E4A2A', rowWidth: 1, dashed: true },
  SUBSOILING: { fill: `${WORKED_GREEN}8C`, rows: '#5B3A20', rowWidth: 1 },
};
const effectFor = (type: string) => EFFECTS[type] ?? EFFECTS.SOWING!;

/** Android redraws a custom marker only while it tracks view changes. */
function SnapMarker({ version, children, ...props }: MapMarkerProps & { version: string; children: ReactNode }) {
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

export const useFieldPlan = (parcel: Parcel, workingWidthM: number | null | undefined): FieldPlan | null =>
  useMemo(() => buildFieldPlan(parcel.geometry.coordinates[0] ?? [], { workingWidthM: workingWidthM || DEFAULT_WIDTH_M }), [parcel.geometry, workingWidthM]);

/** The outline of a parcel being worked, with a soft green glow. */
export const WorkingGlow = memo(function WorkingGlow({ parcel, color = FIELD_STATE_COLORS.ACTIVE }: { parcel: Parcel; color?: string }) {
  const coordinates = useMemo(() => toLatLng(parcel.geometry.coordinates[0] ?? []), [parcel.geometry]);
  return (
    <>
      <Polygon coordinates={coordinates} strokeColor={`${color}4D`} strokeWidth={9} fillColor="transparent" zIndex={2} tappable={false} />
      <Polygon coordinates={coordinates} strokeColor={color} strokeWidth={2} fillColor="transparent" zIndex={2} tappable={false} />
    </>
  );
});

/** Worked ground: green fill clipped to the parcel, plus rows, furrows or stubble for the operation. */
const CoverageLayer = memo(function CoverageLayer({ worked, rows, type }: { worked: Position[][]; rows: Position[][]; type: string }) {
  const effect = effectFor(type);
  const pieces = useMemo(() => worked.map(toLatLng), [worked]);
  // The rows join at the headland into one line, as the machine drove them.
  const rowLine = useMemo(() => toLatLng(rows.flat()), [rows]);
  return (
    <>
      {pieces.map((coordinates, index) => (
        <Polygon key={index} coordinates={coordinates} fillColor={effect.fill} strokeColor="transparent" strokeWidth={0} zIndex={3} tappable={false} />
      ))}
      {effect.rows && rowLine.length > 1 ? (
        <Polyline coordinates={rowLine} strokeColor={effect.rows} strokeWidth={effect.rowWidth} lineDashPattern={effect.dashed ? [4, 6] : undefined} zIndex={4} />
      ) : null}
    </>
  );
});

/** The machine, rotated with its heading. Its picture is drawn once; only the position and rotation change. */
const MachineMarker = memo(function MachineMarker({
  latitude,
  longitude,
  heading,
  type,
  paused,
  label,
  onPress,
}: {
  latitude: number;
  longitude: number;
  heading: number;
  type: string;
  paused: boolean;
  label: string;
  onPress: () => void;
}) {
  return (
    <SnapMarker
      version={`${type}-${paused}`}
      coordinate={{ latitude, longitude }}
      // Android rotates the marker natively; Apple Maps on iOS does not, so the picture turns instead.
      rotation={NATIVE_ROTATION ? heading : undefined}
      flat={NATIVE_ROTATION}
      anchor={{ x: 0.5, y: 0.5 }}
      zIndex={7}
      onPress={onPress}
      accessibilityLabel={label}
    >
      <View style={NATIVE_ROTATION ? undefined : { transform: [{ rotate: `${Math.round(heading)}deg` }] }}>
        <TractorGlyph type={type} paused={paused} size={40} />
      </View>
    </SnapMarker>
  );
});

export type OperationState = 'RUNNING' | 'PAUSED';

const STATE_STYLE: Record<OperationState, { label: string; color: string }> = {
  RUNNING: { label: 'Working', color: colors.leaf },
  PAUSED: { label: 'Paused', color: colors.amber },
};

/** The status card of an active parcel: operation, percent done, time left and state. */
export function OperationCard({ title, machine, percent, remaining, state }: { title: string; machine: string; percent: number; remaining: string; state: OperationState }) {
  const style = STATE_STYLE[state];
  return (
    <View style={styles.card}>
      <View style={styles.cardHead}>
        <MaterialCommunityIcons name="tractor-variant" size={14} color={style.color} />
        <Txt variant="small" color={colors.chaff} numberOfLines={1} style={styles.cardTitle}>{title}</Txt>
      </View>
      <View style={styles.cardFigures}>
        <Txt variant="figure" style={styles.cardPercent}>{`${Math.floor(percent)}%`}</Txt>
        <Txt variant="small" style={styles.cardRemaining} numberOfLines={1}>{remaining}</Txt>
      </View>
      <View style={styles.cardTrack}>
        <View style={[styles.cardFill, { width: `${Math.min(100, percent)}%`, backgroundColor: style.color }]} />
      </View>
      <View style={styles.cardHead}>
        <View style={[styles.stateDot, { backgroundColor: style.color }]} />
        <Txt variant="small" style={styles.cardMeta} numberOfLines={1}>{`${style.label}, ${machine}`}</Txt>
      </View>
    </View>
  );
}

/**
 * One machine working one parcel: it drives the planned passes at the speed
 * of the operation, paints the ground it has worked and reports its progress.
 * Each overlay keeps its own clock, so several machines animate independently
 * and the rest of the map does not re-render on every frame.
 */
export function WorkOperationOverlay({
  parcel,
  work,
  showCard,
  onPress,
}: {
  parcel: Parcel;
  work: ActiveWork;
  showCard: boolean;
  onPress: () => void;
}) {
  const live = useOperations((state) => state.live);
  const plan = useFieldPlan(parcel, work.workingWidthM);
  // The clocks stop once the run is due, so a finished machine costs nothing.
  const running = work.status === 'RUNNING' && !runProgressAt(work, new Date()).finished;
  const frame = useNow(live ? LIVE_FRAME_MS : IDLE_FRAME_MS, running);
  const coverageTick = useNow(live ? LIVE_COVERAGE_MS : IDLE_COVERAGE_MS, running);

  // Once the clocks stop, progress is read at the current moment, never at a stale tick.
  const at = running ? frame : new Date();
  const progress = runProgressAt(work, at);
  const finished = progress.finished || progress.percent >= 100;
  const percent = Math.min(100, progress.percent);
  const state: OperationState = work.status === 'PAUSED' ? 'PAUSED' : 'RUNNING';
  const typeLabel = WORK_TYPES[work.type] ?? 'Operation';
  const machineName = work.machine?.name ?? 'Machine';

  // The ground is repainted on a slower clock than the machine moves.
  const coveragePercent = runProgressAt(work, running ? coverageTick : at).percent;
  const coverage = useMemo(
    () => (plan && !finished ? fieldCoverageAt(plan, coveragePercent, 0) : null),
    // Repainting in steps of 0.1% keeps the polygons stable between ticks.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [plan, finished, Math.floor(coveragePercent * 10)],
  );

  // A real GPS fix is used only when it is on this parcel. A phone left at the
  // farm yard, or the manager's own phone, must not pull the tractor off the field.
  const ring = parcel.geometry.coordinates[0] ?? [];
  const gps = work.lastLocation;
  const gpsOnParcel =
    gps && at.getTime() - new Date(gps.recordedAt).getTime() < GPS_FRESH_MS && pointInRing([gps.lng, gps.lat], ring) ? gps : null;
  const simulated = plan ? fieldPositionAt(plan, percent) : null;
  const latitude = gpsOnParcel?.lat ?? simulated?.lat ?? parcel.centerLat;
  const longitude = gpsOnParcel?.lng ?? simulated?.lng ?? parcel.centerLng;
  const trail = useMemo(
    () => (plan && live && !finished ? toLatLng(fieldTrailAt(plan, percent, 90)) : []),
    // The trail follows the machine on every frame in Live Operations.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [plan, live, finished, Math.round(percent * 200)],
  );

  // At 100% the run is closed and everything it drew leaves the map: route,
  // worked area, machine and card. The parcel then shows its plan status.
  useEffect(() => {
    if (finished) finishOperation(work, parcel);
  }, [finished, work, parcel]);
  if (finished) return null;

  const remaining = state === 'PAUSED' ? 'Paused' : `${hoursMinutes(progress.remainingSeconds)} left`;
  const center = { latitude: parcel.centerLat, longitude: parcel.centerLng };

  return (
    <>
      <WorkingGlow parcel={parcel} />
      {coverage ? <CoverageLayer worked={coverage.worked} rows={coverage.rows} type={work.type} /> : null}
      {trail.length > 1 ? <Polyline coordinates={trail} strokeColor="#FFFFFFAA" strokeWidth={2} lineDashPattern={[2, 4]} zIndex={6} /> : null}
      <MachineMarker
        latitude={latitude}
        longitude={longitude}
        heading={simulated?.headingDeg ?? 0}
        type={work.type}
        paused={state === 'PAUSED'}
        label={`${machineName}, ${typeLabel}, ${Math.floor(percent)}% done`}
        onPress={onPress}
      />
      {showCard ? (
        <SnapMarker
          version={`${Math.floor(percent)}-${state}-${Math.round(progress.remainingSeconds / 60)}`}
          coordinate={center}
          // Hangs under the parcel centre, so it does not cover the parcel card above it.
          anchor={{ x: 0.5, y: -0.1 }}
          zIndex={8}
          onPress={onPress}
          accessibilityLabel={`${typeLabel}, ${Math.floor(percent)}%, ${remaining}, ${STATE_STYLE[state].label}`}
        >
          <OperationCard title={typeLabel} machine={machineName} percent={percent} remaining={remaining} state={state} />
        </SnapMarker>
      ) : null}
    </>
  );
}

const styles = StyleSheet.create({
  card: {
    width: 146,
    padding: space.sm,
    gap: 3,
    borderRadius: 10,
    borderWidth: 1,
    borderColor: `${WORKED_GREEN}88`,
    backgroundColor: 'rgba(20,23,16,0.92)',
  },
  cardHead: { flexDirection: 'row', alignItems: 'center', gap: 5 },
  cardTitle: { flex: 1, fontFamily: fonts.semibold, fontSize: 12.5, lineHeight: 16 },
  cardFigures: { flexDirection: 'row', alignItems: 'baseline', gap: 6 },
  cardPercent: { fontSize: 22, lineHeight: 26 },
  cardRemaining: { flex: 1, fontSize: 11.5, lineHeight: 14 },
  cardTrack: { height: 4, borderRadius: 2, backgroundColor: colors.ridge, overflow: 'hidden' },
  cardFill: { height: '100%', borderRadius: 2 },
  stateDot: { width: 7, height: 7, borderRadius: 4 },
  cardMeta: { flex: 1, fontSize: 11, lineHeight: 14 },
});
