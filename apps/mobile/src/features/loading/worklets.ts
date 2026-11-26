/**
 * Geometry for the growing plants. Every function is a worklet: the paths are
 * rebuilt on the UI thread each frame, so growth and wind stay at 60 FPS while
 * the app mounts underneath.
 */

export function clamp01(value: number) {
  'worklet';
  return value < 0 ? 0 : value > 1 ? 1 : value;
}

/** Ease-in-out cubic over a progress window. */
export function seg(p: number, from: number, to: number) {
  'worklet';
  const x = clamp01((p - from) / (to - from));
  return x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2;
}

/** Springy ease-out with a small overshoot, for leaves unfolding and petals opening. */
export function pop(p: number, from: number, to: number) {
  'worklet';
  const x = clamp01((p - from) / (to - from));
  const c = 1.4;
  return x === 0 ? 0 : 1 + (c + 1) * Math.pow(x - 1, 3) + c * Math.pow(x - 1, 2);
}

/** Rounds a coordinate so generated path strings stay short. */
export function f(value: number) {
  'worklet';
  return Math.round(value * 100) / 100;
}

/**
 * Sideways wind offset at a point of the plant. `frac` is how high the point
 * sits (0 at the soil, 1 at the top): the base never moves, the top sways most.
 */
export function wind(t: number, motion: number, frac: number, amp: number, phase = 0) {
  'worklet';
  const k = frac * frac * motion;
  return (Math.sin(t * 1.15 + phase) * amp + Math.sin(t * 2.6 + phase * 1.7) * amp * 0.22) * k;
}

/** A stem as a stroke path, gently curved and bent by the wind at its top. */
export function stemPath(x: number, base: number, height: number, lean: number, sway: number) {
  'worklet';
  if (height <= 0.2) return '';
  const top = base - height;
  return `M${f(x)} ${f(base)}Q${f(x + lean * 0.4 + sway * 0.35)} ${f(base - height * 0.55)} ${f(x + lean + sway)} ${f(top)}`;
}

/** The point at `frac` of the stem drawn by `stemPath`, to hang leaves and flowers on. */
export function stemPoint(x: number, base: number, height: number, lean: number, sway: number, frac: number) {
  'worklet';
  const t = frac;
  const cx = x + lean * 0.4 + sway * 0.35;
  const cy = base - height * 0.55;
  const ex = x + lean + sway;
  const ey = base - height;
  const u = 1 - t;
  return [u * u * x + 2 * u * t * cx + t * t * ex, u * u * base + 2 * u * t * cy + t * t * ey] as const;
}

/**
 * A leaf from its base along `angle` (radians, 0 = right, -PI/2 = up). `droop`
 * pulls the tip down, for long arching blades of corn and wheat.
 */
export function leafPath(x: number, y: number, angle: number, length: number, width: number, droop = 0, belly = 0.42) {
  'worklet';
  if (length <= 0.3) return '';
  const ca = Math.cos(angle);
  const sa = Math.sin(angle);
  const tx = x + ca * length;
  const ty = y + sa * length + droop;
  const mx = x + ca * length * belly;
  const my = y + sa * length * belly + droop * 0.25;
  const nx = -sa * width;
  const ny = ca * width;
  return `M${f(x)} ${f(y)}Q${f(mx + nx)} ${f(my + ny)} ${f(tx)} ${f(ty)}Q${f(mx - nx)} ${f(my - ny)} ${f(x)} ${f(y)}Z`;
}

/** The midrib of `leafPath`, drawn as a thin stroke. */
export function ribPath(x: number, y: number, angle: number, length: number, droop = 0, belly = 0.42) {
  'worklet';
  if (length <= 0.3) return '';
  const tx = x + Math.cos(angle) * length;
  const ty = y + Math.sin(angle) * length + droop;
  const mx = x + Math.cos(angle) * length * belly;
  const my = y + Math.sin(angle) * length * belly + droop * 0.25;
  return `M${f(x)} ${f(y)}Q${f(mx)} ${f(my)} ${f(tx * 0.92 + x * 0.08)} ${f(ty * 0.92 + y * 0.08)}`;
}

export function circlePath(x: number, y: number, r: number) {
  'worklet';
  if (r <= 0.05) return '';
  return `M${f(x - r)} ${f(y)}a${f(r)} ${f(r)} 0 1 0 ${f(2 * r)} 0a${f(r)} ${f(r)} 0 1 0 ${f(-2 * r)} 0`;
}

/** A rotated ellipse as a closed path. */
export function ellipsePath(cx: number, cy: number, rx: number, ry: number, angle = 0) {
  'worklet';
  if (rx <= 0.05 || ry <= 0.05) return '';
  const steps = 14;
  const ca = Math.cos(angle);
  const sa = Math.sin(angle);
  let d = '';
  for (let k = 0; k <= steps; k += 1) {
    const a = (k / steps) * Math.PI * 2;
    const px = Math.cos(a) * rx;
    const py = Math.sin(a) * ry;
    d += `${k === 0 ? 'M' : 'L'}${f(cx + px * ca - py * sa)} ${f(cy + px * sa + py * ca)}`;
  }
  return `${d}Z`;
}

/** Three leaflets on a short stalk: soybean, potato and alfalfa leaves. */
export function trifoliatePath(x: number, y: number, angle: number, size: number) {
  'worklet';
  if (size <= 0.3) return '';
  const stalk = size * 0.55;
  const bx = x + Math.cos(angle) * stalk;
  const by = y + Math.sin(angle) * stalk;
  return (
    leafPath(bx, by, angle, size, size * 0.38) +
    leafPath(bx, by, angle - 0.85, size * 0.82, size * 0.32) +
    leafPath(bx, by, angle + 0.85, size * 0.82, size * 0.32)
  );
}

/** Deterministic pseudo-random value in [0, 1), used when building static scene data. */
export function hash(n: number) {
  const x = Math.sin(n * 12.9898) * 43758.5453;
  return x - Math.floor(x);
}
