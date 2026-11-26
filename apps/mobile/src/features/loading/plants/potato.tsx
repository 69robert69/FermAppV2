import { interpolateColor, useAnimatedProps } from 'react-native-reanimated';
import { APath, GREEN, GROUND, S1, S2, S3, S4, S5, SEED_Y, STEM_X } from '../Stage';
import type { PlantLoader, PlantProps } from '../types';
import { circlePath, ellipsePath, f, leafPath, pop, seg, stemPath, stemPoint, trifoliatePath, wind } from '../worklets';

const MAX_H = 74;

/** Several stems rise from the seed potato and spread into a canopy. */
const STEMS = [
  { lean: -22, scale: 0.86, from: S1 + 0.04, phase: 0.6 },
  { lean: 0, scale: 1, from: S1, phase: 0 },
  { lean: 20, scale: 0.9, from: S1 + 0.07, phase: 1.4 },
] as const;

/** New tubers on stolons beside the seed potato. */
const TUBERS = [
  { x: 74, y: 206, rx: 9, ry: 6, angle: 0.2, from: S4 - 0.04 },
  { x: 128, y: 210, rx: 10, ry: 6.5, angle: -0.25, from: S4 },
  { x: 92, y: 226, rx: 8, ry: 5.4, angle: 0.4, from: S4 + 0.04 },
  { x: 116, y: 230, rx: 7.5, ry: 5, angle: -0.1, from: S4 + 0.07 },
] as const;

function stemHeight(p: number, i: number) {
  'worklet';
  const stem = STEMS[i]!;
  return (12 * seg(p, stem.from + 0.06, S3) + 44 * seg(p, S3, S4) + 18 * seg(p, S4, S5)) * stem.scale;
}

function stemTop(p: number, t: number, motion: number, i: number, frac: number) {
  'worklet';
  const h = stemHeight(p, i);
  const stem = STEMS[i]!;
  return stemPoint(STEM_X, GROUND, h, stem.lean * seg(p, S2, S4), wind(t, motion, h / MAX_H, 2.2, stem.phase), frac);
}

