import { memo, type ReactNode } from 'react';
import { StyleSheet } from 'react-native';
import Animated, { useAnimatedProps, type SharedValue } from 'react-native-reanimated';
import Svg, { Circle, Defs, Ellipse, G, LinearGradient, Path, RadialGradient, Rect, Stop } from 'react-native-svg';
import { colors } from '../../theme';
import { circlePath, ellipsePath, f, hash, seg, pop, wind } from './worklets';

const APath = Animated.createAnimatedComponent(Path);
const ACircle = Animated.createAnimatedComponent(Circle);
const AG = Animated.createAnimatedComponent(G);

/** Scene geometry shared by every plant, in viewBox units. */
export const VB_W = 200;
export const VB_H = 240;
export const GROUND = 176;
export const STEM_X = 100;
export const SEED_Y = 192;
/** Stage boundaries, as shares of the progress. */
export const S1 = 1 / 6;
export const S2 = 2 / 6;
export const S3 = 3 / 6;
export const S4 = 4 / 6;
export const S5 = 5 / 6;

export const SOIL = { top: '#4A3A26', mid: '#33281A', bottom: '#1C160E', rim: '#6E5636', pebble: '#5C4A32', dark: '#2A2117' };
export const GREEN = { light: '#A3CF70', mid: '#7DAE4E', dark: '#4F7A32', deep: '#3C5F26', rib: '#3F6327' };

type Shared = SharedValue<number>;

// ---------------------------------------------------------------------------
// Full-screen backdrop: minimal, with a soft warm light that grows with progress.

const MOTES = Array.from({ length: 12 }, (_, i) => ({
  x: hash(i + 3),
  y: hash(i + 31),
  r: 0.9 + hash(i + 57) * 1.4,
  speed: 5 + hash(i + 77) * 8,
  sway: 5 + hash(i + 97) * 9,
  phase: hash(i + 113) * Math.PI * 2,
}));

export const Backdrop = memo(function Backdrop({ progress, clock, motion, width, height, accent }: { progress: Shared; clock: Shared; motion: number; width: number; height: number; accent: string }) {
  const glow = useAnimatedProps(() => ({ opacity: 0.35 + 0.65 * seg(progress.value, 0, 0.9) }));
  const motes = useAnimatedProps(() => {
    const t = clock.value * motion;
    let d = '';
    for (const mote of MOTES) {
      const span = height + 20;
      const y = height - ((mote.y * span + t * mote.speed) % span);
      const x = mote.x * width + Math.sin(t * 0.55 + mote.phase) * mote.sway;
      d += circlePath(x, y, mote.r);
    }
    return { d, opacity: 0.12 + 0.2 * progress.value };
  }, [width, height, motion]);
  return (
    <Svg width={width} height={height} style={StyleSheet.absoluteFill} pointerEvents="none">
      <Defs>
        <LinearGradient id="bdSky" x1="0" y1="0" x2="0" y2="1">
          <Stop offset="0" stopColor="#202418" />
          <Stop offset="1" stopColor={colors.loam} />
        </LinearGradient>
        <RadialGradient id="bdLight" cx="50%" cy="30%" r="60%">
          <Stop offset="0" stopColor={accent} stopOpacity="0.2" />
          <Stop offset="0.5" stopColor={accent} stopOpacity="0.05" />
          <Stop offset="1" stopColor={accent} stopOpacity="0" />
        </RadialGradient>
      </Defs>
      <Rect x={0} y={0} width={width} height={height} fill="url(#bdSky)" />
      <AG animatedProps={glow}>
        <Rect x={0} y={0} width={width} height={height} fill="url(#bdLight)" />
      </AG>
      <APath animatedProps={motes} fill="#F0E6C0" />
    </Svg>
  );
});

// ---------------------------------------------------------------------------
// The scene: a soft sun disc, the soil cut-away and the surface, with the plant on top.

