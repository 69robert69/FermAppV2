import { useEffect, useState } from 'react';
import { currentItems, subscribeLive } from './liveStore';
import { subscribeOutbox } from './outbox';
import type { CollectionName, FarmDoc, IrrigationDoc, MachineDoc, MachineWorkDoc, NotificationDoc, ParcelDoc, TaskDoc } from './types';

/** Documentele fermei, actualizate în timp real și disponibile și fără conexiune. */
export function useLiveCollection<T extends FarmDoc>(name: CollectionName): T[] {
  const [data, setData] = useState<T[]>(() => currentItems<T>(name));
  useEffect(() => {
    const refresh = () => setData(currentItems<T>(name));
    refresh();
    const stopLive = subscribeLive(refresh);
    const stopOutbox = subscribeOutbox(refresh);
    return () => {
      stopLive();
      stopOutbox();
    };
  }, [name]);
  return data;
}

export const useLiveParcels = () => useLiveCollection<ParcelDoc>('parcels').filter((parcel) => !parcel.archivedAt);
export const useLiveTasks = () => useLiveCollection<TaskDoc>('tasks');
export const useLiveMachines = () => useLiveCollection<MachineDoc>('machines');
export const useLiveWork = () => useLiveCollection<MachineWorkDoc>('machineWork');
export const useLiveIrrigations = () => useLiveCollection<IrrigationDoc>('irrigations');
export const useLiveNotifications = () => useLiveCollection<NotificationDoc>('notifications');
