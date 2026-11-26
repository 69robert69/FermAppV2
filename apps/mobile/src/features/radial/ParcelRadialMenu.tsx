import { MaterialCommunityIcons } from '@expo/vector-icons';
import { memo, useEffect, useMemo, useState } from 'react';
import { Pressable, StyleSheet, useWindowDimensions, View, type LayoutChangeEvent } from 'react-native';
import Animated, {
  Easing,
  useAnimatedReaction,
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withDelay,
  withSequence,
  withSpring,
  withTiming,
  type SharedValue,
} from 'react-native-reanimated';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Svg, { Circle, Defs, LinearGradient, Path, RadialGradient, Stop } from 'react-native-svg';
import { scheduleOnRN } from 'react-native-worklets';
import { Txt } from '../../components/ui';
import { ha } from '../../lib/format';
import { colors, fonts } from '../../theme';
import { clampCenter, shortestSlotDelta, slotAngle, wedgePath, type RadialLayout } from './geometry';
import { resolveIcon } from './registry';
import { useRadialStore } from './store';
import type { BadgeTone, ParcelSnapshot, RadialAction, RadialBadge } from './types';
import type { ParcelRadialController } from './useParcelRadial';

const SPRING = { damping: 17, stiffness: 230, mass: 0.75 } as const;
const STAGGER_MS = 22;
const EXIT_MS = 150;

const TONES: Record<BadgeTone, string> = {
  info: colors.water,
  success: colors.leaf,
  warning: colors.amber,
  danger: colors.rust,
};

/** The wheel over the map: glass disc, staggered buttons and a focus wedge that follows the finger. */
export function ParcelRadialMenu({ controller }: { controller: ParcelRadialController }) {
  const { menu, snapshot, actions, layout, hovered, select, close } = controller;
  const phase = useRadialStore((state) => state.phase);
  const insets = useSafeAreaInsets();
  const window = useWindowDimensions();
  const [size, setSize] = useState({ width: window.width, height: window.height });
  const onLayout = (event: LayoutChangeEvent) => {
    const { width, height } = event.nativeEvent.layout;
    setSize((current) => (current.width === width && current.height === height ? current : { width, height }));
  };

  return (
    <View pointerEvents={menu ? 'auto' : 'none'} style={StyleSheet.absoluteFill} onLayout={onLayout}>
      {menu && snapshot && actions.length > 0 ? (
        <Wheel
          key={menu.token}
          snapshot={snapshot}
          actions={actions}
          layout={layout}
          hovered={hovered}
          closing={phase === 'closing'}
          center={clampCenter(menu.originX, menu.originY, layout.discRadius, {
            width: size.width,
            height: size.height,
            top: insets.top,
            bottom: 0,
          })}
          origin={{ x: menu.originX, y: menu.originY }}
          size={size}
          onSelect={select}
          onClose={close}
        />
      ) : null}
    </View>
  );
}

interface WheelProps {
  snapshot: ParcelSnapshot;
  actions: RadialAction[];
  layout: RadialLayout;
  hovered: SharedValue<number>;
  closing: boolean;
  center: { x: number; y: number };
  origin: { x: number; y: number };
  size: { width: number; height: number };
  onSelect: (index: number) => void;
  onClose: () => void;
}

