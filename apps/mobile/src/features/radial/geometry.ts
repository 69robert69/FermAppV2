/**
 * Pure wheel geometry. Every function is a worklet, so the focused slice is
 * computed on the UI thread while the finger drags, without a JS round trip.
 * Screen coordinates grow downwards, so positive angles turn clockwise.
 */

export const FULL_TURN = Math.PI * 2;

/** Angle of the centre of a slot. Slot 0 points straight up. */
export function slotAngle(index: number, count: number): number {
  'worklet';
  return -Math.PI / 2 + (index * FULL_TURN) / count;
}

/** Index of the slice in the direction of (dx, dy), or -1 inside the dead zone. */
export function sectorAt(dx: number, dy: number, count: number, deadZone: number): number {
  'worklet';
  if (count <= 0) return -1;
  if (dx * dx + dy * dy < deadZone * deadZone) return -1;
  const step = FULL_TURN / count;
  const fromTop = (((Math.atan2(dy, dx) + Math.PI / 2) % FULL_TURN) + FULL_TURN) % FULL_TURN;
  return Math.floor((fromTop + step / 2) / step) % count;
}

/** The signed number of slots between two indexes, along the shorter way round. */
export function shortestSlotDelta(from: number, to: number, count: number): number {
  'worklet';
  if (count <= 0) return 0;
  let delta = (((to - from) % count) + count) % count;
  if (delta > count / 2) delta -= count;
  return delta;
}

export interface Bounds {
  width: number;
  height: number;
  top: number;
  bottom: number;
}

/** Moves the wheel centre just enough for the whole disc to stay on screen. */
export function clampCenter(x: number, y: number, radius: number, bounds: Bounds, margin = 8) {
  const clampAxis = (value: number, min: number, max: number) => (min > max ? (min + max) / 2 : Math.min(max, Math.max(min, value)));
  return {
    x: clampAxis(x, radius + margin, bounds.width - radius - margin),
    y: clampAxis(y, bounds.top + radius + margin, bounds.height - bounds.bottom - radius - margin),
  };
}

export interface RadialLayout {
  button: number;
  itemRadius: number;
  discRadius: number;
  hubRadius: number;
  /** How far the finger has to move from where it pressed before a slice is chosen. */
  dragDeadZone: number;
}

/** Sizes that keep every button at least 50 dp (thumb friendly) and the wheel on a phone screen. */
export function radialLayout(screenWidth: number, count: number): RadialLayout {
  const button = count > 10 ? 50 : 58;
  const itemRadius = Math.min(122, Math.max(96, screenWidth / 2 - button - 12));
  return {
    button,
    itemRadius,
    discRadius: itemRadius + button / 2 + 20,
    hubRadius: 50,
    dragDeadZone: 34,
  };
}

/** SVG path of the slice centred on the top, between two radii, around (c, c). */
export function wedgePath(c: number, inner: number, outer: number, count: number, gap = 0.035): string {
  const step = FULL_TURN / Math.max(count, 1);
  const start = -Math.PI / 2 - step / 2 + gap;
  const end = -Math.PI / 2 + step / 2 - gap;
  const point = (radius: number, angle: number) => `${(c + radius * Math.cos(angle)).toFixed(2)} ${(c + radius * Math.sin(angle)).toFixed(2)}`;
  const large = end - start > Math.PI ? 1 : 0;
  return [
    `M ${point(outer, start)}`,
    `A ${outer} ${outer} 0 ${large} 1 ${point(outer, end)}`,
    `L ${point(inner, end)}`,
    `A ${inner} ${inner} 0 ${large} 0 ${point(inner, start)}`,
    'Z',
  ].join(' ');
}
