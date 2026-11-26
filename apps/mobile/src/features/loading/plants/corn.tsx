import { useAnimatedProps } from 'react-native-reanimated';
import { APath, GREEN, GROUND, Roots, S2, S3, S4, S5, Seed, Sprout, STEM_X } from '../Stage';
import type { PlantLoader, PlantProps } from '../types';
import { ellipsePath, f, leafPath, pop, ribPath, seg, stemPath, stemPoint, wind } from '../worklets';

const MAX_H = 132;

/** Stem height: a short shoot at emergence, then fast growth through the vegetative stage. */
function height(p: number) {
  'worklet';
  return 16 * seg(p, S2, S3) + 52 * seg(p, S3, S4) + 54 * seg(p, S4, S5) + 10 * seg(p, S5, 1);
}

/** Leaves at a fixed height on the stem: side, length, angle and when they unfold. */
const LEAVES = [
  { h: 10, side: -1, len: 16, angle: 0.75, from: S2 + 0.03, to: S3 + 0.02, droop: 3 },
  { h: 13, side: 1, len: 17, angle: 0.7, from: S2 + 0.06, to: S3 + 0.04, droop: 3 },
  { h: 24, side: -1, len: 34, angle: 0.42, from: S3, to: S4 - 0.04, droop: 12 },
  { h: 36, side: 1, len: 38, angle: 0.38, from: S3 + 0.05, to: S4, droop: 13 },
  { h: 52, side: -1, len: 46, angle: 0.34, from: S4 - 0.06, to: S4 + 0.08, droop: 17 },
  { h: 68, side: 1, len: 48, angle: 0.32, from: S4, to: S5 - 0.02, droop: 18 },
  { h: 86, side: -1, len: 44, angle: 0.36, from: S4 + 0.06, to: S5 + 0.02, droop: 16 },
  { h: 102, side: 1, len: 38, angle: 0.42, from: S5 - 0.06, to: S5 + 0.06, droop: 13 },
  { h: 116, side: -1, len: 28, angle: 0.5, from: S5 - 0.02, to: S5 + 0.1, droop: 9 },
] as const;

function build(p: number, t: number, motion: number, ribs: boolean) {
  'worklet';
  const h = height(p);
  const sway = wind(t, motion, h / MAX_H, 3.2);
  let d = '';
  for (let i = 0; i < LEAVES.length; i += 1) {
    const leaf = LEAVES[i]!;
    if (h < leaf.h + 2) continue;
    const grow = pop(p, leaf.from, leaf.to);
    if (grow <= 0.01) continue;
    const [x, y] = stemPoint(STEM_X, GROUND, h, 0, sway, leaf.h / h);
    const flutter = Math.sin(t * 1.7 + i) * 0.05 * motion;
    const angle = leaf.side < 0 ? Math.PI + leaf.angle + flutter : -leaf.angle - flutter;
    const len = leaf.len * grow;
    d += ribs ? ribPath(x, y, angle, len, leaf.droop * grow) : leafPath(x, y, angle, len, len * 0.11, leaf.droop * grow, 0.38);
  }
  return d;
}

function Corn({ progress, clock, motion }: PlantProps) {
  const stem = useAnimatedProps(() => {
    const h = height(progress.value);
    return { d: stemPath(STEM_X, GROUND + 2, h + 2, 0, wind(clock.value, motion, h / MAX_H, 3.2)), strokeWidth: 2.4 + 3.2 * seg(progress.value, S2, S5) };
  });
  const leaves = useAnimatedProps(() => ({ d: build(progress.value, clock.value, motion, false) }));
  const ribs = useAnimatedProps(() => ({ d: build(progress.value, clock.value, motion, true) }));
  // Tassel: golden branches at the top in the mature stage.
  const tassel = useAnimatedProps(() => {
    const p = progress.value;
    const g = seg(p, S5 - 0.02, 1);
    const h = height(p);
    const sway = wind(clock.value, motion, 1, 3.2);
    const [x, y] = stemPoint(STEM_X, GROUND, h, 0, sway, 1);
    let d = '';
    for (let k = -2; k <= 2; k += 1) {
      const len = (11 - Math.abs(k) * 1.5) * g;
      const a = -Math.PI / 2 + k * 0.32 + Math.sin(clock.value * 2 + k) * 0.06 * motion;
      d += `M${f(x)} ${f(y)}Q${f(x + Math.cos(a) * len * 0.6)} ${f(y + Math.sin(a) * len * 0.6 - 1)} ${f(x + Math.cos(a) * len)} ${f(y + Math.sin(a) * len + 2 * Math.abs(k) * g)}`;
    }
    return { d, opacity: g };
  });
  // The ear: a husked cob leaning out of the stem, with silk at its tip.
  const ear = useAnimatedProps(() => {
    const p = progress.value;
    const g = pop(p, S5 - 0.03, S5 + 0.12);
    const h = height(p);
    if (h < 60 || g <= 0.01) return { d: '' };
    const sway = wind(clock.value, motion, 60 / MAX_H, 3.2);
    const [x, y] = stemPoint(STEM_X, GROUND, h, 0, sway, 60 / h);
    return { d: ellipsePath(x + 5.5 * g, y - 7 * g, 3.4 * g, 9.5 * g, 0.42) };
  });
  const silk = useAnimatedProps(() => {
    const p = progress.value;
    const g = seg(p, S5 + 0.04, 1);
    const h = height(p);
    if (h < 60 || g <= 0.01) return { d: '', opacity: 0 };
    const sway = wind(clock.value, motion, 60 / MAX_H, 3.2);
    const [x, y] = stemPoint(STEM_X, GROUND, h, 0, sway, 60 / h);
    const tipX = x + 9;
    const tipY = y - 15.5;
    let d = '';
    for (let k = 0; k < 4; k += 1) d += `M${f(tipX)} ${f(tipY)}q${f(2 + k)} ${f(-3 - k * 0.5)} ${f((3 + k * 1.4) * g)} ${f((-1 + k) * g)}`;
    return { d, opacity: g };
  });
  return (
    <>
      <Roots progress={progress} kind="fibrous" />
      <Seed progress={progress} rx={4.6} ry={3.6} color="#E8B83A" />
      <Sprout progress={progress} clock={clock} motion={motion} />
      <APath animatedProps={stem} stroke={GREEN.mid} strokeLinecap="round" fill="none" />
      <APath animatedProps={leaves} fill={GREEN.light} stroke={GREEN.dark} strokeWidth={0.4} />
      <APath animatedProps={ribs} stroke={GREEN.rib} strokeWidth={0.5} fill="none" opacity={0.55} />
      <APath animatedProps={ear} fill="#9DBF5E" stroke={GREEN.dark} strokeWidth={0.5} />
      <APath animatedProps={silk} stroke="#C28B4B" strokeWidth={0.7} strokeLinecap="round" fill="none" />
      <APath animatedProps={tassel} stroke="#D9B14A" strokeWidth={1.1} strokeLinecap="round" fill="none" />
    </>
  );
}

export const cornLoader: PlantLoader = {
  id: 'corn',
  name: 'Corn',
  stages: ['Seed', 'Germination', 'Emergence', 'First Leaves', 'Vegetative Growth', 'Mature Plant'],
  messages: ['Preparing farm data...', 'Corn is emerging...', 'Growing...'],
  accent: '#E2B23D',
  Plant: Corn,
};