function Wheel({ snapshot, actions, layout, hovered, closing, center, origin, size, onSelect, onClose }: WheelProps) {
  const reduced = useReducedMotion();
  const progress = useSharedValue(0);
  const pulse = useSharedValue(0);
  const wedgeTurn = useSharedValue(0);
  const wedgeOpacity = useSharedValue(0);
  const [focused, setFocused] = useState(-1);
  const count = actions.length;
  const { discRadius: R, hubRadius, itemRadius, button } = layout;
  const finishClose = useRadialStore((state) => state.finishClose);

  useEffect(() => {
    progress.value = reduced ? withTiming(1, { duration: 120 }) : withSpring(1, SPRING);
    pulse.value = reduced ? 1 : withTiming(1, { duration: 650, easing: Easing.out(Easing.cubic) });
  }, [progress, pulse, reduced]);

  useEffect(() => {
    if (!closing) return;
    progress.value = withTiming(0, { duration: EXIT_MS, easing: Easing.in(Easing.quad) }, (finished) => {
      if (finished) scheduleOnRN(finishClose);
    });
  }, [closing, progress, finishClose]);

  // The wedge turns the short way round to the focused slice, and the hub text follows it.
  useAnimatedReaction(
    () => hovered.value,
    (next, previous) => {
      if (next === previous) return;
      if (next >= 0) {
        const from = previous !== null && previous >= 0 ? previous : next;
        const target = previous !== null && previous >= 0 ? wedgeTurn.value + shortestSlotDelta(from, next, count) : next;
        wedgeTurn.value = previous !== null && previous >= 0 && !reduced ? withSpring(target, SPRING) : target;
        wedgeOpacity.value = withTiming(1, { duration: 90 });
      } else {
        wedgeOpacity.value = withTiming(0, { duration: 120 });
      }
      scheduleOnRN(setFocused, next);
    },
    [count, reduced],
  );

  const backdropStyle = useAnimatedStyle(() => ({ opacity: progress.value }));
  const discStyle = useAnimatedStyle(() => ({
    opacity: Math.min(1, progress.value * 1.4),
    transform: [
      // The disc grows out of the point that was pressed, then settles on its clamped centre.
      { translateX: (origin.x - center.x) * (1 - progress.value) },
      { translateY: (origin.y - center.y) * (1 - progress.value) },
      { scale: 0.35 + 0.65 * progress.value },
    ],
  }));
  const wedgeStyle = useAnimatedStyle(() => ({
    opacity: wedgeOpacity.value,
    transform: [{ rotate: `${(wedgeTurn.value * 360) / Math.max(count, 1)}deg` }],
  }));
  const pulseStyle = useAnimatedStyle(() => ({
    opacity: 0.55 * (1 - pulse.value),
    transform: [{ scale: 0.5 + pulse.value * 1.1 }],
  }));

  const focusedAction = focused >= 0 ? actions[focused] : undefined;
  const wedge = useMemo(() => wedgePath(R, hubRadius + 6, R - 4, count), [R, hubRadius, count]);
  const alerts = alertCount(snapshot);

  return (
    <>
      {/* A spotlight darkens the map around the wheel and keeps the parcel readable. */}
      <Animated.View style={[StyleSheet.absoluteFill, backdropStyle]}>
        <Pressable accessibilityRole="button" accessibilityLabel="Close parcel actions" style={StyleSheet.absoluteFill} onPress={onClose}>
          <Svg width={size.width} height={size.height}>
            <Defs>
              <RadialGradient id="spot" cx={center.x} cy={center.y} r={Math.max(size.width, size.height) * 0.75} gradientUnits="userSpaceOnUse">
                <Stop offset="0" stopColor="#0B0D08" stopOpacity="0.05" />
                <Stop offset={String(Math.min(0.9, (R * 1.15) / Math.max(size.width, size.height)))} stopColor="#0B0D08" stopOpacity="0.35" />
                <Stop offset="1" stopColor="#0B0D08" stopOpacity="0.72" />
              </RadialGradient>
            </Defs>
            <Path d={`M0 0H${size.width}V${size.height}H0Z`} fill="url(#spot)" />
          </Svg>
        </Pressable>
      </Animated.View>

      <Animated.View pointerEvents="none" style={[styles.pulse, { left: origin.x - 60, top: origin.y - 60 }, pulseStyle]} />

      <Animated.View
        pointerEvents="box-none"
        style={[{ position: 'absolute', left: center.x - R, top: center.y - R, width: R * 2, height: R * 2 }, discStyle]}
      >
        <GlassDisc radius={R} hubRadius={hubRadius} />

        <Animated.View pointerEvents="none" style={[StyleSheet.absoluteFill, wedgeStyle]}>
          <Svg width={R * 2} height={R * 2}>
            <Path d={wedge} fill={focusedAction?.accent ?? colors.wheat} fillOpacity={0.2} stroke={focusedAction?.accent ?? colors.wheat} strokeOpacity={0.55} strokeWidth={1.2} />
          </Svg>
        </Animated.View>

        {actions.map((action, index) => (
          <RadialItem
            key={action.id}
            action={action}
            snapshot={snapshot}
            index={index}
            count={count}
            center={R}
            itemRadius={itemRadius}
            button={button}
            hovered={hovered}
            reduced={reduced}
            onPress={onSelect}
          />
        ))}

        <Hub
          radius={hubRadius}
          center={R}
          snapshot={snapshot}
          focused={focusedAction}
          alerts={alerts}
          onClose={onClose}
        />
      </Animated.View>
    </>
  );
}

