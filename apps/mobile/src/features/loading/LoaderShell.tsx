import { useEffect, useMemo, useState } from 'react';
import { StyleSheet, Text, useWindowDimensions, View } from 'react-native';
import Animated, {
  Easing,
  FadeIn,
  FadeInDown,
  FadeOut,
  FadeOutUp,
  interpolate,
  interpolateColor,
  useAnimatedReaction,
  useAnimatedStyle,
  useFrameCallback,
  useReducedMotion,
  useSharedValue,
  withDelay,
  withTiming,
} from 'react-native-reanimated';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { scheduleOnRN } from 'react-native-worklets';
import { colors, fonts, space } from '../../theme';
import { Backdrop, SceneFrame, VB_H, VB_W } from './Stage';
import { STAGE_STARTS, type PlantLoader } from './types';

/** A full growth cycle when loading is instant: short enough to never feel slow. */
const CYCLE_S = 4.4;
const REDUCED_CYCLE_S = 1;
/** While a loading step is still running, growth creeps on slowly instead of freezing. */
const CREEP_RATE = 0.22;
const HOLD_MS = 450;
const FADE_MS = 550;
const MESSAGE_MS = 1100;

/** Farm messages shown in turn while the app loads. */
export const LOADING_MESSAGES = [
  'Loading farm data...',
  'Synchronizing fields...',
  'Checking machinery...',
  'Updating field operations...',
  'Preparing recommendations...',
] as const;
const FINAL_MESSAGE = 'Loading dashboard...';

export interface LoaderShellProps {
  plant: PlantLoader;
  /** Real loading progress, 0 to 1. Growth never runs ahead of it. */
  progress: number;
  /** Called once the fade-out has finished. */
  onFinish: () => void;
}

/**
 * Full-screen loader shared by every plant: progress pacing, plant name, growth
 * stage, rotating messages, progress bar and fade-out. The plant grows with the
 * real loading progress; everything animates on the UI thread.
 */
