import type { QueryKey } from '@tanstack/react-query';
import { collection, onSnapshot, query, Timestamp, where, type DocumentData } from 'firebase/firestore';
import { queryClient } from '../api/queryClient';
import { useToast } from '../store/toast';
import { firestore } from './app';
import { ensureCatalogSeeded } from './catalog';
import { observeWrites, pendingSnapshot, queueWrites, readSnapshot, writeSnapshot, type PendingWrite } from './outbox';
import { completeFinishedRuns, notificationWrites } from './services';
import { parcelAlerts } from './weather';
import type { CollectionName, FarmDoc, IrrigationDoc, MachineWorkDoc, NotificationDoc, ParcelDoc, TaskDoc, UserProfile } from './types';

const READY_TIMEOUT_MS = 6000;
const WATCH_INTERVAL_MS = 30_000;
const DUE_SOON_MS = 86_400_000;
const ALERT_INTERVAL_MS = 3 * 3_600_000;
const FIRST_ALERT_DELAY_MS = 10_000;
const MAP: QueryKey = ['map-overview'];
const COPILOT: QueryKey = ['copilot-insights'];
const CALENDAR: QueryKey = ['calendar'];

/** Which screens refresh when a collection changes. */
const REFRESHES: Partial<Record<CollectionName, QueryKey[]>> = {
  users: [['team'], ['economics'], CALENDAR],
  parcels: [['parcels'], ['parcel'], ['dashboard'], ['intelligence'], MAP, ['economics']],
  cropHistory: [['seasons'], ['parcels'], ['parcel'], ['dashboard'], ['intelligence'], ['rotation'], MAP, ['economics'], COPILOT, CALENDAR],
  tasks: [['tasks'], ['dashboard'], ['intelligence'], MAP, COPILOT, CALENDAR, ['calendar-suggestions']],
  machines: [['machines'], ['work'], ['economics'], CALENDAR],
  machineWork: [['work'], ['dashboard'], ['intelligence'], ['machine-location'], MAP, ['economics'], COPILOT, CALENDAR],
  irrigations: [['irrigation'], ['dashboard'], ['intelligence'], MAP, CALENDAR],
  cropPlans: [['intelligence'], MAP],
  reports: [['intelligence']],
  economics: [['economics'], ['report']],
  observations: [['intelligence']],
  expenses: [['costs'], ['intelligence'], ['report'], ['economics']],
  soilAnalysis: [['parcel'], ['intelligence']],
  photos: [['intelligence']],
  notifications: [['dashboard']],
  seedCatalog: [['catalog'], ['intelligence']],
  machineCatalog: [['catalog'], ['intelligence']],
  implementCatalog: [['catalog'], ['intelligence']],
};

function refresh(name: CollectionName) {
  for (const queryKey of REFRESHES[name] ?? []) void queryClient.invalidateQueries({ queryKey });
}

const items = new Map<CollectionName, FarmDoc[]>();
const ready = new Map<CollectionName, Promise<void>>();
const listeners = new Set<() => void>();
let activeProfile: UserProfile | null = null;
let announceStart: (() => void) | null = null;
let started = new Promise<void>((resolve) => {
  announceStart = resolve;
});

function plain(data: DocumentData): DocumentData {
  return Object.fromEntries(
    Object.entries(data).map(([key, value]) => [key, value instanceof Timestamp ? value.toDate().toISOString() : value]),
  );
}

/** Changes waiting to be sent appear immediately in lists. */
function withPending<T extends FarmDoc>(list: readonly T[], writes: readonly PendingWrite[], name: CollectionName) {
  const result = new Map(list.map((item) => [item.id, item]));
  for (const write of writes) {
    if (write.collection !== name) continue;
    if (write.kind === 'delete') result.delete(write.docId);
    else if (write.kind !== 'update' || result.has(write.docId)) {
      result.set(write.docId, { ...result.get(write.docId), ...write.data, id: write.docId } as unknown as T);
    }
  }
  return [...result.values()];
}

export const subscribeLive = (listener: () => void) => {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
};

/** The data available now, including unsent changes. */
export function currentItems<T extends FarmDoc>(name: CollectionName): T[] {
  const list = withPending((items.get(name) ?? []) as T[], pendingSnapshot(), name);
  if (name !== 'notifications') return list;
  return list.filter((item) => (item as unknown as NotificationDoc).userId === activeProfile?.uid);
}

/** Waits for the first load of the collection, from the server or from the phone. */
export async function liveItems<T extends FarmDoc>(name: CollectionName): Promise<T[]> {
  await started;
  await ready.get(name);
  return currentItems<T>(name);
}

function watch(name: CollectionName, profile: UserProfile) {
  const key = `live:${profile.farmId}:${name}`;
  const constraints = [where('farmId', '==', profile.farmId)];
  if (name === 'notifications') constraints.push(where('userId', '==', profile.uid));
  let live = false;
  let first = true;
  // Changes seen without a connection are applied only when the server confirms them.
  let unconfirmed = false;
  let markReady = () => {};
  ready.set(name, new Promise<void>((resolve) => {
    markReady = resolve;
    setTimeout(resolve, READY_TIMEOUT_MS);
  }));

  void readSnapshot<FarmDoc[]>(key).then((saved) => {
    if (!saved || live) return;
    items.set(name, saved);
    markReady();
  });

  return onSnapshot(
    query(collection(firestore, name), ...constraints),
    // Without this option, the server's confirmation for unchanged data would not reach the app.
    { includeMetadataChanges: true },
    (snapshot) => {
      // Without a connection, the Firestore memory is empty and must not replace the data saved on the phone.
      if (snapshot.metadata.fromCache) {
        unconfirmed = true;
        return;
      }
      if (live && !unconfirmed && snapshot.docChanges().length === 0) return;
      unconfirmed = false;
      live = true;
      const next = snapshot.docs.map((item) => ({ ...plain(item.data()), id: item.id }) as FarmDoc);
      items.set(name, next);
      void writeSnapshot(key, next);
      markReady();
      if (name === 'notifications' && !first) {
        for (const change of snapshot.docChanges()) {
          const notification = change.doc.data() as NotificationDoc;
          if (change.type === 'added' && !notification.readAt) useToast.getState().show(notification.body);
        }
      }
      first = false;
      listeners.forEach((listener) => listener());
      refresh(name);
    },
    () => markReady(),
  );
}

