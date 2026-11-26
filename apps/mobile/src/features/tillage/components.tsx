import { MaterialCommunityIcons } from '@expo/vector-icons';
import {
  soilHealthLabel,
  TILLAGE_LIST,
  TILLAGE_SYSTEMS,
  type CompatibilityCheck,
  type TillageHistoryEntry,
  type TillageSystem,
} from '@fermapp/domain';
import { useEffect } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';
import Animated, { useAnimatedStyle, useSharedValue, withDelay, withTiming } from 'react-native-reanimated';
import { Txt } from '../../components/ui';
import { colors, fonts, space } from '../../theme';

/** Coloured map and list badge. The text always names the technology, so colour is never the only cue. */
export function TillageBadge({ system, size = 'md' }: { system: TillageSystem | null | undefined; size?: 'sm' | 'md' }) {
  if (!system) return null;
  const profile = TILLAGE_SYSTEMS[system];
  const small = size === 'sm';
  return (
    <View
      accessibilityLabel={`Tillage: ${profile.name}`}
      style={[styles.badge, small && styles.badgeSmall, { borderColor: profile.color, backgroundColor: `${profile.color}2E` }]}
    >
      <View style={[styles.dot, small && styles.dotSmall, { backgroundColor: profile.color }]} />
      <Txt variant="small" color={colors.chaff} style={[styles.badgeText, small && styles.badgeTextSmall]}>{profile.badge}</Txt>
    </View>
  );
}

/** Soil health score as a number, a gradient bar and a sentence. */
export function SoilHealthMeter({ score, compact }: { score: number; compact?: boolean }) {
  const fill = useSharedValue(0);
  useEffect(() => {
    fill.value = withDelay(80, withTiming(score, { duration: 700 }));
  }, [fill, score]);
  const style = useAnimatedStyle(() => ({ width: `${fill.value}%` }));
  const reading = soilHealthLabel(score);
  const tint = reading.level === 'excellent' || reading.level === 'good' ? colors.leaf : reading.level === 'fair' ? colors.amber : colors.rust;
  return (
    <View style={{ gap: space.xs }} accessibilityLabel={`Soil health score ${score} of 100. ${reading.label}`}>
      <View style={styles.scoreRow}>
        <Txt variant="small">Soil Health Score</Txt>
        <Txt variant="figure" style={compact ? styles.figureSmall : undefined}>
          {score}
          <Txt variant="small">/100</Txt>
        </Txt>
      </View>
      <View style={styles.meterTrack}>
        {/* The track is split in score bands so the position reads without colour. */}
        {[55, 70, 85].map((mark) => (
          <View key={mark} style={[styles.meterMark, { left: `${mark}%` }]} />
        ))}
        <Animated.View style={[styles.meterFill, { backgroundColor: tint }, style]} />
      </View>
      {!compact ? (
        <View style={styles.reading}>
          <MaterialCommunityIcons name={reading.level === 'poor' ? 'alert-outline' : 'sprout-outline'} size={14} color={tint} />
          <Txt variant="small" style={{ flex: 1 }}>{reading.label}</Txt>
        </View>
      ) : null}
    </View>
  );
}

export function WaterStars({ value, size = 16 }: { value: number; size?: number }) {
  return (
    <View style={styles.stars} accessibilityLabel={`Water retention ${value} of 5`}>
      {[1, 2, 3, 4, 5].map((index) => (
        <MaterialCommunityIcons key={index} name={index <= Math.round(value) ? 'star' : 'star-outline'} size={size} color={index <= Math.round(value) ? colors.water : colors.line} />
      ))}
    </View>
  );
}

/** Technology picker, one card per technology. */
export function TillagePicker({ value, onChange, disabled }: { value: TillageSystem | null; onChange: (system: TillageSystem) => void; disabled?: boolean }) {
  return (
    <View style={styles.picker}>
      {TILLAGE_LIST.map((profile) => {
        const selected = profile.code === value;
        return (
          <Pressable
            key={profile.code}
            disabled={disabled}
            accessibilityRole="radio"
            accessibilityState={{ selected, disabled }}
            accessibilityLabel={profile.name}
            onPress={() => onChange(profile.code)}
            style={[styles.option, selected && { borderColor: profile.color, backgroundColor: `${profile.color}24` }]}
          >
            <View style={[styles.dot, { backgroundColor: profile.color }]} />
            <Txt variant="small" color={colors.chaff} numberOfLines={1} style={styles.optionText}>{profile.name}</Txt>
            {selected ? <MaterialCommunityIcons name="check-circle" size={16} color={profile.color} /> : null}
          </Pressable>
        );
      })}
    </View>
  );
}

/** A warning raised by a compatibility check, or nothing. */
export function CompatibilityNotice({ check }: { check: CompatibilityCheck }) {
  if (check.compatible || !check.message) return null;
  return (
    <View style={styles.notice} accessibilityRole="alert">
      <MaterialCommunityIcons name="alert-outline" size={18} color={colors.amber} />
      <Txt variant="small" color={colors.chaff} style={{ flex: 1 }}>{check.message}</Txt>
    </View>
  );
}

