import { useAnimatedProps } from 'react-native-reanimated';
import { APath, GREEN, GROUND, Roots, S2, S3, S4, S5, Seed, Sprout, STEM_X } from '../Stage';
import type { PlantLoader, PlantProps } from '../types';
import { ellipsePath, f, pop, seg, stemPath, stemPoint, trifoliatePath, wind } from '../worklets';

const MAX_H = 104;
const CUT_Y = GROUND - 22;

/** A crown of stems that thickens into a dense stand. */
const STEMS = [
  { lean: 0, scale: 1, from: S2, phase: 0 },
  { lean: -12, scale: 0.92, from: S3, phase: 0.8 },
  { lean: 12, scale: 0.95, from: S3 + 0.03, phase: 1.6 },
  { lean: -24, scale: 0.8, from: S3 + 0.07, phase: 2.4 },
  { lean: 24, scale: 0.84, from: S3 + 0.1, phase: 3.1 },
  { lean: -6, scale: 0.86, from: S4, phase: 3.9 },
  { lean: 7, scale: 0.88, from: S4 + 0.03, phase: 4.6 },
] as const;

function stemHeight(p: number, i: number) {
  'worklet';
  const stem = STEMS[i]!;
  return (14 * seg(p, stem.from, stem.from + 0.1) + 74 * seg(p, Math.max(stem.from, S3), S5) + 16 * seg(p, S5, 1)) * stem.scale;
}

function point(p: number, t: number, motion: number, i: number, frac: number) {
  'worklet';
  const stem = STEMS[i]!;
  const h = stemHeight(p, i);
  return stemPoint(STEM_X, GROUND, h, stem.lean * seg(p, stem.from, stem.from + 0.1), wind(t, motion, h / MAX_H, 3, stem.phase * 0.3), frac);
}

function Alfalfa({ progress, clock, motion }: PlantProps) {
  const stems = useAnimatedProps(() => {
    const p = progress.value;
    let d = '';
    for (let i = 0; i < STEMS.length; i += 1) {
      const stem = STEMS[i]!;
      const h = stemHeight(p, i);
      if (h < 0.5) continue;
      d += stemPath(STEM_X, GROUND + 2, h + 2, stem.lean * seg(p, stem.from, stem.from + 0.1), wind(clock.value, motion, h / MAX_H, 3, stem.phase * 0.3));
    }
    return { d };
  });
  // Small trifoliate leaves all along every stem.
  const leaves = useAnimatedProps(() => {
    const p = progress.value;
    let d = '';
    for (let i = 0; i < STEMS.length; i += 1) {
      const h = stemHeight(p, i);
      const count = Math.min(5, Math.floor(h / 16));
      for (let k = 0; k < count; k += 1) {
        const frac = (k + 0.7) / (count + 0.4);
        const g = pop(p, STEMS[i]!.from + 0.04 + k * 0.04, STEMS[i]!.from + 0.16 + k * 0.05);
        if (g <= 0.01) continue;
        const [x, y] = point(p, clock.value, motion, i, frac);
        const side = (i + k) % 2 === 0 ? 1 : -1;
        const flutter = Math.sin(clock.value * 1.8 + i + k) * 0.08 * motion;
        d += trifoliatePath(x, y, side > 0 ? -0.6 - flutter : Math.PI + 0.6 + flutter, 6.2 * g);
      }
    }
    return { d };
  });
  // Purple racemes open at the stem tips when the crop is ready.
  const flowers = useAnimatedProps(() => {
    const p = progress.value;
    const g = pop(p, S5 - 0.02, 1);
    if (g <= 0.01) return { d: '' };
    let d = '';
    for (let i = 0; i < STEMS.length; i += 1) {
      const [x, y] = point(p, clock.value, motion, i, 1);
      for (let k = 0; k < 6; k += 1) {
        const a = -Math.PI / 2 + (k - 2.5) * 0.45;
        d += ellipsePath(x + Math.cos(a) * 2.6 * g, y - 2 + Math.sin(a) * 3 * g, 1.3 * g, 2 * g, a + Math.PI / 2);
      }
    }
    return { d };
  });
  // A dashed line marks the cutting height once the crop is ready.
  const cut = useAnimatedProps(() => {
    const g = seg(progress.value, S5 + 0.06, 1);
    return { d: g > 0.01 ? `M${f(STEM_X - 44 * g)} ${CUT_Y}L${f(STEM_X + 44 * g)} ${CUT_Y}` : '', opacity: 0.85 * g };
  });
  return (
    <>
      <Roots progress={progress} kind="tap" />
      <Seed progress={progress} rx={3.2} ry={2.2} color="#C2A24E" />
      <Sprout progress={progress} clock={clock} motion={motion} />
      <APath animatedProps={stems} stroke={GREEN.mid} strokeWidth={1.3} strokeLinecap="round" fill="none" />
      <APath animatedProps={leaves} fill={GREEN.light} stroke={GREEN.dark} strokeWidth={0.3} />
      <APath animatedProps={flowers} fill="#8E6BC2" stroke="#6A4B98" strokeWidth={0.25} />
      <APath animatedProps={cut} stroke="#F0ECDB" strokeWidth={0.8} strokeDasharray={[3, 3]} fill="none" />
    </>
  );
}

export const alfalfaLoader: PlantLoader = {
  id: 'alfalfa',
  name: 'Alfalfa',
  stages: ['Seed', 'Germination', 'Emergence', 'Vegetative Growth', 'Mature Crop', 'Ready for Cutting'],
  messages: ['The stand is thickening...', 'First flowers are opening...'],
  accent: '#9C7FD0',
  Plant: Alfalfa,
};