/** Without Cloud Functions, open phones complete operations and announce due tasks. */
function watchFarm(profile: UserProfile) {
  const announced = new Set<string>();
  return () => {
    const members = currentItems<UserProfile & FarmDoc>('users');
    const parcels = currentItems<ParcelDoc>('parcels');
    const work = currentItems<MachineWorkDoc>('machineWork');
    if (profile.role !== 'AGRONOMIST') {
      void completeFinishedRuns({
        // Operators can only complete their own operations.
        work: profile.role === 'OPERATOR' ? work.filter((run) => run.operatorId === profile.uid) : work,
        irrigations: currentItems<IrrigationDoc>('irrigations'),
        members,
        parcelName: (id) => parcels.find((parcel) => parcel.id === id)?.name ?? '',
        // Operators may only update tasks assigned to them; the rules reject the rest.
        tasks: currentItems<TaskDoc>('tasks').filter((task) => profile.role !== 'OPERATOR' || task.responsibleUser === profile.uid),
      });
    }
    const now = Date.now();
    for (const task of currentItems<TaskDoc>('tasks')) {
      if (task.status === 'DONE' || !task.responsibleUser || new Date(task.dueDate).getTime() - now > DUE_SOON_MS) continue;
      const eventId = `due_${task.id}_${task.dueDate.slice(0, 10)}`;
      if (announced.has(eventId)) continue;
      announced.add(eventId);
      const recipients = members.filter((member) => member.uid === task.responsibleUser);
      void queueWrites(notificationWrites(recipients, eventId, { kind: 'TASK_DUE', title: 'Task due', body: task.title }));
    }
  };
}

/** Without Cloud Functions, weather alerts are checked from open phones. */
async function checkWeatherAlerts() {
  const members = currentItems<UserProfile & FarmDoc>('users');
  for (const parcel of currentItems<ParcelDoc>('parcels').filter((item) => !item.archivedAt)) {
    const alerts = await parcelAlerts(parcel.name, parcel.centerLat, parcel.centerLng).catch(() => []);
    for (const alert of alerts) {
      const eventId = `alert_${parcel.id}_${alert.type}_${alert.startsAt.slice(0, 10)}`;
      const writes = notificationWrites(members, eventId, {
        kind: 'WEATHER',
        title: alert.title,
        body: alert.message,
        parcelId: parcel.id,
        alertType: alert.type,
        severity: alert.severity,
      });
      for (const write of writes) await queueWrites([write]);
    }
  }
}

/** Weather alerts, then the copilot's findings (overdue work, harvest and nitrogen windows, conflicts). */
async function checkAlerts() {
  await checkWeatherAlerts();
  await import('../features/satellite/background')
    .then(({ refreshSatelliteInBackground }) => refreshSatelliteInBackground())
    .catch((error: unknown) => console.warn('The satellite images could not be refreshed', error));
  await import('./copilot')
    .then(({ notifyCopilotInsights }) => notifyCopilotInsights())
    .catch((error: unknown) => console.warn('The copilot could not check the farm', error));
}

/** Starts listening to the farm data. Screens read from this copy, so they do not consume extra reads. */
export function startLiveStore(profile: UserProfile) {
  activeProfile = profile;
  const names: CollectionName[] = ['users', 'parcels', 'cropHistory', 'tasks', 'machines', 'machineWork', 'irrigations', 'cropPlans', 'observations', 'soilAnalysis', 'notifications', 'seedCatalog', 'machineCatalog', 'implementCatalog'];
  // Security rules hide financial data from operators.
  if (profile.role !== 'OPERATOR') names.push('reports', 'expenses', 'economics');
  observeWrites({
    queued: (writes) => new Set(writes.map((write) => write.collection)).forEach(refresh),
    rejected: (code) => useToast.getState().show(`The last change was not saved on the server (${code}).`),
  });
  const stops = names.map((name) => watch(name, profile));
  announceStart?.();
  const tick = watchFarm(profile);
  const timer = setInterval(tick, WATCH_INTERVAL_MS);
  void ensureCatalogSeeded(profile).catch((error: unknown) => console.warn('The catalog could not be loaded', error));
  // Amounts recorded in lei move to euro once per farm.
  void import('./economics')
    .then(({ migrateFarmToEuro }) => migrateFarmToEuro(profile))
    .catch((error: unknown) => console.warn('The amounts could not be moved to euro', error));
  const firstAlerts = setTimeout(() => void checkAlerts(), FIRST_ALERT_DELAY_MS);
  const alertTimer = setInterval(() => void checkAlerts(), ALERT_INTERVAL_MS);
  return () => {
    clearInterval(timer);
    clearTimeout(firstAlerts);
    clearInterval(alertTimer);
    stops.forEach((stop) => stop());
    items.clear();
    ready.clear();
    activeProfile = null;
    started = new Promise<void>((resolve) => {
      announceStart = resolve;
    });
  };
}
