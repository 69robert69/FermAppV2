import {
  computeGeoMetrics,
  estimateIrrigation,
  estimateWork,
  timedProgress,
  type CropCode,
  type PolygonGeometry,
} from '@fermapp/domain';
import { collection, doc } from 'firebase/firestore';
import { firestore } from './app';
import { useFirebaseSession } from './auth';
import { queueWrites, type PendingWrite } from './outbox';
import type { CollectionName, IrrigationDoc, MachineWorkDoc, NotificationDoc, ParcelDoc, RunState, TaskDoc, UserProfile } from './types';

/** Identificatorul se generează pe telefon, deci documentul poate fi creat și offline. */
export const newId = (name: CollectionName) => doc(collection(firestore, name)).id;

export function session() {
  const { profile } = useFirebaseSession.getState();
  if (!profile) throw new Error('Sesiunea nu este activă.');
  return profile;
}

const nowIso = () => new Date().toISOString();
export const farmFields = () => ({ farmId: session().farmId, createdAt: nowIso() });

export const parcelGeometry = (parcel: Pick<ParcelDoc, 'geometry'>) => JSON.parse(parcel.geometry) as PolygonGeometry;

const geometryFields = (geometry: PolygonGeometry) => {
  const metrics = computeGeoMetrics(geometry);
  return {
    area: metrics.areaHa,
    perimeter: metrics.perimeterM,
    centerLat: metrics.center.lat,
    centerLng: metrics.center.lng,
    coordinates: (geometry.coordinates[0] ?? []).map(([lng, lat]) => ({ lat, lng })),
    geometry: JSON.stringify(geometry),
  };
};

export async function createParcel(input: {
  name: string;
  code?: string;
  geometry: PolygonGeometry;
  county?: string;
  locality?: string;
  notes?: string;
  irrigatedAreaHa?: number | null;
}) {
  const id = newId('parcels');
  await queueWrites([
    {
      kind: 'set',
      collection: 'parcels',
      docId: id,
      data: {
        ...farmFields(),
        ownerId: session().uid,
        name: input.name,
        code: input.code ?? `P-${id.slice(0, 6).toUpperCase()}`,
        ...geometryFields(input.geometry),
        county: input.county ?? null,
        locality: input.locality ?? null,
        notes: input.notes ?? null,
        irrigatedAreaHa: input.irrigatedAreaHa ?? null,
        currentCrop: null,
        plannedCrop: null,
        rotationScore: null,
        irrigationPercent: null,
        ndviScore: null,
        archivedAt: null,
      },
    },
  ]);
  return id;
}

export function updateParcel(id: string, input: Record<string, unknown> & { geometry?: PolygonGeometry }) {
  const { geometry, ...fields } = input;
  return queueWrites([
    { kind: 'update', collection: 'parcels', docId: id, data: { ...fields, ...(geometry ? geometryFields(geometry) : {}) } },
  ]);
}

export const archiveParcel = (id: string) =>
  queueWrites([{ kind: 'update', collection: 'parcels', docId: id, data: { archivedAt: nowIso() } }]);

export const recordCrop = (input: {
  parcelId: string;
  year: number;
  crop: CropCode;
  sowingDate?: string | null;
  harvestDate?: string | null;
  yieldT?: number | null;
  harvestMoisture?: number | null;
  notes?: string | null;
  seedId?: string | null;
  seedName?: string | null;
}) =>
  queueWrites([
    {
      kind: 'set',
      collection: 'cropHistory',
      docId: `${input.parcelId}_${input.year}`,
      data: { ...farmFields(), ...input, sowingDate: input.sowingDate ?? null, harvestDate: input.harvestDate ?? null },
    },
  ]);

