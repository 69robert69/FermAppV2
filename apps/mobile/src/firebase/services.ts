import {
  computeGeoMetrics,
  estimateIrrigation,
  estimateWork,
  isTillageSystem,
  parseTillageHistory,
  tillageInYear,
  taskForFinishedWork,
  timedProgress,
  withTillageYear,
  type CropCode,
  type PolygonGeometry,
  type TillageSystem,
} from '@fermapp/domain';
import { collection, doc } from 'firebase/firestore';
import { firestore } from './app';
import { useFirebaseSession } from './auth';
import { queueWrites, type PendingWrite } from './outbox';
import type { CollectionName, IrrigationDoc, MachineWorkDoc, NotificationDoc, ParcelDoc, RunState, TaskDoc, UserProfile } from './types';

/** The task a finished operation carries out, closed together with it. */
function linkedTaskWrites(tasks: readonly TaskDoc[] | undefined, run: MachineWorkDoc, now: Date): PendingWrite[] {
  if (!tasks) return [];
  const task = taskForFinishedWork(tasks.filter((item) => item.parcelId === run.parcelId), run.type, now);
  return task ? [{ kind: 'update', collection: 'tasks', docId: task.id, data: { status: 'DONE', completedAt: now.toISOString() } }] : [];
}

/** The identifier is generated on the phone, so the document can be created offline too. */
export const newId = (name: CollectionName) => doc(collection(firestore, name)).id;

export function session() {
  const { profile } = useFirebaseSession.getState();
  if (!profile) throw new Error('The session is not active.');
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
  tillageSystem?: string | null;
}) {
  const id = newId('parcels');
  const tillage = isTillageSystem(input.tillageSystem) ? input.tillageSystem : null;
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
        tillageSystem: tillage,
        tillageHistory: tillage ? [{ year: new Date().getFullYear(), system: tillage }] : [],
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
        tillageSystems: Array.isArray(input.tillageSystems) ? input.tillageSystems.filter(isTillageSystem) : [],
        width: null,
        speed: null,
        currentStatus: 'IDLE',
        lastKnownLocation: null,
      },
    },
  ]);
  return id;
}

/** Sets the technology of a year; the current technology follows the history. */
export function setParcelTillage(parcel: ParcelDoc, system: TillageSystem, year: number) {
  const history = withTillageYear(parseTillageHistory(parcel.tillageHistory), { year, system });
  const current = tillageInYear({ areaHa: parcel.area, system: null, history }, new Date().getFullYear());
  return queueWrites([
    { kind: 'update', collection: 'parcels', docId: parcel.id, data: { tillageSystem: current, tillageHistory: history } },
  ]);
}

/** Changes the editable fields of a machine. */
export function updateMachine(id: string, input: Record<string, unknown>) {
  const data: Record<string, unknown> = {};
  if (Array.isArray(input.tillageSystems)) data.tillageSystems = input.tillageSystems.filter(isTillageSystem);
  for (const key of ['name', 'type', 'brand', 'model', 'powerHp'] as const) if (key in input) data[key] = input[key] ?? null;
  if ('avgConsumptionLh' in input) data.fuelConsumption = input.avgConsumptionLh ?? null;
  if ('operatorId' in input) data.operator = input.operatorId ?? null;
  return queueWrites([{ kind: 'update', collection: 'machines', docId: id, data }]);
}

/** GPS position sent by the operator's phone during the operation. */
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

/** Pause, resume or completion of an operation or an irrigation. */
export function controlRun(name: 'machineWork' | 'irrigations', run: TimedRun, action: 'pause' | 'resume' | 'complete' | 'cancel', tasks?: readonly TaskDoc[]) {
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
  const linked = name === 'machineWork' && action === 'complete' ? linkedTaskWrites(tasks, run as MachineWorkDoc, now) : [];
  return queueWrites([{ kind: 'update', collection: name, docId: run.id, data }, ...linked]);
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

/** The fixed identifier prevents duplicates when several phones observe the same event. */
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

/** Without Cloud Functions, the first open phone completes the operations that are due. */
export async function completeFinishedRuns(input: {
  work: readonly MachineWorkDoc[];
  irrigations: readonly IrrigationDoc[];
  members: readonly UserProfile[];
  parcelName: (parcelId: string) => string;
  tasks?: readonly TaskDoc[];
}) {
  const now = new Date();
  const isDone = (run: TimedRun) => run.status === 'RUNNING' && runProgress(run, now).finished;
  const closing = { status: 'COMPLETED', finishDate: now.toISOString(), pausedAt: null };
  const writes: PendingWrite[] = [];
  for (const run of input.work.filter(isDone)) {
    writes.push({ kind: 'update', collection: 'machineWork', docId: run.id, data: closing });
    writes.push(...linkedTaskWrites(input.tasks, run, now));
    writes.push(...notificationWrites(input.members, run.id, { kind: 'WORK_DONE', title: 'Operation completed', body: `The operation on parcel ${input.parcelName(run.parcelId)} has ended.` }));
  }
  for (const run of input.irrigations.filter(isDone)) {
    writes.push({ kind: 'update', collection: 'irrigations', docId: run.id, data: { ...closing, percentCompleted: 100 } });
    writes.push(...notificationWrites(input.members, run.id, { kind: 'IRRIGATION_DONE', title: 'Irrigation completed', body: `The irrigation on parcel ${input.parcelName(run.parcelId)} has ended.` }));
  }
  // Each write goes separately, so a notification already sent does not block the rest.
  for (const write of writes) await queueWrites([write]);
}