const GlassDisc = memo(function GlassDisc({ radius: R, hubRadius }: { radius: number; hubRadius: number }) {
  return (
    <View pointerEvents="none" style={[StyleSheet.absoluteFill, styles.discShadow, { borderRadius: R }]}>
      <Svg width={R * 2} height={R * 2}>
        <Defs>
          <LinearGradient id="glass" x1="0" y1="0" x2="0" y2="1">
            <Stop offset="0" stopColor="#3A4030" stopOpacity="0.80" />
            <Stop offset="0.55" stopColor="#1E2218" stopOpacity="0.84" />
            <Stop offset="1" stopColor="#12150D" stopOpacity="0.90" />
          </LinearGradient>
          <LinearGradient id="sheen" x1="0" y1="0" x2="0" y2="1">
            <Stop offset="0" stopColor="#FFFFFF" stopOpacity="0.16" />
            <Stop offset="0.45" stopColor="#FFFFFF" stopOpacity="0" />
          </LinearGradient>
          <RadialGradient id="hub" cx="50%" cy="40%" r="60%">
            <Stop offset="0" stopColor="#2F3526" stopOpacity="0.98" />
            <Stop offset="1" stopColor="#14170F" stopOpacity="0.98" />
          </RadialGradient>
        </Defs>
        <Circle cx={R} cy={R} r={R - 1} fill="url(#glass)" stroke={colors.chaff} strokeOpacity={0.16} strokeWidth={1} />
        <Circle cx={R} cy={R} r={R - 3} fill="url(#sheen)" />
        <Circle cx={R} cy={R} r={R - 10} fill="none" stroke={colors.chaff} strokeOpacity={0.05} strokeWidth={1} />
        <Circle cx={R} cy={R} r={hubRadius + 4} fill="none" stroke={colors.wheat} strokeOpacity={0.22} strokeWidth={1} strokeDasharray="2 5" />
        <Circle cx={R} cy={R} r={hubRadius} fill="url(#hub)" stroke={colors.chaff} strokeOpacity={0.18} strokeWidth={1} />
      </Svg>
    </View>
  );
});

interface ItemProps {
  action: RadialAction;
  snapshot: ParcelSnapshot;
  index: number;
  count: number;
  center: number;
  itemRadius: number;
  button: number;
  hovered: SharedValue<number>;
  reduced: boolean;
  onPress: (index: number) => void;
}

const RadialItem = memo(function RadialItem({ action, snapshot, index, count, center, itemRadius, button, hovered, reduced, onPress }: ItemProps) {
  const appear = useSharedValue(0);
  const focus = useSharedValue(0);
  const angle = slotAngle(index, count);
  const dx = Math.cos(angle) * itemRadius;
  const dy = Math.sin(angle) * itemRadius;
  const badge = action.badge?.(snapshot) ?? null;
  const icon = resolveIcon(action, snapshot);

  useEffect(() => {
    appear.value = reduced ? withTiming(1, { duration: 120 }) : withDelay(30 + index * STAGGER_MS, withSpring(1, SPRING));
  }, [appear, index, reduced]);

  useAnimatedReaction(
    () => hovered.value === index,
    (active, previous) => {
      if (active === previous) return;
      focus.value = active ? withSequence(withTiming(1.12, { duration: 90 }), withSpring(1, SPRING)) : withTiming(0, { duration: 140 });
    },
    [index],
  );

  const wrapStyle = useAnimatedStyle(() => ({
    opacity: appear.value,
    transform: [
      { translateX: dx * appear.value },
      { translateY: dy * appear.value },
      { scale: (0.3 + 0.7 * appear.value) * (1 + 0.16 * focus.value) },
    ],
  }));
  const glowStyle = useAnimatedStyle(() => ({ opacity: Math.min(1, focus.value), transform: [{ scale: 1 + 0.12 * focus.value }] }));

  const half = button / 2;
  return (
    <Animated.View style={[styles.item, { left: center - half, top: center - half, width: button }, wrapStyle]}>
      <Animated.View pointerEvents="none" style={[styles.glow, { width: button + 14, height: button + 14, borderRadius: half + 7, left: -7, top: -7, borderColor: action.accent, shadowColor: action.accent }, glowStyle]} />
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={`${action.label}${badge ? `, ${badge.label}` : ''}`}
        hitSlop={6}
        onPressIn={() => {
          hovered.value = index;
        }}
        onPressOut={() => {
          if (hovered.value === index) hovered.value = -1;
        }}
        onPress={() => onPress(index)}
        style={({ pressed }) => [
          styles.button,
          { width: button, height: button, borderRadius: half, borderColor: `${action.accent}66` },
          pressed && { backgroundColor: `${action.accent}33` },
        ]}
      >
        <MaterialCommunityIcons name={icon} size={button * 0.44} color={action.accent} />
      </Pressable>
      {badge ? <Badge badge={badge} /> : null}
      <Txt variant="small" color={colors.chaff} numberOfLines={1} style={styles.itemLabel}>
        {action.shortLabel}
      </Txt>
    </Animated.View>
  );
});

