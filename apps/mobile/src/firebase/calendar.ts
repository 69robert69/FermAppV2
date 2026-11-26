import { CROPS, type CalendarDay, type CalendarRun, type CalendarTask, type Resource } from '@fermapp/domain';
import { dueInstant, localDay } from './copilot';
import { liveItems } from './liveStore';
import { queueWrites } from './outbox';
import type { CropHistoryDoc, FarmDoc, IrrigationDoc, MachineDoc, MachineWorkDoc, ParcelDoc, TaskDoc, UserProfile } from './types';
import { getForecast } from './weather';

export interface CalendarData {
  tasks: CalendarTask[];
  runs: CalendarRun[];
  machines: Resource[];
  operators: Resource[];
  members: Resource[];
  parcels: { id: string; name: string; areaHa: number }[];
  seasons: Record<string, { year: number; cropName: string; sowingDate: string | null; harvestDate: string | null }[]>;
}

/** Everything the calendar shows, from the farm data already on the phone. */
export async function loadCalendar(): Promise<CalendarData> {
  const [tasks, work, irrigation, machines, members, parcels, seasons] = await Promise.all([
    liveItems<TaskDoc>('tasks'),
    liveItems<MachineWorkDoc>('machineWork'),
    liveItems<IrrigationDoc>('irrigations'),
    liveItems<MachineDoc>('machines'),
    liveItems<UserProfile & FarmDoc>('users'),
    liveItems<ParcelDoc>('parcels'),
    liveItems<CropHistoryDoc>('cropHistory'),
  ]);
  const people = members.map((member) => ({ id: member.uid, name: member.name, type: member.role }));
  return {
    tasks: tasks.map((task) => ({
      id: task.id,
      title: task.title,
      type: task.type,
      parcelId: task.parcelId,
      priority: task.priority,
      status: task.status,
      day: localDay(task.dueDate),
      assigneeId: task.responsibleUser,
      machineId: task.machineId ?? null,
      source: task.source ?? (task.planId ? 'PLAN' : 'USER'),
    })),
    runs: [
      ...work.map((run): CalendarRun => ({ id: run.id, kind: 'WORK', type: run.type, parcelId: run.parcelId, status: run.status, startDay: localDay(run.startDate), endDay: run.finishDate ? localDay(run.finishDate) : null, machineId: run.machineId, operatorId: run.operatorId })),
      ...irrigation.map((run): CalendarRun => ({ id: run.id, kind: 'IRRIGATION', type: run.type, parcelId: run.parcelId, status: run.status, startDay: localDay(run.startDate), endDay: run.finishDate ? localDay(run.finishDate) : null, machineId: null, operatorId: null })),
    ],
    machines: machines.map((machine) => ({ id: machine.id, name: machine.name, type: machine.type })),
    operators: people.filter((member) => member.type === 'OPERATOR'),
    members: people,
    parcels: parcels.filter((parcel) => !parcel.archivedAt).map((parcel) => ({ id: parcel.id, name: parcel.name, areaHa: parcel.area })),
    seasons: seasons.reduce<CalendarData['seasons']>((byParcel, season) => {
      (byParcel[season.parcelId] ??= []).push({ year: season.year, cropName: CROPS[season.crop].name, sowingDate: season.sowingDate ? localDay(season.sowingDate) : null, harvestDate: season.harvestDate ? localDay(season.harvestDate) : null });
      return byParcel;
    }, {}),
  };
}

/** Daily forecast at the farm centre, for weather warnings on calendar days. */
export async function farmForecastDays(): Promise<CalendarDay[]> {
  const parcels = (await liveItems<ParcelDoc>('parcels')).filter((parcel) => !parcel.archivedAt);
  const area = parcels.reduce((sum, parcel) => sum + parcel.area, 0);
  if (!parcels.length || area <= 0) return [];
  const lat = parcels.reduce((sum, parcel) => sum + parcel.centerLat * parcel.area, 0) / area;
  const lng = parcels.reduce((sum, parcel) => sum + parcel.centerLng * parcel.area, 0) / area;
  const forecast = await getForecast(lat, lng);
  const chance = new Map<string, number>();
  for (const hour of forecast.hours) chance.set(hour.time.slice(0, 10), Math.max(chance.get(hour.time.slice(0, 10)) ?? 0, hour.precipitationProbabilityPct ?? 0));
  return forecast.days.map((day) => ({ date: day.date, tMinC: day.tMinC, tMaxC: day.tMaxC, precipitationMm: day.precipitationMm, rainProbabilityPct: chance.get(day.date) ?? null, windMaxKmh: day.windMaxKmh }));
}

export interface TaskEdit {
  day?: string;
  priority?: CalendarTask['priority'];
  assigneeId?: string | null;
  machineId?: string | null;
  status?: CalendarTask['status'];
}

/** Calendar edits write straight to the task, so the To-Do list updates at once. */
export function updateTask(id: string, edit: TaskEdit) {
  const data: Record<string, unknown> = {};
  if (edit.day) data.dueDate = dueInstant(edit.day);
  if (edit.priority) data.priority = edit.priority;
  if (edit.assigneeId !== undefined) data.responsibleUser = edit.assigneeId;
  if (edit.machineId !== undefined) data.machineId = edit.machineId;
  if (edit.status) {
    data.status = edit.status;
    data.completedAt = edit.status === 'DONE' ? new Date().toISOString() : null;
  }
  return queueWrites([{ kind: 'update', collection: 'tasks', docId: id, data }]);
}
