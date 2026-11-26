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
import { announceCompletion } from '../features/operations/store';
import { useAuth } from '../store/auth';

/** Which screens refresh on each event received from the server. */
const INVALIDATIONS: Record<string, QueryKey[]> = {
  'work:updated': [['work'], ['dashboard'], ['intelligence'], mapOverviewKey, ['economics']],
  'irrigation:updated': [['irrigation'], ['dashboard'], ['intelligence'], mapOverviewKey],
  'task:updated': [['tasks'], ['dashboard'], ['intelligence'], mapOverviewKey],
  'alert:new': [['alerts'], ['dashboard'], ['intelligence']],
  'notification:new': [['notifications']],
  'parcel:updated': [['intelligence'], ['parcels'], mapOverviewKey, ['economics']],
};

interface FinishedRun {
  id: string;
  status: string;
  parcel: { name: string };
  type?: string;
}

/** Updates the machine position on the map without reloading all parcels. */
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
    // On Firebase, live updates come directly from Firestore.
    if (!farmId || usesFirebase) return;
    const socket = io(API_ORIGIN, {
      transports: ['websocket'],
      auth: (callback) => callback({ token: useAuth.getState().accessToken }),
    });

    // The server can announce the same completion twice, and the map may have announced it already.
    const announce = (run: FinishedRun, message: string) => {
      if (run.status === 'COMPLETED') announceCompletion(run.id, message);
    };
    socket.on('work:updated', (work: FinishedRun) => {
      announce(work, `${WORK_TYPES[work.type ?? ''] ?? 'The operation'} of parcel ${work.parcel?.name} has been completed.`);
    });
    socket.on('irrigation:updated', (run: FinishedRun) => {
      announce(run, `Irrigation on parcel ${run.parcel?.name} has completed.`);
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