export async function createTask(input: Pick<TaskDoc, 'title' | 'type' | 'priority' | 'dueDate'> & Partial<Pick<TaskDoc, 'parcelId' | 'description' | 'responsibleUser'>>) {
  const id = newId('tasks');
  await queueWrites([
    {
      kind: 'set',
      collection: 'tasks',
      docId: id,
      data: {
        ...farmFields(),
        parcelId: null,
        description: null,
        responsibleUser: null,
        ...input,
        status: 'TODO',
        completedAt: null,
        planId: null,
        planStepKey: null,
      },
    },
  ]);
  return id;
}

export const setTaskStatus = (id: string, status: TaskDoc['status']) =>
  queueWrites([{ kind: 'update', collection: 'tasks', docId: id, data: { status, completedAt: status === 'DONE' ? nowIso() : null } }]);

export async function createMachine(input: Record<string, unknown>) {
  const id = newId('machines');
  await queueWrites([
    {
      kind: 'set',
      collection: 'machines',
      docId: id,
      data: {
        ...farmFields(),
        name: input.name,
        type: input.type,
        brand: input.brand ?? null,
        model: input.model ?? null,
        powerHp: input.powerHp ?? null,
        fuelConsumption: input.avgConsumptionLh ?? null,
        operator: input.operatorId ?? null,
        catalogId: input.catalogId ?? null,
        fuelConsumptionMin: input.fuelConsumptionMin ?? null,
        fuelConsumptionMax: input.fuelConsumptionMax ?? null,
        tankCapacity: input.tankCapacity ?? null,
        weight: input.weight ?? null,
        width: null,
        speed: null,
        currentStatus: 'IDLE',
        lastKnownLocation: null,
      },
    },
  ]);
  return id;
}

/** Poziția GPS trimisă de telefonul operatorului în timpul lucrării. */
export const updateWorkLocation = (id: string, location: { lat: number; lng: number; headingDeg?: number | null; speedKmh?: number | null }) =>
  queueWrites([
    {
      kind: 'update',
      collection: 'machineWork',
      docId: id,
      data: {
        lastLocation: { lat: location.lat, lng: location.lng, headingDeg: location.headingDeg ?? null, speedKmh: location.speedKmh ?? null, recordedAt: nowIso() },
      },
    },
  ]);

const pausedFields = { pausedAt: null, pausedMs: 0, finishDate: null };

export async function startWork(input: {
  parcel: Pick<ParcelDoc, 'id' | 'area'>;
  machineId: string | null;
  type: string;
  workingWidthM: number;
  speedKmh: number;
  efficiencyPct: number;
  fuelLPerH?: number | null;
  fuelPricePerL?: number | null;
}) {
  const { parcel, ...params } = input;
  const id = newId('machineWork');
  const { durationHours } = estimateWork(parcel.area, params);
  await queueWrites([
    {
      kind: 'set',
      collection: 'machineWork',
      docId: id,
      data: {
        ...farmFields(),
        ...params,
        ...pausedFields,
        parcelId: parcel.id,
        operatorId: session().uid,
        areaHa: parcel.area,
        durationHours,
        status: 'RUNNING',
        startDate: nowIso(),
      },
    },
  ]);
  return id;
}

export async function startIrrigation(input: {
  parcelId: string;
  type: IrrigationDoc['type'];
  length: number;
  speed: number;
  doseMm: number | null;
}) {
  const id = newId('irrigations');
  const estimate = estimateIrrigation(input.length, input.speed);
  await queueWrites([
    {
      kind: 'set',
      collection: 'irrigations',
      docId: id,
      data: {
        ...farmFields(),
        ...input,
        ...pausedFields,
        durationHours: estimate.durationHours,
        eta: estimate.eta.toISOString(),
        percentCompleted: 0,
        status: 'RUNNING',
        startDate: nowIso(),
      },
    },
  ]);
  return id;
}

type TimedRun = Pick<MachineWorkDoc, 'id' | 'status' | 'startDate' | 'durationHours' | 'pausedAt' | 'pausedMs'>;