function Potato({ progress, clock, motion }: PlantProps) {
  // The seed potato, planted at the start, gives its energy and shrinks by harvest.
  const seed = useAnimatedProps(() => {
    const p = progress.value;
    const drop = seg(p, 0, S1 * 0.6);
    const size = pop(p, 0, S1 * 0.45) * (1 - 0.35 * seg(p, S4, 1));
    return { d: ellipsePath(STEM_X, SEED_Y + 4 - 24 * (1 - drop), 9 * size, 6.5 * size, -0.15) };
  });
  const eyes = useAnimatedProps(() => {
    const p = progress.value;
    const y = SEED_Y + 4 - 24 * (1 - seg(p, 0, S1 * 0.6));
    return { d: circlePath(STEM_X - 4, y - 1, 0.7) + circlePath(STEM_X + 3, y + 2, 0.6) + circlePath(STEM_X + 5, y - 2.5, 0.5), opacity: seg(p, 0.04, 0.1) * (1 - seg(p, S4, 1)) };
  });
  // Sprouts push up from the eyes during emergence.
  const sprouts = useAnimatedProps(() => {
    const p = progress.value;
    let d = '';
    for (let i = 0; i < STEMS.length; i += 1) {
      const g = seg(p, STEMS[i]!.from, S2 + 0.04);
      if (g <= 0.01) continue;
      const top = SEED_Y - 2 - (SEED_Y - GROUND) * g;
      d += `M${STEM_X + (i - 1) * 3} ${SEED_Y - 2}Q${f(STEM_X + (i - 1) * 5)} ${f((SEED_Y + top) / 2)} ${f(STEM_X + (i - 1) * 4)} ${f(top)}`;
    }
    return { d, opacity: 1 - seg(p, S2 + 0.04, S2 + 0.1) };
  });
  const stems = useAnimatedProps(() => {
    const p = progress.value;
    let d = '';
    for (let i = 0; i < STEMS.length; i += 1) {
      const h = stemHeight(p, i);
      if (h < 0.5) continue;
      d += stemPath(STEM_X, GROUND + 2, h + 2, STEMS[i]!.lean * seg(p, S2, S4), wind(clock.value, motion, h / MAX_H, 2.2, STEMS[i]!.phase));
    }
    return { d, stroke: interpolateColor(seg(p, S5, 1), [0, 1], [GREEN.mid, '#9A9A4A']) };
  });
  // Compound leaves build the canopy, then yellow towards harvest.
  const canopy = useAnimatedProps(() => {
    const p = progress.value;
    let d = '';
    for (let i = 0; i < STEMS.length; i += 1) {
      const h = stemHeight(p, i);
      for (let k = 0; k < 4; k += 1) {
        const frac = 0.32 + k * 0.2;
        if (h * frac < 6) continue;
        const g = pop(p, S2 + 0.02 + k * 0.05 + i * 0.02, S4 + k * 0.02);
        if (g <= 0.01) continue;
        const [x, y] = stemTop(p, clock.value, motion, i, frac);
        const side = (i + k) % 2 === 0 ? 1 : -1;
        const angle = side > 0 ? -0.4 - Math.sin(clock.value * 1.3 + k) * 0.06 * motion : Math.PI + 0.4 + Math.sin(clock.value * 1.3 + k) * 0.06 * motion;
        d += trifoliatePath(x, y, angle, 11 * g);
        d += leafPath(x, y, angle + side * 0.9, 6 * g, 2.6 * g);
      }
    }
    return { d, fill: interpolateColor(seg(p, S5 + 0.02, 1), [0, 1], [GREEN.light, '#C9B65A']) };
  });
  const flowers = useAnimatedProps(() => {
    const p = progress.value;
    const g = pop(p, S3 + 0.03, S4);
    const fade = 1 - seg(p, S5, 1);
    let d = '';
    for (let i = 0; i < STEMS.length; i += 1) {
      const [x, y] = stemTop(p, clock.value, motion, i, 1);
      for (let k = 0; k < 5; k += 1) {
        const a = (k / 5) * Math.PI * 2 - Math.PI / 2;
        d += leafPath(x, y - 3, a, 3.6 * g, 1.6 * g);
      }
    }
    return { d, opacity: fade };
  });
  const flowerCenters = useAnimatedProps(() => {
    const p = progress.value;
    const g = pop(p, S3 + 0.05, S4);
    let d = '';
    for (let i = 0; i < STEMS.length; i += 1) {
      const [x, y] = stemTop(p, clock.value, motion, i, 1);
      d += circlePath(x, y - 3, 1.2 * g);
    }
    return { d, opacity: 1 - seg(p, S5, 1) };
  });
  // Stolons reach out and new tubers swell beneath the canopy.
  const stolons = useAnimatedProps(() => {
    const p = progress.value;
    let d = '';
    for (const tuber of TUBERS) {
      const g = seg(p, tuber.from - 0.06, tuber.from + 0.04);
      if (g <= 0.01) continue;
      d += `M${STEM_X} ${SEED_Y}Q${f((STEM_X + tuber.x) / 2)} ${f(SEED_Y + 4)} ${f(STEM_X + (tuber.x - STEM_X) * g)} ${f(SEED_Y + (tuber.y - SEED_Y) * g)}`;
    }
    return { d };
  });
  const tubers = useAnimatedProps(() => {
    const p = progress.value;
    let d = '';
    for (const tuber of TUBERS) {
      const g = pop(p, tuber.from, 1);
      d += ellipsePath(tuber.x, tuber.y, tuber.rx * g, tuber.ry * g, tuber.angle);
    }
    return { d };
  });
  return (
    <>
      <APath animatedProps={stolons} stroke="#CDB98E" strokeWidth={0.9} strokeLinecap="round" fill="none" />
      <APath animatedProps={tubers} fill="#C79A5B" stroke="#8C6534" strokeWidth={0.6} />
      <APath animatedProps={seed} fill="#B98A50" stroke="#7E5A2E" strokeWidth={0.6} />
      <APath animatedProps={eyes} fill="#6E4E28" />
      <APath animatedProps={sprouts} stroke="#E3D6A6" strokeWidth={1.6} strokeLinecap="round" fill="none" />
      <APath animatedProps={stems} strokeWidth={1.8} strokeLinecap="round" fill="none" />
      <APath animatedProps={canopy} stroke={GREEN.dark} strokeWidth={0.35} />
      <APath animatedProps={flowers} fill="#EDE6F4" stroke="#B9A7CF" strokeWidth={0.3} />
      <APath animatedProps={flowerCenters} fill="#E8C33A" />
    </>
  );
}

export const potatoLoader: PlantLoader = {
  id: 'potato',
  name: 'Potato',
  stages: ['Seed Potato Planted', 'Emergence', 'Canopy Development', 'Flowering', 'Tuber Development', 'Harvest Ready'],
  messages: ['Tubers are forming...', 'The canopy is closing...'],
  accent: '#C79A5B',
  Plant: Potato,
};