export function LoaderShell({ plant, progress: realProgress, onFinish }: LoaderShellProps) {
  const { width, height } = useWindowDimensions();
  const insets = useSafeAreaInsets();
  const reducedMotion = useReducedMotion();
  const motion = reducedMotion ? 0 : 1;
  const cycle = reducedMotion ? REDUCED_CYCLE_S : CYCLE_S;
  const { Plant } = plant;

  const real = useSharedValue(realProgress);
  const progress = useSharedValue(0);
  const clock = useSharedValue(0);
  const fade = useSharedValue(1);
  const [percent, setPercent] = useState(0);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    real.value = realProgress;
  }, [realProgress, real]);

  useFrameCallback((frame) => {
    const dt = Math.min((frame.timeSincePreviousFrame ?? 16) / 1000, 0.05);
    clock.value += dt;
    const p = progress.value;
    const target = real.value;
    if (p < target) {
      progress.value = Math.min(target, p + dt / cycle);
    } else if (target < 1) {
      const ceiling = Math.min(0.95, target + (1 - target) * 0.45);
      if (p < ceiling) progress.value = Math.min(ceiling, p + (dt * CREEP_RATE) / cycle);
    }
  });

  useAnimatedReaction(
    () => Math.floor(progress.value * 100 + 1e-6),
    (value, previous) => {
      if (value !== previous) scheduleOnRN(setPercent, value);
    },
  );

  useAnimatedReaction(
    () => progress.value >= 1,
    (done, wasDone) => {
      if (!done || wasDone) return;
      fade.value = withDelay(
        HOLD_MS,
        withTiming(0, { duration: FADE_MS, easing: Easing.inOut(Easing.cubic) }, (finished) => {
          if (finished) scheduleOnRN(onFinish);
        }),
      );
    },
  );

  // Plant messages and farm messages alternate; the last moments always say what comes next.
  const messages = useMemo(() => {
    const list: string[] = [];
    const longest = Math.max(plant.messages.length, LOADING_MESSAGES.length);
    for (let i = 0; i < longest; i += 1) {
      if (LOADING_MESSAGES[i]) list.push(LOADING_MESSAGES[i]!);
      if (plant.messages[i]) list.push(plant.messages[i]!);
    }
    return list;
  }, [plant.messages]);
  useEffect(() => {
    const timer = setInterval(() => setTick((value) => value + 1), MESSAGE_MS);
    return () => clearInterval(timer);
  }, []);
  const message = percent >= 90 ? FINAL_MESSAGE : messages[tick % messages.length]!;

  let stage = 0;
  for (let i = 0; i < STAGE_STARTS.length; i += 1) if (percent >= Math.round(STAGE_STARTS[i]! * 100)) stage = i;
  const last = stage === STAGE_STARTS.length - 1;

  const containerStyle = useAnimatedStyle(() => ({ opacity: fade.value }));
  const sceneStyle = useAnimatedStyle(() => ({ transform: [{ scale: interpolate(fade.value, [0, 1], [1.05, 1]) }] }));
  const barStyle = useAnimatedStyle(() => ({
    width: `${progress.value * 100}%`,
    backgroundColor: interpolateColor(progress.value, [0, 0.6, 1], [colors.leaf, colors.leaf, plant.accent]),
  }));

  // The scene fits the screen on phones, tablets and desktop windows alike.
  const sceneWidth = Math.min(width - space.lg * 2, 340, ((height - insets.top - insets.bottom) * 0.46 * VB_W) / VB_H);
  const barWidth = Math.min(width - space.xxl * 2, 360);

  return (
    <Animated.View
      style={[styles.root, containerStyle]}
      accessible
      accessibilityRole="progressbar"
      accessibilityLabel={`Loading FermApp. ${plant.name}, stage ${stage + 1}: ${plant.stages[stage]}`}
      accessibilityValue={{ min: 0, max: 100, now: percent }}
    >
      <Backdrop progress={progress} clock={clock} motion={motion} width={width} height={height} accent={plant.accent} />
      <Animated.View style={[styles.content, { paddingTop: insets.top + space.lg, paddingBottom: insets.bottom + space.lg }, sceneStyle]}>
        <View style={styles.brand}>
          <Text style={styles.brandName}>FermApp</Text>
          <Text style={[styles.plantName, { color: plant.accent }]}>{plant.name}</Text>
        </View>

        <View style={styles.sceneWrap}>
          <SceneFrame size={sceneWidth} progress={progress} accent={plant.accent}>
            <Plant progress={progress} clock={clock} motion={motion} />
          </SceneFrame>
        </View>

        <View style={[styles.status, { width: barWidth }]}>
          <View style={styles.stageSlot}>
            <Animated.Text
              key={stage}
              entering={FadeInDown.duration(360).easing(Easing.out(Easing.cubic))}
              exiting={FadeOutUp.duration(220)}
              style={[styles.stage, last && { color: plant.accent }]}
              numberOfLines={1}
              adjustsFontSizeToFit
            >
              {`Stage ${stage + 1}: ${plant.stages[stage]}`}
            </Animated.Text>
          </View>
          <View style={styles.messageSlot}>
            <Animated.Text key={message} entering={FadeIn.duration(320)} exiting={FadeOut.duration(200)} style={styles.message} numberOfLines={1}>
              {message}
            </Animated.Text>
          </View>
          <View style={styles.track}>
            <Animated.View style={[styles.fill, barStyle]}>
              <View style={styles.shine} />
            </Animated.View>
          </View>
          <Text style={styles.percent}>
            {percent}
            <Text style={styles.percentSign}>%</Text>
          </Text>
        </View>
      </Animated.View>
    </Animated.View>
  );
}

const styles = StyleSheet.create({
  root: { position: 'absolute', top: 0, right: 0, bottom: 0, left: 0, backgroundColor: colors.loam, zIndex: 1000, elevation: 1000 },
  content: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: space.lg, paddingHorizontal: space.lg },
  brand: { alignItems: 'center', gap: 2 },
  brandName: { fontFamily: fonts.semibold, fontSize: 13, letterSpacing: 3, color: colors.straw, textTransform: 'uppercase' },
  plantName: { fontFamily: fonts.figure, fontSize: 36, letterSpacing: 0.5 },
  sceneWrap: {
    // A soft drop shadow lifts the scene off the background.
    shadowColor: '#000',
    shadowOpacity: 0.35,
    shadowRadius: 22,
    shadowOffset: { width: 0, height: 14 },
  },
  status: { alignItems: 'center', gap: space.xs },
  stageSlot: { height: 28, alignSelf: 'stretch' },
  stage: { position: 'absolute', left: 0, right: 0, textAlign: 'center', fontFamily: fonts.semibold, fontSize: 19, color: colors.chaff },
  messageSlot: { height: 20, alignSelf: 'stretch' },
  message: { position: 'absolute', left: 0, right: 0, textAlign: 'center', fontFamily: fonts.medium, fontSize: 13.5, color: colors.straw },
  track: { alignSelf: 'stretch', height: 6, borderRadius: 3, backgroundColor: colors.ridge, overflow: 'hidden', marginTop: space.sm },
  fill: { height: '100%', borderRadius: 3, overflow: 'hidden' },
  shine: { height: '45%', backgroundColor: 'rgba(255,255,255,0.22)' },
  percent: { fontFamily: fonts.figure, fontSize: 24, color: colors.chaff, fontVariant: ['tabular-nums'], marginTop: space.xs },
  percentSign: { fontFamily: fonts.figure, fontSize: 15, color: colors.straw },
});