export const SceneFrame = memo(function SceneFrame({ size, progress, accent, children }: { size: number; progress: Shared; accent: string; children: ReactNode }) {
  const sun = useAnimatedProps(() => ({ opacity: 0.25 + 0.75 * seg(progress.value, 0.1, 0.95) }));
  return (
    <Svg width={size} height={(size * VB_H) / VB_W} viewBox={`0 0 ${VB_W} ${VB_H}`}>
      <Defs>
        <RadialGradient id="sceneSun" cx="50%" cy="50%" r="50%">
          <Stop offset="0" stopColor={accent} stopOpacity="0.42" />
          <Stop offset="0.6" stopColor={accent} stopOpacity="0.1" />
          <Stop offset="1" stopColor={accent} stopOpacity="0" />
        </RadialGradient>
        <LinearGradient id="sceneSoil" x1="0" y1="0" x2="0" y2="1">
          <Stop offset="0" stopColor={SOIL.top} />
          <Stop offset="0.45" stopColor={SOIL.mid} />
          <Stop offset="1" stopColor={SOIL.bottom} />
        </LinearGradient>
        <LinearGradient id="sceneFade" x1="0" y1="0" x2="1" y2="0">
          <Stop offset="0" stopColor={colors.loam} stopOpacity="1" />
          <Stop offset="0.16" stopColor={colors.loam} stopOpacity="0" />
          <Stop offset="0.84" stopColor={colors.loam} stopOpacity="0" />
          <Stop offset="1" stopColor={colors.loam} stopOpacity="1" />
        </LinearGradient>
      </Defs>
      <AG animatedProps={sun}>
        <Circle cx={STEM_X} cy={92} r={86} fill="url(#sceneSun)" />
      </AG>
      {/* Soil cut-away with a rounded surface and a few pebbles. */}
      <Path d={`M0 ${GROUND + 2} Q${VB_W / 2} ${GROUND - 5} ${VB_W} ${GROUND + 2} L${VB_W} ${VB_H} L0 ${VB_H} Z`} fill="url(#sceneSoil)" />
      <Path d={`M0 ${GROUND + 2} Q${VB_W / 2} ${GROUND - 5} ${VB_W} ${GROUND + 2}`} stroke={SOIL.rim} strokeWidth={1.4} fill="none" />
      {[
        [28, 198, 3.6, 2.2],
        [54, 220, 2.4, 1.5],
        [150, 206, 3.2, 2],
        [174, 226, 2.2, 1.4],
        [128, 232, 2.8, 1.7],
        [72, 236, 2, 1.3],
      ].map(([x, y, rx, ry], index) => (
        <Ellipse key={index} cx={x} cy={y} rx={rx} ry={ry} fill={index % 2 ? SOIL.dark : SOIL.pebble} />
      ))}
      {/* Contact shadow under the plant. */}
      <Ellipse cx={STEM_X} cy={GROUND - 0.5} rx={22} ry={2.6} fill="#000" opacity={0.22} />
      {children}
      {/* Edges melt into the background, so the scene has no hard frame. */}
      <Rect x={0} y={0} width={VB_W} height={VB_H} fill="url(#sceneFade)" pointerEvents="none" />
    </Svg>
  );
});

// ---------------------------------------------------------------------------
// Shared growth pieces.

/** The seed drops into the soil, swells, and fades once the plant no longer needs it. */
export function Seed({ progress, rx, ry, color, stripe, fadeAt = S3 }: { progress: Shared; rx: number; ry: number; color: string; stripe?: string; fadeAt?: number }) {
  const body = useAnimatedProps(() => {
    const p = progress.value;
    const drop = seg(p, 0, S1 * 0.55);
    const swell = 1 + 0.18 * seg(p, S1 * 0.5, S1);
    const y = SEED_Y - 26 * (1 - drop);
    return {
      d: ellipsePath(STEM_X, y, rx * swell * pop(p, 0, S1 * 0.4), ry * swell * pop(p, 0, S1 * 0.4), -0.3),
      opacity: 1 - seg(p, fadeAt, fadeAt + 0.08),
    };
  });
  const lines = useAnimatedProps(() => {
    const p = progress.value;
    const y = SEED_Y - 26 * (1 - seg(p, 0, S1 * 0.55));
    return {
      d: stripe ? `M${f(STEM_X - rx * 0.55)} ${f(y - ry * 0.2)}L${f(STEM_X + rx * 0.55)} ${f(y - ry * 0.55)}M${f(STEM_X - rx * 0.5)} ${f(y + ry * 0.35)}L${f(STEM_X + rx * 0.5)} ${f(y)}` : '',
      opacity: (1 - seg(p, fadeAt, fadeAt + 0.08)) * seg(p, 0.02, 0.08),
    };
  });
  // A small puff of soil where the seed lands.
  const puff = useAnimatedProps(() => {
    const p = progress.value;
    const k = seg(p, S1 * 0.5, S1 * 0.85);
    let d = '';
    for (let i = 0; i < 5; i += 1) {
      const a = Math.PI + (i / 4) * Math.PI;
      d += circlePath(STEM_X + Math.cos(a) * (4 + 9 * k), GROUND - 1 + Math.sin(a) * (3 + 5 * k), 1.1 * (1 - k));
    }
    return { d, opacity: 1 - k };
  });
  return (
    <>
      <APath animatedProps={body} fill={color} />
      {stripe ? <APath animatedProps={lines} stroke={stripe} strokeWidth={0.8} strokeLinecap="round" fill="none" /> : null}
      <APath animatedProps={puff} fill={SOIL.rim} />
    </>
  );
}

