import { MaterialCommunityIcons } from '@expo/vector-icons';
import { workTiming } from '@fermapp/domain';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';
import type { Parcel } from '../../api/types';
import { Txt } from '../../components/ui';
import { hoursMinutes, number, WORK_TYPES } from '../../lib/format';
import { runProgressAt, useNow } from '../../lib/hooks';
import { colors, fonts, space } from '../../theme';
import type { ActiveWork } from '../intelligence/api';
import type { MapParcelInfo } from '../map/api';
import { isRunActive } from './finish';
import { TractorGlyph } from './TractorGlyph';

interface Item {
  work: ActiveWork;
  parcel: Parcel;
}

function FleetCard({ item, now, onPress }: { item: Item; now: Date; onPress: () => void }) {
  const { work, parcel } = item;
  const progress = runProgressAt(work, now);
  const paused = work.status === 'PAUSED';
  const timing = workTiming(work.areaHa, work.speedKmh, work.workingWidthM);
  const state = paused ? { label: 'Paused', color: colors.amber } : { label: 'Working', color: colors.leaf };
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={`${work.machine?.name ?? 'Machine'}, ${WORK_TYPES[work.type]} on ${parcel.name}, ${Math.floor(progress.percent)}%`}
      onPress={onPress}
      style={({ pressed }) => [styles.card, pressed && { borderColor: colors.wheat }]}
    >
      <View style={styles.row}>
        <View style={styles.glyph}>
          <TractorGlyph type={work.type} paused={paused} size={34} />
        </View>
        <View style={{ flex: 1 }}>
          <Txt variant="small" color={colors.chaff} numberOfLines={1} style={styles.title}>{work.machine?.name ?? 'Machine'}</Txt>
          <Txt variant="small" numberOfLines={1}>{`${WORK_TYPES[work.type] ?? work.type}, ${parcel.name}`}</Txt>
        </View>
        <Txt variant="figure" style={styles.percent}>{`${Math.floor(progress.percent)}%`}</Txt>
      </View>
      <View style={styles.track}>
        <View style={[styles.fill, { width: `${Math.min(100, progress.percent)}%`, backgroundColor: state.color }]} />
      </View>
      <View style={styles.row}>
        <View style={[styles.dot, { backgroundColor: state.color }]} />
        <Txt variant="small" style={styles.meta}>{paused ? 'Paused' : `${hoursMinutes(progress.remainingSeconds)} left`}</Txt>
        <Txt variant="small" style={styles.meta}>{`${number(work.speedKmh, 0)} km/h, ${number(work.workingWidthM)} m`}</Txt>
      </View>
      <Txt variant="small" style={styles.meta}>{`${number(work.areaHa)} ha, ${number(timing.capacityHaH, 1)} ha/h, ${hoursMinutes(work.durationHours * 3600)} in total`}</Txt>
    </Pressable>
  );
}

/** The fleet at a glance: who works where, how far along and how long is left. */
export function LiveOperationsPanel({
  parcels,
  overview,
  bottom,
  onFocus,
  onClose,
}: {
  parcels: Parcel[];
  overview: MapParcelInfo[];
  bottom: number;
  onFocus: (parcel: Parcel) => void;
  onClose: () => void;
}) {
  const now = useNow(1000);
  const byId = new Map(parcels.map((parcel) => [parcel.id, parcel]));
  const items: Item[] = overview.flatMap((info) => {
    const parcel = byId.get(info.parcelId);
    // A machine that reached 100% is available again and leaves the fleet panel at once.
    return parcel ? info.work.filter((work) => isRunActive(work, now)).map((work) => ({ work, parcel })) : [];
  });
  const active = items.length;

  return (
    <View style={[styles.panel, { bottom }]}>
      <View style={styles.head}>
        <View style={styles.liveDot} />
        <Txt variant="subtitle" style={{ flex: 1 }}>Live Operations</Txt>
        <Txt variant="small">{`${active} working`}</Txt>
        <Pressable accessibilityRole="button" accessibilityLabel="Close Live Operations" hitSlop={10} onPress={onClose} style={styles.close}>
          <MaterialCommunityIcons name="close" size={20} color={colors.straw} />
        </Pressable>
      </View>
      {items.length === 0 ? (
        <Txt variant="small">No machine is working right now. Start an operation from Work or from the parcel wheel.</Txt>
      ) : (
        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.list}>
          {items.map((item) => (
            <FleetCard key={item.work.id} item={item} now={now} onPress={() => onFocus(item.parcel)} />
          ))}
        </ScrollView>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  panel: {
    position: 'absolute',
    left: space.md,
    right: space.md,
    padding: space.md,
    gap: space.sm,
    borderRadius: 16,
    borderWidth: 1,
    borderColor: `${colors.chaff}1F`,
    backgroundColor: 'rgba(20,23,16,0.88)',
    shadowColor: '#000',
    shadowOpacity: 0.45,
    shadowRadius: 18,
    shadowOffset: { width: 0, height: 8 },
    elevation: 12,
  },
  head: { flexDirection: 'row', alignItems: 'center', gap: space.sm },
  liveDot: { width: 9, height: 9, borderRadius: 5, backgroundColor: colors.rust },
  close: { width: 36, height: 36, alignItems: 'center', justifyContent: 'center' },
  list: { gap: space.sm, paddingRight: space.sm },
  card: { width: 236, padding: space.md, gap: 6, borderRadius: 12, borderWidth: 1, borderColor: colors.line, backgroundColor: colors.furrow },
  row: { flexDirection: 'row', alignItems: 'center', gap: space.sm },
  glyph: { width: 36, height: 36, alignItems: 'center', justifyContent: 'center' },
  title: { fontFamily: fonts.semibold, fontSize: 13 },
  percent: { fontSize: 22, lineHeight: 26 },
  track: { height: 5, borderRadius: 3, backgroundColor: colors.ridge, overflow: 'hidden' },
  fill: { height: '100%', borderRadius: 3 },
  dot: { width: 7, height: 7, borderRadius: 4 },
  meta: { fontSize: 11.5, lineHeight: 15 },
});
