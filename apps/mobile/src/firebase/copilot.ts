import {
  ECONOMIC_CATEGORIES,
  ECONOMIC_LABELS,
  farmInsights,
  isTillageSystem,
  type CopilotDay,
  type CopilotEconomics,
  type CopilotRun,
  type FarmSnapshot,
  type TaskChange,
  type TaskProposal,
} from '@fermapp/domain';
import { readSatelliteSummary } from '../features/satellite/cache';
import { useSatellitePrefs } from '../features/satellite/prefs';
import { liveItems } from './liveStore';
import { queueWrites } from './outbox';
import { farmFields, newId, notificationWrites, session } from './services';
import type { CropHistoryDoc, FarmDoc, IrrigationDoc, MachineDoc, MachineWorkDoc, ObservationDoc, ParcelDoc, SoilAnalysisDoc, TaskDoc, UserProfile } from './types';
import { getForecast, getHistory, upcomingHours } from './weather';

const DAY_MS = 86_400_000;
const isoDay = (date: Date) => date.toISOString().slice(0, 10);

/** Due dates are stored as instants; the copilot reasons in local calendar days. */
export function localDay(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value.slice(0, 10);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

/** Local noon of a calendar day, so the stored instant stays on that day in every nearby time zone. */
export function dueInstant(day: string): string {
  const [year, month, date] = day.split('-').map(Number);
  return new Date(year!, month! - 1, date!, 12).toISOString();
}

async function farmWeather(parcels: readonly ParcelDoc[]) {
  const area = parcels.reduce((sum, parcel) => sum + (parcel.area || 0), 0);
  if (!parcels.length || area <= 0) return null;
  const lat = parcels.reduce((sum, parcel) => sum + parcel.centerLat * parcel.area, 0) / area;
  const lng = parcels.reduce((sum, parcel) => sum + parcel.centerLng * parcel.area, 0) / area;
  const forecast = await getForecast(lat, lng).catch(() => null);
  if (!forecast) return null;
  const hours = upcomingHours(forecast);
  const today = hours[0]?.time.slice(0, 10) ?? isoDay(new Date());
  const rainChance = new Map<string, number>();
  for (const hour of forecast.hours) {
    const date = hour.time.slice(0, 10);
    rainChance.set(date, Math.max(rainChance.get(date) ?? 0, hour.precipitationProbabilityPct ?? 0));
  }
  const days: CopilotDay[] = forecast.days
    .filter((day) => day.date >= today)
    .slice(0, 10)
    .map((day) => ({ date: day.date, tMinC: day.tMinC, tMaxC: day.tMaxC, precipitationMm: day.precipitationMm, rainProbabilityPct: rainChance.get(day.date) ?? null, windMaxKmh: day.windMaxKmh, et0Mm: day.et0Mm }));
  // The archive lags two days behind; it is cached for hours, so this costs one request a day.
  const end = new Date(Date.now() - 2 * DAY_MS);
  const start = new Date(end.getTime() - 29 * DAY_MS);
  const history = await getHistory(lat, lng, isoDay(start), isoDay(end)).catch(() => null);
  return {
    days,
    hours: hours.slice(0, 120).map(({ time, temperatureC, humidityPct, precipitationMm, precipitationProbabilityPct, windKmh, weatherCode }) => ({ time, temperatureC, humidityPct, precipitationMm, precipitationProbabilityPct, windKmh, weatherCode })),
    past30RainMm: history ? Math.round(history.reduce((sum, day) => sum + day.precipitationMm, 0)) : null,
  };
}

async function economicsByParcel(year: number): Promise<Map<string, CopilotEconomics>> {
  const result = new Map<string, CopilotEconomics>();
  const { farmEconomics } = await import('./economics');
  const farm = await farmEconomics(year).catch(() => null);
  for (const item of farm?.parcels ?? []) {
    const season = item.result.season;
    if (season.total <= 0 && !item.result.expected && !item.result.actual) continue;
    result.set(item.parcelId, {
      year,
      costPerHa: season.perHa,
      totalCost: season.total,
      costShares: Object.fromEntries(
        ECONOMIC_CATEGORIES.filter((category) => season.total > 0 && season.byCategory[category] > 0).map((category) => [ECONOMIC_LABELS[category], Math.round((season.byCategory[category] / season.total) * 100)]),
      ),
      expectedProfitPerHa: item.result.expected?.profitPerHa ?? null,
      actualProfitPerHa: item.result.actual?.profitPerHa ?? null,
      revenuePerHa: item.result.actual?.revenuePerHa ?? item.result.expected?.revenuePerHa ?? null,
    });
  }
  return result;
}

const runStatus = (status: string): CopilotRun['status'] =>
  status === 'RUNNING' || status === 'PAUSED' || status === 'COMPLETED' ? status : 'CANCELLED';

/** Gathers fields, operations, machinery, weather, economics, soil, yields and to-dos into one context. */
export async function buildFarmSnapshot(): Promise<FarmSnapshot> {
  const profile = session();
  const now = new Date();
  const year = now.getFullYear();
  const canSeeFinance = profile.role !== 'OPERATOR';
  const [allParcels, seasons, soils, tasks, work, irrigation, machines, members, observations] = await Promise.all([
    liveItems<ParcelDoc>('parcels'),
    liveItems<CropHistoryDoc>('cropHistory'),
    liveItems<SoilAnalysisDoc>('soilAnalysis'),
    liveItems<TaskDoc>('tasks'),
    liveItems<MachineWorkDoc>('machineWork'),
    liveItems<IrrigationDoc>('irrigations'),
    liveItems<MachineDoc>('machines'),
    liveItems<UserProfile & FarmDoc>('users'),
    liveItems<ObservationDoc>('observations'),
  ]);
  const parcels = allParcels.filter((parcel) => !parcel.archivedAt);
  const satellitePrefs = useSatellitePrefs.getState();
  const withSatellite = satellitePrefs.enabled && satellitePrefs.aiAnalysis;
  const [weather, economics, satellite] = await Promise.all([
    farmWeather(parcels),
    canSeeFinance ? economicsByParcel(year) : Promise.resolve(new Map<string, CopilotEconomics>()),
    // Cached readings only: the copilot never waits for the satellite service.
    withSatellite ? Promise.all(parcels.map(async (parcel) => [parcel.id, (await readSatelliteSummary(parcel.id))?.summary ?? null] as const)).then((rows) => new Map(rows)) : Promise.resolve(new Map()),
  ]);

  return {
    // Local wall-clock time written as ISO, so the copilot's "today" is the phone's calendar day.
    now: new Date(now.getTime() - now.getTimezoneOffset() * 60_000).toISOString(),
    farmName: profile.farmName,
    canSeeFinance,
    weather,
    parcels: parcels.map((parcel) => {
      const soil = soils.find((item) => item.parcelId === parcel.id && item.status === 'READY');
      return {
        id: parcel.id,
        name: parcel.name,
        code: parcel.code ?? null,
        areaHa: parcel.area,
        tillage: isTillageSystem(parcel.tillageSystem) ? parcel.tillageSystem : null,
        irrigatedHa: parcel.irrigatedAreaHa ?? null,
        notes: parcel.notes ?? null,
        seasons: seasons
          .filter((season) => season.parcelId === parcel.id)
          .sort((a, b) => b.year - a.year)
          .map((season) => ({
            year: season.year,
            crop: season.crop,
            sowingDate: season.sowingDate ? localDay(season.sowingDate) : null,
            harvestDate: season.harvestDate ? localDay(season.harvestDate) : null,
            yieldT: season.yieldT ?? null,
            expectedYieldTHa: season.expectedYieldTHa ?? null,
            expectedPricePerT: season.expectedPricePerT ?? null,
            actualPricePerT: season.actualPricePerT ?? null,
          })),
        soil: soil
          ? {
              ph: soil.ph ?? null,
              organicMatterPct: soil.organicMatterPct ?? null,
              clayPct: soil.clayPct ?? null,
              sandPct: soil.sandPct ?? null,
              waterHoldingMmPerM: soil.waterHoldingMmPerM ?? null,
              texture: soil.texture ?? null,
              label: soil.label ?? null,
            }
          : null,
        economics: economics.get(parcel.id) ?? null,
        satellite: satellite.get(parcel.id) ?? null,
        observations: observations
          .filter((item) => item.parcelId === parcel.id)
          .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
          .slice(0, 10)
          .map((item) => ({ date: localDay(item.createdAt), text: item.text })),
      };
    }),
    tasks: tasks.map((task) => ({
      id: task.id,
      parcelId: task.parcelId,
      title: task.title,
      type: task.type,
      priority: task.priority,
      status: task.status,
      dueDate: localDay(task.dueDate),
      assigneeId: task.responsibleUser,
      machineId: task.machineId ?? null,
      source: task.source ?? (task.planId ? 'PLAN' : 'USER'),
    })),
    runs: [
      ...work.map((run): CopilotRun => ({ id: run.id, kind: 'WORK', parcelId: run.parcelId, type: run.type, status: runStatus(run.status), startDate: run.startDate, finishDate: run.finishDate, machineId: run.machineId, operatorId: run.operatorId, areaHa: run.areaHa })),
      ...irrigation.map((run): CopilotRun => ({ id: run.id, kind: 'IRRIGATION', parcelId: run.parcelId, type: run.type, status: runStatus(run.status), startDate: run.startDate, finishDate: run.finishDate, machineId: null, operatorId: null, areaHa: 0, doseMm: run.doseMm })),
    ],
    machines: machines.map((machine) => ({ id: machine.id, name: machine.name, type: machine.type, powerHp: machine.powerHp })),
    members: members.map((member) => ({ id: member.uid, name: member.name, role: member.role })),
  };
}

// ------------------------------------------------------------------ actions

/** A record of what the copilot changed, so every action can be traced and undone. */
export interface CopilotAction {
  id: string;
  at: string;
  kind: 'CREATE' | 'RESCHEDULE' | 'PRIORITY' | 'CANCEL';
  taskId: string;
  title: string;
  parcelId: string | null;
  /** Applied without asking, in fully automatic mode. */
  auto: boolean;
  /** The task as it was before the change, to restore it. */
  before: Partial<TaskDoc> | null;
  summary: string;
  undoneAt: string | null;
}

const actionId = () => `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 7)}`;

export async function createTaskFromProposal(proposal: TaskProposal, auto: boolean): Promise<CopilotAction> {
  const id = newId('tasks');
  const data: Omit<TaskDoc, 'id' | 'farmId' | 'createdAt'> = {
    parcelId: proposal.parcelId,
    title: proposal.title,
    description: proposal.reason,
    type: proposal.type,
    priority: proposal.priority,
    status: 'TODO',
    responsibleUser: proposal.assigneeId,
    dueDate: dueInstant(proposal.dueDate),
    completedAt: null,
    planId: null,
    planStepKey: null,
    source: 'AI',
    category: proposal.category,
    machineId: proposal.machineId,
    windowStart: proposal.window.start,
    windowEnd: proposal.window.end,
    aiReason: proposal.reason,
  };
  await queueWrites([{ kind: 'set', collection: 'tasks', docId: id, data: { ...farmFields(), ...data } }]);
  return { id: actionId(), at: new Date().toISOString(), kind: 'CREATE', taskId: id, title: proposal.title, parcelId: proposal.parcelId, auto, before: null, summary: `Created for ${proposal.dueDate}`, undoneAt: null };
}

export async function applyTaskChange(change: TaskChange, auto: boolean): Promise<CopilotAction> {
  const task = (await liveItems<TaskDoc>('tasks')).find((item) => item.id === change.taskId);
  if (!task) throw new Error('The task no longer exists.');
  const base = { id: actionId(), at: new Date().toISOString(), taskId: task.id, title: task.title, parcelId: task.parcelId, auto, undoneAt: null };
  if (change.kind === 'CANCEL') {
    const { id: _id, ...rest } = task;
    void _id;
    await queueWrites([{ kind: 'delete', collection: 'tasks', docId: task.id }]);
    return { ...base, kind: 'CANCEL', before: rest, summary: 'Cancelled' };
  }
  if (change.kind === 'PRIORITY' && change.toPriority) {
    await queueWrites([{ kind: 'update', collection: 'tasks', docId: task.id, data: { priority: change.toPriority } }]);
    return { ...base, kind: 'PRIORITY', before: { priority: task.priority }, summary: `Priority ${task.priority.toLowerCase()} → ${change.toPriority.toLowerCase()}` };
  }
  if (!change.toDate) throw new Error('The change has no new date.');
  await queueWrites([{ kind: 'update', collection: 'tasks', docId: task.id, data: { dueDate: dueInstant(change.toDate) } }]);
  return { ...base, kind: 'RESCHEDULE', before: { dueDate: task.dueDate }, summary: `Moved ${localDay(task.dueDate)} → ${change.toDate}` };
}

/** Reverses an action: deletes a created task, restores a date or priority, or brings a cancelled task back. */
export async function undoAction(action: CopilotAction) {
  if (action.kind === 'CREATE') {
    await queueWrites([{ kind: 'delete', collection: 'tasks', docId: action.taskId }]);
  } else if (action.kind === 'CANCEL' && action.before) {
    await queueWrites([{ kind: 'set', collection: 'tasks', docId: action.taskId, data: action.before as Record<string, unknown> }]);
  } else if (action.before) {
    await queueWrites([{ kind: 'update', collection: 'tasks', docId: action.taskId, data: action.before as Record<string, unknown> }]);
  }
}

// ------------------------------------------------------------------ proactive notifications

const NOTIFIED_KINDS = new Set(['OVERDUE', 'UPCOMING', 'HARVEST', 'FERTILIZATION', 'IRRIGATION', 'MACHINERY', 'WEATHER']);

/**
 * Without Cloud Functions, open phones of managers and agronomists turn the copilot's
 * important findings into notifications, at most once a day per finding.
 */
export async function notifyCopilotInsights() {
  const profile = session();
  if (profile.role === 'OPERATOR') return;
  const snapshot = await buildFarmSnapshot();
  const members = (await liveItems<UserProfile & FarmDoc>('users')).filter((member) => member.role !== 'OPERATOR');
  const today = localDay(snapshot.now);
  for (const insight of farmInsights(snapshot)) {
    // Upcoming operations are worth a heads-up even though they are not problems.
    if ((insight.severity === 'INFO' && insight.kind !== 'UPCOMING') || !NOTIFIED_KINDS.has(insight.kind)) continue;
    const eventId = `copilot_${insight.kind}_${insight.parcelId ?? 'farm'}_${today}`;
    const writes = notificationWrites(members, eventId, {
      kind: 'RECOMMENDATION',
      title: 'Copilot',
      body: insight.message,
      ...(insight.parcelId ? { parcelId: insight.parcelId } : {}),
      severity: insight.severity === 'CRITICAL' ? 'SEVERE' : 'WARNING',
    });
    for (const write of writes) await queueWrites([write]);
  }
}