function Badge({ badge }: { badge: RadialBadge }) {
  return (
    <View pointerEvents="none" style={[styles.badge, { backgroundColor: TONES[badge.tone] }]}>
      <Txt variant="small" color={colors.loam} style={styles.badgeText}>{badge.label}</Txt>
    </View>
  );
}

function Hub({
  radius,
  center,
  snapshot,
  focused,
  alerts,
  onClose,
}: {
  radius: number;
  center: number;
  snapshot: ParcelSnapshot;
  focused: RadialAction | undefined;
  alerts: number;
  onClose: () => void;
}) {
  const { parcel, info } = snapshot;
  const hint = focused?.hint?.(snapshot) ?? null;
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={`Close actions for parcel ${parcel.name}`}
      onPress={onClose}
      style={[styles.hub, { left: center - radius, top: center - radius, width: radius * 2, height: radius * 2, borderRadius: radius }]}
    >
      {alerts > 0 ? (
        <View style={styles.alert}>
          <MaterialCommunityIcons name="alert" size={10} color={colors.loam} />
          <Txt variant="small" color={colors.loam} style={styles.badgeText}>{alerts}</Txt>
        </View>
      ) : null}
      {focused ? (
        <>
          <Txt variant="small" color={focused.accent} numberOfLines={2} style={styles.hubTitle}>{focused.label}</Txt>
          {hint ? <Txt variant="small" numberOfLines={2} style={styles.hubHint}>{hint}</Txt> : null}
        </>
      ) : (
        <>
          <Txt variant="small" color={colors.chaff} numberOfLines={1} style={styles.hubTitle}>{parcel.name}</Txt>
          <Txt variant="small" numberOfLines={1} style={styles.hubHint}>{ha(parcel.areaHa)}</Txt>
          {info?.crop ? <Txt variant="small" numberOfLines={1} style={styles.hubHint}>{info.crop.name}</Txt> : null}
          {snapshot.favorite ? <MaterialCommunityIcons name="star" size={12} color={colors.wheat} /> : null}
        </>
      )}
    </Pressable>
  );
}

/** Things that need attention: overdue tasks, a paused irrigation and poor crop health. */
export function alertCount(snapshot: ParcelSnapshot) {
  const paused = snapshot.info?.irrigation.filter((run) => run.status === 'PAUSED').length ?? 0;
  const health = snapshot.parcel.satellite?.[0]?.healthClass === 'PROBLEM' ? 1 : 0;
  return snapshot.overdueTasks + paused + health;
}

const styles = StyleSheet.create({
  pulse: { position: 'absolute', width: 120, height: 120, borderRadius: 60, borderWidth: 2, borderColor: colors.wheat },
  discShadow: {
    shadowColor: '#000',
    shadowOpacity: 0.55,
    shadowRadius: 24,
    shadowOffset: { width: 0, height: 12 },
    elevation: 18,
  },
  item: { position: 'absolute', alignItems: 'center' },
  glow: {
    position: 'absolute',
    borderWidth: 2,
    backgroundColor: 'rgba(255,255,255,0.04)',
    shadowOpacity: 0.9,
    shadowRadius: 12,
    shadowOffset: { width: 0, height: 0 },
  },
  button: {
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 1,
    backgroundColor: 'rgba(16,19,12,0.78)',
    shadowColor: '#000',
    shadowOpacity: 0.4,
    shadowRadius: 6,
    shadowOffset: { width: 0, height: 3 },
    elevation: 6,
  },
  itemLabel: {
    position: 'absolute',
    top: '100%',
    marginTop: 3,
    width: 84,
    textAlign: 'center',
    fontFamily: fonts.semibold,
    fontSize: 10.5,
    lineHeight: 13,
    textShadowColor: 'rgba(0,0,0,0.85)',
    textShadowRadius: 4,
  },
  badge: {
    position: 'absolute',
    top: -4,
    right: -6,
    minWidth: 20,
    height: 20,
    paddingHorizontal: 5,
    borderRadius: 10,
    borderWidth: 2,
    borderColor: colors.loam,
    alignItems: 'center',
    justifyContent: 'center',
  },
  badgeText: { fontFamily: fonts.bold, fontSize: 10, lineHeight: 12 },
  hub: { position: 'absolute', alignItems: 'center', justifyContent: 'center', paddingHorizontal: 8 },
  hubTitle: { fontFamily: fonts.semibold, fontSize: 12, lineHeight: 15, textAlign: 'center' },
  hubHint: { fontSize: 10.5, lineHeight: 13, textAlign: 'center' },
  alert: {
    position: 'absolute',
    top: -9,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 2,
    paddingHorizontal: 6,
    height: 18,
    borderRadius: 9,
    backgroundColor: colors.rust,
    borderWidth: 2,
    borderColor: colors.loam,
  },
});

