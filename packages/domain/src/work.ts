export interface WorkParams {
  workingWidthM: number;
  speedKmh: number;
  efficiencyPct: number;
}

/** Capacity (ha/hour) = Width (m) × Speed (km/h) × Efficiency / 10. */
export function workCapacityHaH({ workingWidthM, speedKmh, efficiencyPct }: WorkParams): number {
  if (workingWidthM <= 0 || speedKmh <= 0 || efficiencyPct <= 0 || efficiencyPct > 100) {
    throw new RangeError('Width, speed and efficiency (1-100%) must be positive.');
  }
  return (workingWidthM * speedKmh * (efficiencyPct / 100)) / 10;
}

export function workDurationHours(areaHa: number, capacityHaH: number): number {
  if (areaHa <= 0 || capacityHaH <= 0) throw new RangeError('Area and capacity must be positive.');
  return areaHa / capacityHaH;
}

export interface TimedProgressInput {
  startedAt: Date;
  durationMs: number;
  /** Time accumulated in completed pauses. */
  pausedMs?: number;
  /** Moment the current pause started, if the job is paused. */
  pausedAt?: Date | null;
  now?: Date;
}

export interface TimedProgress {
  elapsedMs: number;
  remainingMs: number;
  percent: number;
  eta: Date;
  finished: boolean;
}

/** Progress of an operation with known duration; pauses stop the timer. */
export function timedProgress(input: TimedProgressInput): TimedProgress {
  const now = input.now ?? new Date();
  const reference = input.pausedAt ?? now;
  const elapsedMs = Math.max(
    0,
    Math.min(input.durationMs, reference.getTime() - input.startedAt.getTime() - (input.pausedMs ?? 0)),
  );
  const remainingMs = input.durationMs - elapsedMs;
  return {
    elapsedMs,
    remainingMs,
    percent: input.durationMs > 0 ? Math.round((elapsedMs / input.durationMs) * 1000) / 10 : 100,
    eta: new Date(now.getTime() + remainingMs),
    finished: remainingMs <= 0,
  };
}

export function estimateWork(areaHa: number, params: WorkParams, startAt: Date = new Date()) {
  const capacityHaH = workCapacityHaH(params);
  const durationHours = workDurationHours(areaHa, capacityHaH);
  return {
    capacityHaH: Math.round(capacityHaH * 100) / 100,
    durationHours: Math.round(durationHours * 100) / 100,
    eta: new Date(startAt.getTime() + durationHours * 3_600_000),
  };
}