export const runProgress = (run: TimedRun, now = new Date()) =>
  timedProgress({
    startedAt: new Date(run.startDate),
    durationMs: run.durationHours * 3_600_000,
    pausedMs: run.pausedMs,
    pausedAt: run.pausedAt ? new Date(run.pausedAt) : null,
    now,
  });

/** Pauză, reluare sau încheiere pentru o lucrare ori o irigare. */
export function controlRun(name: 'machineWork' | 'irrigations', run: TimedRun, action: 'pause' | 'resume' | 'complete' | 'cancel') {
  const now = new Date();
  const finished: Record<string, RunState> = { complete: 'COMPLETED', cancel: 'CANCELLED' };
  const data: Record<string, unknown> =
    action === 'pause'
      ? { status: 'PAUSED', pausedAt: now.toISOString() }
      : action === 'resume'
        ? {
            status: 'RUNNING',
            pausedAt: null,
            pausedMs: run.pausedMs + (run.pausedAt ? now.getTime() - new Date(run.pausedAt).getTime() : 0),
          }
        : { status: finished[action], pausedAt: null, finishDate: now.toISOString() };
  if (name === 'irrigations') data.percentCompleted = runProgress(run, now).percent;
  return queueWrites([{ kind: 'update', collection: name, docId: run.id, data }]);
}

export const addObservation = (parcelId: string, text: string) =>
  queueWrites([
    {
      kind: 'set',
      collection: 'observations',
      docId: newId('observations'),
      data: { ...farmFields(), parcelId, text, authorId: session().uid },
    },
  ]);

export const markNotificationRead = (id: string) =>
  queueWrites([{ kind: 'update', collection: 'notifications', docId: id, data: { readAt: nowIso() } }]);

/** Identificatorul fix împiedică dublarea când mai multe telefoane observă același eveniment. */
export function notificationWrites(
  members: readonly Pick<UserProfile, 'uid'>[],
  eventId: string,
  message: Pick<NotificationDoc, 'title' | 'body' | 'kind'> & Partial<Pick<NotificationDoc, 'parcelId' | 'alertType' | 'severity'>>,
): PendingWrite[] {
  return members.map((member) => ({
    kind: 'once',
    collection: 'notifications',
    docId: `${eventId}_${member.uid}`,
    data: { ...farmFields(), ...message, userId: member.uid, readAt: null },
  }));
}

/** Fără Cloud Functions, primul telefon deschis încheie operațiunile ajunse la termen. */
export async function completeFinishedRuns(input: {
  work: readonly MachineWorkDoc[];
  irrigations: readonly IrrigationDoc[];
  members: readonly UserProfile[];
  parcelName: (parcelId: string) => string;
}) {
  const now = new Date();
  const isDone = (run: TimedRun) => run.status === 'RUNNING' && runProgress(run, now).finished;
  const closing = { status: 'COMPLETED', finishDate: now.toISOString(), pausedAt: null };
  const writes: PendingWrite[] = [];
  for (const run of input.work.filter(isDone)) {
    writes.push({ kind: 'update', collection: 'machineWork', docId: run.id, data: closing });
    writes.push(...notificationWrites(input.members, run.id, { kind: 'WORK_DONE', title: 'Lucrare finalizată', body: `Lucrarea de pe parcela ${input.parcelName(run.parcelId)} s-a încheiat.` }));
  }
  for (const run of input.irrigations.filter(isDone)) {
    writes.push({ kind: 'update', collection: 'irrigations', docId: run.id, data: { ...closing, percentCompleted: 100 } });
    writes.push(...notificationWrites(input.members, run.id, { kind: 'IRRIGATION_DONE', title: 'Irigare finalizată', body: `Irigarea de pe parcela ${input.parcelName(run.parcelId)} s-a încheiat.` }));
  }
  // Fiecare scriere merge separat, ca o notificare deja trimisă să nu blocheze restul.
  for (const write of writes) await queueWrites([write]);
}
