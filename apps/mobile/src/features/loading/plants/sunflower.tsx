import { useAnimatedProps } from 'react-native-reanimated';
import { APath, GREEN, GROUND, Roots, S2, S3, S4, S5, Seed, Sprout, STEM_X } from '../Stage';
import type { PlantLoader, PlantProps } from '../types';
import { circlePath, leafPath, pop, ribPath, seg, stemPath, stemPoint, wind } from '../worklets';

const MAX_H = 118;
const PETALS = 18;

function height(p: number) {
  'worklet';
  return 40 * seg(p, S2, S3) + 50 * seg(p, S3, S4) + 28 * seg(p, S4, S5);
}

/** Broad heart-shaped leaves in pairs up the stem. */
const LEAVES = [
  { h: 12, len: 13, from: S2 + 0.04, to: S3 + 0.02 },
  { h: 30, len: 22, from: S3 + 0.02, to: S4 - 0.02 },
  { h: 52, len: 26, from: S3 + 0.08, to: S4 + 0.03 },
  { h: 74, len: 22, from: S4 - 0.02, to: S5 },
  { h: 94, len: 16, from: S4 + 0.06, to: S5 + 0.04 },
] as const;

function headAt(p: number, t: number, motion: number) {
  'worklet';
  const h = height(p);
  const sway = wind(t, motion, h / MAX_H, 2.6);
  return stemPoint(STEM_X, GROUND, h, 0, sway, 1);
}

function leaves(p: number, t: number, motion: number, ribs: boolean) {
  'worklet';
  const h = height(p);
  const sway = wind(t, motion, h / MAX_H, 2.6);
  let d = '';
  for (let i = 0; i < LEAVES.length; i += 1) {
    const leaf = LEAVES[i]!;
    if (h < leaf.h + 2) continue;
    const g = pop(p, leaf.from, leaf.to);
    if (g <= 0.01) continue;
    const [x, y] = stemPoint(STEM_X, GROUND, h, 0, sway, leaf.h / h);
    const flutter = Math.sin(t * 1.5 + i * 1.3) * 0.06 * motion;
    for (const side of [-1, 1]) {
      const angle = side < 0 ? Math.PI + 0.35 + flutter : -0.35 - flutter;
      const len = leaf.len * g * (side < 0 ? 1 : 0.92);
      d += ribs ? ribPath(x, y, angle, len, len * 0.2) : leafPath(x, y, angle, len, len * 0.42, len * 0.2, 0.36);
    }
  }
  return d;
}

function Sunflower({ progress, clock, motion }: PlantProps) {
  const stem = useAnimatedProps(() => {
    const h = height(progress.value);
    return { d: stemPath(STEM_X, GROUND + 2, h + 2, 0, wind(clock.value, motion, h / MAX_H, 2.6)), strokeWidth: 2 + 2.6 * seg(progress.value, S2, S5) };
  });
  const leafProps = useAnimatedProps(() => ({ d: leaves(progress.value, clock.value, motion, false) }));
  const ribProps = useAnimatedProps(() => ({ d: leaves(progress.value, clock.value, motion, true) }));
  // The bud swells, then its green bracts part as the petals push out.
  const bud = useAnimatedProps(() => {
    const p = progress.value;
    const g = pop(p, S4 + 0.02, S5);
    const [x, y] = headAt(p, clock.value, motion);
    let d = circlePath(x, y, 6.5 * g);
    for (let k = 0; k < 10; k += 1) {
      const a = (k / 10) * Math.PI * 2;
      d += leafPath(x + Math.cos(a) * 5 * g, y + Math.sin(a) * 5 * g, a, 5.5 * g, 1.8 * g);
    }
    return { d };
  });
  const petals = useAnimatedProps(() => {
    const p = progress.value;
    const open = pop(p, S5, 1);
    if (open <= 0.01) return { d: '' };
    const [x, y] = headAt(p, clock.value, motion);
    let d = '';
    for (let layer = 0; layer < 2; layer += 1) {
      for (let k = 0; k < PETALS; k += 1) {
        const a = ((k + layer * 0.5) / PETALS) * Math.PI * 2 + Math.sin(clock.value * 0.8) * 0.02 * motion;
        const len = (layer ? 13 : 15) * open;
        d += leafPath(x + Math.cos(a) * 8, y + Math.sin(a) * 8, a, len, len * 0.24, 0, 0.5);
      }
    }
    return { d };
  });
  const disk = useAnimatedProps(() => {
    const p = progress.value;
    const g = seg(p, S5 + 0.02, 1);
    const [x, y] = headAt(p, clock.value, motion);
    return { d: circlePath(x, y, 9.5 * g) };
  });
  const seeds = useAnimatedProps(() => {
    const p = progress.value;
    const g = seg(p, S5 + 0.06, 1);
    if (g <= 0.01) return { d: '', opacity: 0 };
    const [x, y] = headAt(p, clock.value, motion);
    let d = '';
    for (let k = 0; k < 40; k += 1) {
      const r = 8.4 * Math.sqrt((k + 0.5) / 40) * g;
      const a = k * 2.39996;
      d += circlePath(x + r * Math.cos(a), y + r * Math.sin(a), 0.5);
    }
    return { d, opacity: g };
  });
  return (
    <>
      <Roots progress={progress} kind="tap" />
      <Seed progress={progress} rx={5.4} ry={2.8} color="#2F2A22" stripe="#C9BFA4" />
      <Sprout progress={progress} clock={clock} motion={motion} />
      <APath animatedProps={stem} stroke={GREEN.mid} strokeLinecap="round" fill="none" />
      <APath animatedProps={leafProps} fill={GREEN.light} stroke={GREEN.dark} strokeWidth={0.4} />
      <APath animatedProps={ribProps} stroke={GREEN.rib} strokeWidth={0.5} fill="none" opacity={0.55} />
      <APath animatedProps={bud} fill="#6E9A45" stroke={GREEN.deep} strokeWidth={0.4} />
      <APath animatedProps={petals} fill="#F4C534" stroke="#D9961C" strokeWidth={0.4} />
      <APath animatedProps={disk} fill="#5B3A19" />
      <APath animatedProps={seeds} fill="#B07E35" />
    </>
  );
}

export const sunflowerLoader: PlantLoader = {
  id: 'sunflower',
  name: 'Sunflower',
  stages: ['Seed', 'Germination', 'Stem Growth', 'Leaf Development', 'Bud Formation', 'Fully Open Flower'],
  messages: ['Turning towards the sun...', 'The bud is forming...'],
  accent: '#F4C534',
  Plant: Sunflower,
};