const ROOT_SETS = {
  tap: [
    { d: 'M100 194 C99 204 102 212 99 224', from: S1, to: S2, width: 1.5 },
    { d: 'M100 198 C95 202 91 207 87 213', from: S1 + 0.05, to: S2 + 0.05, width: 1 },
    { d: 'M100 199 C105 203 109 208 113 213', from: S1 + 0.07, to: S2 + 0.07, width: 1 },
    { d: 'M99 210 C94 214 92 218 88 225', from: S3, to: S4, width: 0.8 },
    { d: 'M100 212 C106 216 109 220 113 227', from: S3 + 0.03, to: S4 + 0.03, width: 0.8 },
    { d: 'M99 223 C97 228 99 232 97 238', from: S3 + 0.05, to: S4 + 0.05, width: 1.1 },
  ],
  fibrous: [
    { d: 'M100 194 C96 204 92 212 86 222', from: S1, to: S2, width: 1 },
    { d: 'M100 194 C101 205 99 214 101 226', from: S1 + 0.03, to: S2 + 0.03, width: 1.1 },
    { d: 'M100 194 C104 204 109 212 114 221', from: S1 + 0.05, to: S2 + 0.05, width: 1 },
    { d: 'M100 195 C93 200 86 206 79 210', from: S3, to: S4, width: 0.8 },
    { d: 'M100 195 C107 200 114 206 121 211', from: S3 + 0.02, to: S4 + 0.02, width: 0.8 },
    { d: 'M100 196 C97 210 94 222 92 234', from: S3 + 0.04, to: S4 + 0.04, width: 0.8 },
    { d: 'M100 196 C103 210 106 222 108 234', from: S3 + 0.06, to: S4 + 0.06, width: 0.8 },
  ],
} as const;
const ROOT_LENGTH = 46;

function Root({ progress, d, from, to, width }: { progress: Shared; d: string; from: number; to: number; width: number }) {
  const props = useAnimatedProps(() => ({ strokeDashoffset: ROOT_LENGTH * (1 - seg(progress.value, from, to)) }));
  return <APath animatedProps={props} d={d} stroke="#CDB98E" strokeWidth={width} strokeLinecap="round" fill="none" strokeDasharray={[ROOT_LENGTH, ROOT_LENGTH]} opacity={0.85} />;
}

/** Roots grow down from the seed as the plant develops. */
export function Roots({ progress, kind = 'tap' }: { progress: Shared; kind?: keyof typeof ROOT_SETS }) {
  return (
    <>
      {ROOT_SETS[kind].map((root) => (
        <Root key={root.d} progress={progress} d={root.d} from={root.from} to={root.to} width={root.width} />
      ))}
    </>
  );
}

/** The germinating shoot: a pale hook pushing up from the seed to the surface. */
export function Sprout({ progress, clock, motion, color = '#D7E3A0' }: { progress: Shared; clock: Shared; motion: number; color?: string }) {
  const props = useAnimatedProps(() => {
    const p = progress.value;
    const g = seg(p, S1 + 0.02, S2);
    const top = SEED_Y - 4 - (SEED_Y - GROUND + 2) * g;
    const hook = 3.5 * (1 - seg(p, S2 - 0.04, S2 + 0.02));
    const sway = wind(clock.value, motion, g, 0.6);
    return {
      d: g <= 0.01 ? '' : `M${STEM_X} ${SEED_Y - 3}Q${f(STEM_X + 2)} ${f((SEED_Y + top) / 2)} ${f(STEM_X + sway)} ${f(top)}q${f(-hook)} ${f(-hook * 0.4)} ${f(-hook)} ${f(hook * 0.8)}`,
      opacity: 1 - seg(p, S2 + 0.02, S2 + 0.08),
    };
  });
  return <APath animatedProps={props} stroke={color} strokeWidth={2} strokeLinecap="round" fill="none" />;
}

export { ACircle, AG, APath };
