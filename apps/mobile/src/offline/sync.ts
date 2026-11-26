import NetInfo from '@react-native-community/netinfo';
import { create } from 'zustand';
import { ApiError, apiSend } from '../api/client';
import { queryClient } from '../api/queryClient';
import { useAuth } from '../store/auth';
import { failOperation, outboxCounts, pendingOperations, removeOperation } from './db';

interface SyncState {
  online: boolean;
  pending: number;
  failed: number;
  syncing: boolean;
}

export const useSync = create<SyncState>(() => ({ online: true, pending: 0, failed: 0, syncing: false }));

export async function refreshOutboxCounts() {
  useSync.setState(await outboxCounts());
}

let running: Promise<void> | null = null;

/** Sends operations in order; stops at the first connection problem. */
export function flushOutbox(): Promise<void> {
  running ??= (async () => {
    if (!useAuth.getState().accessToken) return;
    useSync.setState({ syncing: true });
    let sent = 0;
    for (const operation of await pendingOperations()) {
      try {
        const result = await apiSend(operation.method, operation.path, operation.body, {
          offline: false,
          operationId: operation.id,
        });
        void result;
        await removeOperation(operation.id);
        sent += 1;
      } catch (error) {
        if (error instanceof ApiError && error.status > 0 && error.status < 500) {
          // The server rejected the operation permanently, so it stays visible to the user.
          await failOperation(operation.id, error.message);
          continue;
        }
        break;
      }
    }
    await refreshOutboxCounts();
    useSync.setState({ syncing: false });
    if (sent > 0) await queryClient.invalidateQueries();
  })().finally(() => {
    running = null;
  });
  return running;
}

export function startSyncListener() {
  void refreshOutboxCounts();
  return NetInfo.addEventListener((state) => {
    const online = state.isConnected !== false && state.isInternetReachable !== false;
    const wasOffline = !useSync.getState().online;
    useSync.setState({ online });
    if (online && wasOffline) void flushOutbox();
  });
}