/** Year by year history, as a horizontal timeline. */
export function TillageTimeline({ history }: { history: readonly TillageHistoryEntry[] }) {
  if (history.length === 0) return <Txt variant="small">No history yet. The technology you set is recorded for its year.</Txt>;
  const thisYear = new Date().getFullYear();
  return (
    <View style={styles.timeline}>
      {history.map((entry, index) => {
        const profile = TILLAGE_SYSTEMS[entry.system];
        const future = entry.year > thisYear;
        return (
          <View key={entry.year} style={styles.step}>
            <View style={styles.stepLine}>
              <View style={[styles.connector, index === 0 && { opacity: 0 }]} />
              <View style={[styles.node, { borderColor: profile.color, backgroundColor: future ? colors.loam : profile.color }]} />
              <View style={[styles.connector, index === history.length - 1 && { opacity: 0 }]} />
            </View>
            <Txt variant="small" color={colors.chaff} style={styles.stepYear}>{String(entry.year)}</Txt>
            <Txt variant="small" numberOfLines={1} style={styles.stepLabel}>{future ? `${profile.badge} (plan)` : profile.badge}</Txt>
          </View>
        );
      })}
    </View>
  );
}

const styles = StyleSheet.create({
  badge: { flexDirection: 'row', alignItems: 'center', gap: 5, alignSelf: 'flex-start', paddingHorizontal: 8, height: 24, borderRadius: 12, borderWidth: 1 },
  badgeSmall: { height: 18, paddingHorizontal: 6, gap: 4, borderRadius: 9 },
  badgeText: { fontFamily: fonts.bold, fontSize: 11, lineHeight: 14, letterSpacing: 0.4 },
  badgeTextSmall: { fontSize: 9.5, lineHeight: 12 },
  dot: { width: 8, height: 8, borderRadius: 4 },
  dotSmall: { width: 6, height: 6, borderRadius: 3 },
  scoreRow: { flexDirection: 'row', alignItems: 'flex-end', justifyContent: 'space-between' },
  figureSmall: { fontSize: 22, lineHeight: 26 },
  meterTrack: { height: 10, borderRadius: 5, backgroundColor: colors.ridge, overflow: 'hidden' },
  meterFill: { height: '100%', borderRadius: 5 },
  meterMark: { position: 'absolute', top: 0, bottom: 0, width: 2, backgroundColor: colors.loam, zIndex: 1 },
  reading: { flexDirection: 'row', alignItems: 'center', gap: space.xs },
  stars: { flexDirection: 'row', gap: 2 },
  picker: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  option: {
    width: '48.5%',
    flexDirection: 'row',
    alignItems: 'center',
    gap: space.sm,
    minHeight: 48,
    paddingHorizontal: space.md,
    borderRadius: 10,
    borderWidth: 1,
    borderColor: colors.line,
    backgroundColor: colors.furrow,
  },
  optionText: { flex: 1, fontFamily: fonts.semibold, fontSize: 13 },
  notice: {
    flexDirection: 'row',
    gap: space.sm,
    alignItems: 'flex-start',
    padding: space.md,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: `${colors.amber}66`,
    backgroundColor: `${colors.amber}1A`,
  },
  timeline: { flexDirection: 'row' },
  step: { flex: 1, alignItems: 'center', gap: 2 },
  stepLine: { flexDirection: 'row', alignItems: 'center', alignSelf: 'stretch' },
  connector: { flex: 1, height: 2, backgroundColor: colors.line },
  node: { width: 14, height: 14, borderRadius: 7, borderWidth: 2 },
  stepYear: { fontFamily: fonts.semibold, fontSize: 12 },
  stepLabel: { fontSize: 10.5, lineHeight: 13 },
});

/** Toggle chips for the technologies a machine suits. */
export function TillageChips({ value, onToggle, disabled }: { value: readonly TillageSystem[]; onToggle: (system: TillageSystem) => void; disabled?: boolean }) {
  return (
    <View style={chipStyles.row}>
      {TILLAGE_LIST.map((profile) => {
        const on = value.includes(profile.code);
        return (
          <Pressable
            key={profile.code}
            disabled={disabled}
            accessibilityRole="checkbox"
            accessibilityState={{ checked: on, disabled }}
            accessibilityLabel={profile.name}
            hitSlop={4}
            onPress={() => onToggle(profile.code)}
            style={[chipStyles.chip, on ? { borderColor: profile.color, backgroundColor: `${profile.color}2E` } : null]}
          >
            <MaterialCommunityIcons name={on ? 'check' : 'close'} size={13} color={on ? colors.chaff : colors.straw} />
            <Txt variant="small" color={on ? colors.chaff : colors.straw} style={chipStyles.text}>{profile.badge}</Txt>
          </Pressable>
        );
      })}
    </View>
  );
}

const chipStyles = StyleSheet.create({
  row: { flexDirection: 'row', flexWrap: 'wrap', gap: space.xs },
  chip: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
    minHeight: 32,
    paddingHorizontal: 10,
    borderRadius: 16,
    borderWidth: 1,
    borderColor: colors.line,
  },
  text: { fontFamily: fonts.semibold, fontSize: 11.5 },
});
