import { interpolateColor, useAnimatedProps } from 'react-native-reanimated';
import { APath, GREEN, GROUND, Roots, S2, S3, S4, S5, Seed, Sprout, STEM_X } from '../Stage';
import type { PlantLoader, PlantProps } from '../types';
import { ellipsePath, f, leafPath, seg, stemPath, stemPoint, wind } from '../worklets';

const MAX_H = 124;
const RIPE = '#D9B43A';

/**
 * The main shoot and its tillers: each starts as a short leafy shoot, then
 * elongates into a stem carrying a head. Tillers appear during tillering.
 */
const SHOOTS = [
  { lean: 0, scale: 1, from: S2, phase: 0 },
  { lean: -14, scale: 0.9, from: S3, phase: 1.1 },
  { lean: 14, scale: 0.92, from: S3 + 0.03, phase: 2.3 },
  { lean: -26, scale: 0.78, from: S3 + 0.07, phase: 3.1 },
  { lean: 26, scale: 0.8, from: S3 + 0.1, phase: 4.2 },
] as const;

function shootHeight(p: number, i: number) {
  'worklet';
  const shoot = SHOOTS[i]!;
  // Leafy shoot first, then stem elongation lifts every shoot together.
  return (18 * seg(p, shoot.from, shoot.from + 0.12) + 96 * seg(p, S4, S5 + 0.04) + 10 * seg(p, S5, 1)) * shoot.scale;
}

function sway(t: number, motion: number, i: number, h: number) {
  'worklet';
  return wind(t, motion, h / MAX_H, 4, SHOOTS[i]!.phase * 0.25);
}

function stems(p: number, t: number, motion: number) {
  'worklet';
  let d = '';
  for (let i = 0; i < SHOOTS.length; i += 1) {
    const h = shootHeight(p, i);
    if (h < 0.5) continue;
    d += stemPath(STEM_X, GROUND + 2, h + 2, SHOOTS[i]!.lean * seg(p, SHOOTS[i]!.from, SHOOTS[i]!.from + 0.12), sway(t, motion, i, h));
  }
  return d;
}

function blades(p: number, t: number, motion: number) {
  'worklet';
  let d = '';
  for (let i = 0; i < SHOOTS.length; i += 1) {
    const shoot = SHOOTS[i]!;
    const g = seg(p, shoot.from + 0.02, shoot.from + 0.14);
    if (g <= 0.01) continue;
    const h = shootHeight(p, i);
    const lean = shoot.lean * seg(p, shoot.from, shoot.from + 0.12);
    const s = sway(t, motion, i, h);
    // Two narrow blades per shoot, one low and one higher up the stem.
    for (let k = 0; k < 2; k += 1) {
      const frac = k === 0 ? Math.min(0.9, 10 / Math.max(h, 1)) : 0.42;
      if (k === 1 && h < 30) continue;
      const [x, y] = stemPoint(STEM_X, GROUND, h, lean, s, frac);
      const side = (i + k) % 2 === 0 ? 1 : -1;
      const len = (k === 0 ? 20 : 26) * g * shoot.scale;
      const angle = side > 0 ? -0.55 - Math.sin(t * 1.6 + i) * 0.05 * motion : Math.PI + 0.55 + Math.sin(t * 1.6 + i) * 0.05 * motion;
      d += leafPath(x, y, angle, len, len * 0.07, len * 0.32, 0.4);
    }
  }
  return d;
}

/** Heads build up grain by grain, then their awns grow. */
function heads(p: number, t: number, motion: number, awns: boolean) {
  'worklet';
  const g = seg(p, S5 - 0.02, 1);
  if (g <= 0.01) return '';
  let d = '';
  for (let i = 0; i < SHOOTS.length; i += 1) {
    const h = shootHeight(p, i);
    const lean = SHOOTS[i]!.lean;
    const s = sway(t, motion, i, h);
    const [x, y] = stemPoint(STEM_X, GROUND, h, lean, s, 1);
    const dirX = (lean + s) / Math.max(h, 1);
    const grains = Math.round(7 * g * SHOOTS[i]!.scale);
    for (let k = 0; k < grains; k += 1) {
      const gy = y - 2 - k * 2.6;
      const gx = x + dirX * (k * 2.6);
      for (const side of [-1, 1]) {
        const cx = gx + side * 1.25;
        if (awns) d += `M${f(cx)} ${f(gy - 1)}l${f(side * 1.6)} ${f(-6 * g)}`;
        else d += ellipsePath(cx, gy, 1.3, 1.9, side * 0.4);
      }
    }
  }
  return d;
}

function Wheat({ progress, clock, motion }: PlantProps) {
  const stemProps = useAnimatedProps(() => ({
    d: stems(progress.value, clock.value, motion),
    stroke: interpolateColor(seg(progress.value, S5, 1), [0, 1], [GREEN.mid, RIPE]),
  }));
  const bladeProps = useAnimatedProps(() => ({
    d: blades(progress.value, clock.value, motion),
    fill: interpolateColor(seg(progress.value, S5 + 0.04, 1), [0, 1], [GREEN.light, '#C9C46A']),
  }));
  const grainProps = useAnimatedProps(() => ({
    d: heads(progress.value, clock.value, motion, false),
    fill: interpolateColor(seg(progress.value, S5 + 0.03, 1), [0, 1], ['#B7CF7A', RIPE]),
  }));
  const awnProps = useAnimatedProps(() => ({ d: heads(progress.value, clock.value, motion, true) }));
  return (
    <>
      <Roots progress={progress} kind="fibrous" />
      <Seed progress={progress} rx={4.2} ry={2.4} color="#C9A266" />
      <Sprout progress={progress} clock={clock} motion={motion} />
      <APath animatedProps={stemProps} strokeWidth={1.5} strokeLinecap="round" fill="none" />
      <APath animatedProps={bladeProps} stroke={GREEN.dark} strokeWidth={0.3} />
      <APath animatedProps={awnProps} stroke="#E6CF7E" strokeWidth={0.45} strokeLinecap="round" fill="none" />
      <APath animatedProps={grainProps} stroke="#9C7F2A" strokeWidth={0.3} />
    </>
  );
}

export const wheatLoader: PlantLoader = {
  id: 'wheat',
  name: 'Wheat',
  stages: ['Seed', 'Germination', 'Emergence', 'Tillering', 'Stem Elongation', 'Mature Head'],
  messages: ['Tillers are spreading...', 'Heads are filling...'],
  accent: RIPE,
  Plant: Wheat,
};
