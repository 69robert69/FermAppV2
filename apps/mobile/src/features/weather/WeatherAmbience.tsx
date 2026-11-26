import type { ConditionKind } from '@fermapp/domain';
import { memo, useEffect } from 'react';
import { StyleSheet, View } from 'react-native';
import Animated, {
  cancelAnimation,
  Easing,
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withDelay,
  withRepeat,
  withTiming,
} from 'react-native-reanimated';
import Svg, { Defs, Path, RadialGradient, Rect, Stop } from 'react-native-svg';
import { colors } from '../../theme';

/**
 * Subtle sky behind the widget's headline: drifting clouds, falling rain or snow, a sun glow.
 * Everything runs on the UI thread and stays still when the system asks for reduced motion.
 */

const CLOUD_PATH = 'M20 45 Q5 45 5 32 Q5 20 18 19 Q22 6 37 7 Q47 0 58 8 Q72 4 78 18 Q95 19 95 32 Q95 45 80 45 Z';
const WET: ConditionKind[] = ['DRIZZLE', 'RAIN', 'HEAVY_RAIN', 'STORM', 'HAIL', 'SLEET'];
const CLOUDED: ConditionKind[] = ['PARTLY_CLOUDY', 'CLOUDY', 'FOG', ...WET, 'SNOW'];
const DROPS: Partial<Record<ConditionKind, number>> = { DRIZZLE: 6, RAIN: 10, SLEET: 9, HEAVY_RAIN: 14, STORM: 14, HAIL: 12, SNOW: 9 };

/** Fixed pseudo-random spread, so drops do not jump between renders. */
const spread = (i: number) => ((i * 37) % 100) / 100;

function Glow({ isDay, reduced }: { isDay: boolean; reduced: boolean }) {
  const pulse = useSharedValue(0);
  useEffect(() => {
    if (reduced) return;
    pulse.value = withRepeat(withTiming(1, { duration: 3600, easing: Easing.inOut(Easing.sin) }), -1, true);
    return () => cancelAnimation(pulse);
  }, [pulse, reduced]);
  const style = useAnimatedStyle(() => ({
    opacity: 0.55 + pulse.value * 0.3,
    transform: [{ scale: 1 + pulse.value * 0.12 }],
  }));
  const tint = isDay ? colors.wheat : colors.straw;
  return (
    <Animated.View style={[styles.glow, style]}>
      <Svg width="100%" height="100%" viewBox="0 0 100 100">
        <Defs>
          <RadialGradient id="sunGlow" cx="50%" cy="50%" r="50%">
            <Stop offset="0" stopColor={tint} stopOpacity={isDay ? 0.55 : 0.22} />
            <Stop offset="0.45" stopColor={tint} stopOpacity={isDay ? 0.18 : 0.08} />
            <Stop offset="1" stopColor={tint} stopOpacity={0} />
          </RadialGradient>
        </Defs>
        <Rect width="100" height="100" fill="url(#sunGlow)" />
      </Svg>
    </Animated.View>
  );
}

function Cloud({ width, top, size, duration, offset, opacity, reduced }: {
  width: number;
  top: number;
  size: number;
  duration: number;
  /** Starting point of the loop, 0 to 1. */
  offset: number;
  opacity: number;
  reduced: boolean;
}) {
  const travel = width + size;
  const x = useSharedValue(offset);
  useEffect(() => {
    if (reduced) return;
    // Finish the current pass, then loop from the left edge at constant speed.
    x.value = withTiming(1, { duration: duration * (1 - offset), easing: Easing.linear }, (finished) => {
      if (!finished) return;
      x.value = 0;
      x.value = withRepeat(withTiming(1, { duration, easing: Easing.linear }), -1, false);
    });
    return () => cancelAnimation(x);
  }, [x, duration, offset, reduced]);
  const style = useAnimatedStyle(() => ({ transform: [{ translateX: x.value * travel - size }] }));
  return (
    <Animated.View style={[{ position: 'absolute', top, left: 0, width: size, height: size / 2, opacity }, style]}>
      <Svg width="100%" height="100%" viewBox="0 0 100 50">
        <Path d={CLOUD_PATH} fill={colors.chaff} />
      </Svg>
    </Animated.View>
  );
}

function Drop({ x, height, delay, duration, flake, color, reduced }: {
  x: number;
  height: number;
  delay: number;
  duration: number;
  flake: boolean;
  color: string;
  reduced: boolean;
}) {
  const progress = useSharedValue(reduced ? spread(x) : 0);
  useEffect(() => {
    if (reduced) return;
    progress.value = withDelay(delay, withRepeat(withTiming(1, { duration, easing: Easing.linear }), -1, false));
    return () => cancelAnimation(progress);
  }, [progress, delay, duration, reduced]);
  const style = useAnimatedStyle(() => ({
    opacity: progress.value < 0.1 ? progress.value * 10 : progress.value > 0.85 ? (1 - progress.value) / 0.15 : 1,
    transform: [{ translateY: -16 + progress.value * (height + 16) }, { translateX: flake ? Math.sin(progress.value * 6.28) * 4 : -progress.value * 10 }, { rotate: flake ? '0deg' : '14deg' }],
  }));
  return <Animated.View style={[flake ? styles.flake : styles.drop, { left: x, backgroundColor: color }, style]} />;
}

export const WeatherAmbience = memo(function WeatherAmbience({ kind, isDay, width, height }: {
  kind: ConditionKind;
  isDay: boolean;
  width: number;
  height: number;
}) {
  const reduced = useReducedMotion();
  if (width <= 0) return null;
  const sunny = kind === 'CLEAR' || (kind === 'PARTLY_CLOUDY' && isDay);
  const drops = DROPS[kind] ?? 0;
  const flake = kind === 'SNOW';
  const heavy = kind === 'HEAVY_RAIN' || kind === 'STORM' || kind === 'HAIL';
  const cloudOpacity = kind === 'PARTLY_CLOUDY' ? 0.09 : heavy ? 0.16 : 0.12;
  return (
    <View pointerEvents="none" style={[StyleSheet.absoluteFill, { height }]}>
      {sunny ? <Glow isDay={isDay} reduced={reduced} /> : null}
      {CLOUDED.includes(kind) ? (
        <>
          <Cloud width={width} top={6} size={110} duration={46_000} offset={0.15} opacity={cloudOpacity} reduced={reduced} />
          <Cloud width={width} top={height * 0.42} size={80} duration={64_000} offset={0.6} opacity={cloudOpacity * 0.8} reduced={reduced} />
        </>
      ) : null}
      {Array.from({ length: drops }, (_, i) => (
        <Drop
          key={i}
          x={Math.round(spread(i + 1) * (width - 8)) + 4}
          height={height}
          delay={Math.round(spread(i + 7) * (flake ? 3000 : 1200))}
          duration={flake ? 3200 + spread(i + 3) * 1600 : (heavy ? 700 : 1000) + spread(i + 3) * 400}
          flake={flake}
          color={flake ? 'rgba(240,236,219,0.75)' : 'rgba(102,173,203,0.55)'}
          reduced={reduced}
        />
      ))}
    </View>
  );
});

const styles = StyleSheet.create({
  glow: { position: 'absolute', top: -46, left: -40, width: 170, height: 170 },
  drop: { position: 'absolute', top: 0, width: 1.5, height: 11, borderRadius: 1 },
  flake: { position: 'absolute', top: 0, width: 3, height: 3, borderRadius: 1.5 },
});
