export function irrigationDurationHours(routeLengthM: number, speedMPerH: number): number {
  if (routeLengthM <= 0 || speedMPerH <= 0) {
    throw new RangeError('Path length and speed must be positive.');
  }
  return routeLengthM / speedMPerH;
}

export function estimateIrrigation(routeLengthM: number, speedMPerH: number, startAt: Date = new Date()) {
  const durationHours = irrigationDurationHours(routeLengthM, speedMPerH);
  return {
    durationHours: Math.round(durationHours * 100) / 100,
    eta: new Date(startAt.getTime() + durationHours * 3_600_000),
  };
}
