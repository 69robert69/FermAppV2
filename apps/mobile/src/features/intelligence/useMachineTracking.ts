import { useQuery } from '@tanstack/react-query';
import * as Location from 'expo-location';
import { useEffect } from 'react';
import { apiGet, apiSend } from '../../api/client';
import type { WorkSession } from '../../api/types';
import { useParcels } from '../../lib/hooks';
import { useAuth } from '../../store/auth';

const INTERVAL_MS = 15_000;
const DISTANCE_M = 15;
/** A phone this far from the parcel is not on the machine in the field; its position is not sent. */
const MAX_DISTANCE_FROM_PARCEL_M = 2000;

function metersBetween(lat1: number, lng1: number, lat2: number, lng2: number) {
  const rad = Math.PI / 180;
  const x = (lng2 - lng1) * rad * Math.cos(((lat1 + lat2) / 2) * rad);
  const y = (lat2 - lat1) * rad;
  return Math.hypot(x, y) * 6_371_000;
}

/** While the app is open, the operator's phone sends the machine's position. */
export function useMachineTracking() {
  const userId = useAuth((state) => state.user?.id);
  const work = useQuery({ queryKey: ['work'], queryFn: () => apiGet<WorkSession[]>('/work') });
  const running = work.data?.find((item) => item.status === 'RUNNING' && item.operator.id === userId);
  const workId = running?.id;
  const parcels = useParcels();
  const parcel = parcels.data?.find((item) => item.id === running?.parcel.id);
  const center = parcel ? { lat: parcel.centerLat, lng: parcel.centerLng } : null;
  const centerKey = center ? `${center.lat},${center.lng}` : '';

  useEffect(() => {
    if (!workId) return;
    let cancelled = false;
    let subscription: Location.LocationSubscription | null = null;

    void (async () => {
      const permission = await Location.requestForegroundPermissionsAsync();
      if (!permission.granted || cancelled) return;
      subscription = await Location.watchPositionAsync(
        { accuracy: Location.Accuracy.High, timeInterval: INTERVAL_MS, distanceInterval: DISTANCE_M },
        ({ coords }) => {
          // Whoever starts an operation from the office must not drag the tractor to the office.
          if (center && metersBetween(coords.latitude, coords.longitude, center.lat, center.lng) > MAX_DISTANCE_FROM_PARCEL_M) return;
          void apiSend(
            'POST',
            `/work/${workId}/location`,
            {
              lat: coords.latitude,
              lng: coords.longitude,
              headingDeg: coords.heading != null && coords.heading >= 0 ? coords.heading : null,
              speedKmh: coords.speed != null && coords.speed >= 0 ? coords.speed * 3.6 : null,
              accuracyM: coords.accuracy,
            },
            { offline: false, idempotent: false },
          ).catch(() => undefined);
        },
      );
      if (cancelled) subscription.remove();
    })().catch(() => undefined);

    return () => {
      cancelled = true;
      subscription?.remove();
    };
    // centerKey stands for the parcel centre used above.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workId, centerKey]);
}
