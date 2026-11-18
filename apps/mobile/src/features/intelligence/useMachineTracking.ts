import { useQuery } from '@tanstack/react-query';
import * as Location from 'expo-location';
import { useEffect } from 'react';
import { apiGet, apiSend } from '../../api/client';
import type { WorkSession } from '../../api/types';
import { useAuth } from '../../store/auth';

const INTERVAL_MS = 15_000;
const DISTANCE_M = 15;

/** Cât timp aplicația este deschisă, telefonul operatorului transmite poziția utilajului. */
export function useMachineTracking() {
  const userId = useAuth((state) => state.user?.id);
  const work = useQuery({ queryKey: ['work'], queryFn: () => apiGet<WorkSession[]>('/work') });
  const workId = work.data?.find((item) => item.status === 'RUNNING' && item.operator.id === userId)?.id;

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
  }, [workId]);
}
