import { interpolateColor, useAnimatedProps } from 'react-native-reanimated';
import { APath, GREEN, GROUND, Roots, S2, S3, S4, S5, Seed, Sprout, STEM_X } from '../Stage';
import type { PlantLoader, PlantProps } from '../types';
import { circlePath, ellipsePath, f, pop, seg, stemPath, stemPoint, trifoliatePath, wind } from '../worklets';

const MAX_H = 96;

function height(p: number) {
  'worklet';
  return 16 * seg(p, S2, S3) + 58 * seg(p, S3, S4) + 22 * seg(p, S4, S5 + 0.04);
}

/** Nodes up the stem, alternating sides: a trifoliate leaf, later a flower and a pod. */
const NODES = [
  { h: 28, side: -1, size: 15, from: S3 + 0.02, to: S4 - 0.02 },
  { h: 42, side: 1, size: 17, from: S3 + 0.06, to: S4 },
  { h: 56, side: -1, size: 17, from: S3 + 0.1, to: S4 + 0.04 },
  { h: 70, side: 1, size: 15, from: S4 - 0.02, to: S5 - 0.04 },
  { h: 84, side: -1, size: 12, from: S4 + 0.04, to: S5 },
] as const;

function at(p: number, t: number, motion: number, frac: number) {
  'worklet';
  const h = height(p);
  return stemPoint(STEM_X, GROUND, h, 0, wind(t, motion, h / MAX_H, 2.4), frac);
}

function leaves(p: number, t: number, motion: number) {
  'worklet';
  const h = height(p);
  let d = '';
  for (let i = 0; i < NODES.length; i += 1) {
    const node = NODES[i]!;
    if (h < node.h + 1) continue;
    const g = pop(p, node.from, node.to);
    if (g <= 0.01) continue;
    const [x, y] = at(p, t, motion, node.h / h);
    const flutter = Math.sin(t * 1.4 + i) * 0.07 * motion;
    // The petiole holds the leaflets out and slightly up.
    const angle = node.side < 0 ? Math.PI + 0.55 + flutter : -0.55 - flutter;
    const px = x + Math.cos(angle) * 9 * g;
    const py = y + Math.sin(angle) * 9 * g;
    d += `M${f(x)} ${f(y)}L${f(px)} ${f(py)}`;
    d += trifoliatePath(px, py, angle, node.size * 0.55 * g);
  }
  return d;
}

function Soybean({ progress, clock, motion }: PlantProps) {
  const stem = useAnimatedProps(() => {
    const h = height(progress.value);
    return { d: stemPath(STEM_X, GROUND + 2, h + 2, 0, wind(clock.value, motion, h / MAX_H, 2.4)), strokeWidth: 1.8 + 1.4 * seg(progress.value, S2, S5) };
  });
  // Two round cotyledons open at the top of the young stem, then stay low on it.
  const cotyledons = useAnimatedProps(() => {
    const p = progress.value;
    const g = pop(p, S2 + 0.04, S3);
    const h = height(p);
    if (g <= 0.01 || h < 4) return { d: '', opacity: 0 };
    const [x, y] = at(p, clock.value, motion, Math.min(1, 15 / h));
    const spread = 0.35 + 0.6 * g;
    return { d: ellipsePath(x - 5 * spread, y - 1, 5 * g, 3.4 * g, 0.3) + ellipsePath(x + 5 * spread, y - 1, 5 * g, 3.4 * g, -0.3), opacity: 1 - 0.5 * seg(p, S5, 1) };
  });
  const leafProps = useAnimatedProps(() => ({
    d: leaves(progress.value, clock.value, motion),
    fill: interpolateColor(seg(progress.value, S5 + 0.04, 1), [0, 1], [GREEN.light, '#C8C25C']),
  }));
  const flowers = useAnimatedProps(() => {
    const p = progress.value;
    const g = pop(p, S4 + 0.02, S5);
    const fade = 1 - seg(p, S5 + 0.04, S5 + 0.12);
    const h = height(p);
    let d = '';
    for (let i = 0; i < NODES.length; i += 1) {
      const node = NODES[i]!;
      if (h < node.h + 1) continue;
      const [x, y] = at(p, clock.value, motion, node.h / h);
      d += circlePath(x - node.side * 2.6, y - 1.5, 1.9 * g) + circlePath(x - node.side * 1.2, y - 3.6, 1.5 * g);
    }
    return { d, opacity: fade };
  });
  // Pods hang in clusters at the nodes and turn tan as they mature.
  const pods = useAnimatedProps(() => {
    const p = progress.value;
    const g = pop(p, S5, 1);
    if (g <= 0.01) return { d: '', fill: '#8DB55A' };
    const h = height(p);
    let d = '';
    for (let i = 0; i < NODES.length; i += 1) {
      const node = NODES[i]!;
      if (h < node.h + 1) continue;
      const [x, y] = at(p, clock.value, motion, node.h / h);
      for (let k = 0; k < 2; k += 1) {
        const a = Math.PI / 2 + (node.side * 0.5 + (k ? -0.35 : 0.35)) + Math.sin(clock.value + i) * 0.05 * motion;
        d += ellipsePath(x - node.side * 1.5 + Math.cos(a) * 5 * g, y + Math.sin(a) * 5 * g, 5.4 * g, 1.6 * g, a);
      }
    }
    return { d, fill: interpolateColor(seg(p, S5 + 0.05, 1), [0, 1], ['#8DB55A', '#B79A5A']) };
  });
  return (
    <>
      <Roots progress={progress} kind="tap" />
      <Seed progress={progress} rx={4.4} ry={3.4} color="#D9C38B" />
      <Sprout progress={progress} clock={clock} motion={motion} />
      <APath animatedProps={stem} stroke={GREEN.mid} strokeLinecap="round" fill="none" />
      <APath animatedProps={cotyledons} fill="#9CC46A" stroke={GREEN.dark} strokeWidth={0.4} />
      <APath animatedProps={leafProps} stroke={GREEN.dark} strokeWidth={0.4} />
      <APath animatedProps={pods} stroke="#6E6A3A" strokeWidth={0.35} />
      <APath animatedProps={flowers} fill="#9B6FC4" />
    </>
  );
}

export const soybeanLoader: PlantLoader = {
  id: 'soybean',
  name: 'Soybean',
  stages: ['Seed', 'Germination', 'Cotyledons', 'Vegetative Growth', 'Flowering', 'Mature Plant'],
  messages: ['Cotyledons are opening...', 'Pods are filling...'],
  accent: '#A9C25A',
  Plant: Soybean,
};
