import type { QueryKey } from '@tanstack/react-query';
import { useEffect } from 'react';
import { io } from 'socket.io-client';
import { refreshAccessToken } from '../api/client';
import { queryClient } from '../api/queryClient';
import { API_ORIGIN } from '../config';
import { usesFirebase } from '../firebase/config';
import type { ActiveWork, MachinePosition } from '../features/intelligence/api';
import { mapOverviewKey, type MapParcelInfo } from '../features/map/api';
import { WORK_TYPES } from '../lib/format';
import { useAuth } from '../store/auth';
import { useToast } from '../store/toast';

/** Ce ecrane se reîmprospătează la fiecare eveniment primit de la server. */
const INVALIDATIONS: Record<string, QueryKey[]> = {
  'work:updated': [['work'], ['dashboard'], ['intelligence'], mapOverviewKey],
  'irrigation:updated': [['irrigation'], ['dashboard'], ['intelligence'], mapOverviewKey],
  'task:updated': [['tasks'], ['dashboard'], ['intelligence'], mapOverviewKey],
  'alert:new': [['alerts'], ['dashboard'], ['intelligence']],
  'notification:new': [['notifications']],
  'parcel:updated': [['intelligence'], ['parcels'], mapOverviewKey],
};

interface FinishedRun {
  id: string;
  status: string;
  parcel: { name: string };
  type?: string;
}

/** Actualizează poziția utilajului pe hartă fără să reîncarce toate parcelele. */
function placeMachine(position: MachinePosition & { workId: string }) {
  queryClient.setQueryData<MapParcelInfo[]>(mapOverviewKey, (items) =>
    items?.map((item) => ({
      ...item,
      work: item.work.map((work: ActiveWork) => (work.id === position.workId ? { ...work, lastLocation: position } : work)),
    })),
  );
}

export function useRealtime() {
  const farmId = useAuth((state) => state.farm?.id);

  useEffect(() => {
    // Pe Firebase, actualizările live vin direct din Firestore.
    if (!farmId || usesFirebase) return;
    const socket = io(API_ORIGIN, {
      transports: ['websocket'],
      auth: (callback) => callback({ token: useAuth.getState().accessToken }),
    });

    // Serverul poate anunța aceeași finalizare de două ori.
    const announced = new Set<string>();
    const announce = (run: FinishedRun, message: string) => {
      if (run.status !== 'COMPLETED' || announced.has(run.id)) return;
      announced.add(run.id);
      useToast.getState().show(message);
    };
    socket.on('work:updated', (work: FinishedRun) => {
      announce(work, `${WORK_TYPES[work.type ?? ''] ?? 'Lucrarea'} pe parcela ${work.parcel?.name} s-a finalizat.`);
    });
    socket.on('irrigation:updated', (run: FinishedRun) => {
      announce(run, `Irigarea pe parcela ${run.parcel?.name} s-a finalizat.`);
    });

    for (const [event, keys] of Object.entries(INVALIDATIONS)) {
      socket.on(event, () => {
        for (const queryKey of keys) void queryClient.invalidateQueries({ queryKey });
      });
    }
    socket.on('machine:location', (position: MachinePosition & { workId: string }) => {
      queryClient.setQueryData(['machine-location', position.workId], position);
      placeMachine(position);
    });
    socket.on('connect_error', async (error) => {
      if (error.message !== 'UNAUTHORIZED') return;
      const token = await refreshAccessToken().catch(() => null);
      if (token) socket.connect();
    });

    return () => {
      socket.close();
    };
  }, [farmId]);
}
