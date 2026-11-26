import {
  askCopilot,
  CROP_CODES,
  EMPTY_MEMORY,
  CROPS,
  computeGeoMetrics,
  cropProgress,
  evaluateRotation,
  isTillageSystem,
  parseTillageHistory,
  nextRecommendedAction,
  nextSeasonYear,
  parcelEconomics,
  rankCropsForParcel,
  rankRotationOptions,
  soilIndices,
  summarizeCosts,
  vegetationHealth,
  waterBalance,
  type CostCategory,
  type CropCode,
  type CropHistoryEntry,
  type LastCrop,
  type NextAction,
  type NextActionInput,
  type ParcelPlanData,
  type PolygonGeometry,
  type WorkOperation,
} from '@fermapp/domain';
import { File } from 'expo-file-system';
import { doc, getDoc } from 'firebase/firestore';
import { ApiError } from '../api/client';
import type { Session } from '../store/auth';
import { TASK_TYPES } from '../lib/format';
import { firestore, auth } from './app';
import { authErrorMessage, inviteMember, joinInvitedFarm, registerFarm, registerInvited, signInWithEmail, signOutFirebase } from './auth';
import { liveItems } from './liveStore';
import { queueWrites, type PendingWrite } from './outbox';
import {
  catalogImplements,
  catalogSeeds,
  catalogTractors,
  deleteEntry,
  importCatalog,
  machineRecommendation,
  OPERATION_BY_TASK,
  sameEntry,
  saveSeed,
  saveTractor,
  seedRanking,
} from './catalog';
import { exportBytes, parseGeoBytes, saveGeneratedFile, type GeoFormat } from './geoFiles';
import { addPhoto, parcelPhotos } from './photos';
import { cachedSiteData, fetchSoilProfile, previewPlan, regeneratePlan, savePlan } from './plans';
import { buildReport, escapeHtml, htmlToPdf, reportToXlsx, type ReportType } from './reports';
import {
  addObservation,
  archiveParcel,
  controlRun,
  createMachine,
  createParcel,
  createTask,
  farmFields,
  newId,
  parcelGeometry,
  recordCrop,
  runProgress,
  session,
  setParcelTillage,
  setTaskStatus,
  startIrrigation,
  startWork,
  updateMachine,
  updateParcel,
  updateWorkLocation,
} from './services';
import { forecastView, getHistory, historyView, parcelWeather, workWindows } from './weather';
import type {
  CollectionName,
  CropHistoryDoc,
  CropPlanDoc,
  ExpenseDoc,
  FarmDoc,
  FarmRole,
  IrrigationDoc,
  MachineDoc,
  MachineWorkDoc,
  NotificationDoc,
  ObservationDoc,
  ParcelDoc,
  ReportDoc,
  SoilAnalysisDoc,
  TaskDoc,
  UserProfile,
} from './types';

type Body = Record<string, any>;
type Member = UserProfile & FarmDoc;

const DAY_MS = 86_400_000;
const SYSTEM_LABELS = { REEL: 'Hose reel', PIVOT: 'Pivot', LINEAR: 'Linear' } as const;
const ACTIVE = new Set(['RUNNING', 'PAUSED']);

export const notMigrated = () => new ApiError(501, 'NOT_MIGRATED', 'This feature is not available on Firebase yet.');

const segments = (path: string) => path.split('?')[0]!.split('/').filter(Boolean);
const isoDate = (value: unknown) => (value ? new Date(value as string).toISOString() : null);

async function requireParcel(id: string) {
  const parcel = (await liveItems<ParcelDoc>('parcels')).find((item) => item.id === id);
  if (!parcel) throw new ApiError(404, 'NOT_FOUND', 'The parcel was not found.');
  return parcel;
}

const seasonsOf = async (parcelId: string) =>
  (await liveItems<CropHistoryDoc>('cropHistory')).filter((item) => item.parcelId === parcelId).sort((a, b) => b.year - a.year);

const historyOf = (seasons: readonly CropHistoryDoc[], beforeYear?: number): CropHistoryEntry[] =>
  seasons.filter((item) => beforeYear === undefined || item.year < beforeYear).map(({ crop, year }) => ({ crop, year }));

/** The soil section, with indices computed when the data is complete. */
function soilView(analysis: SoilAnalysisDoc | undefined) {
  if (!analysis) return { status: 'PENDING' };
  const { ph, organicMatterPct, clayPct, sandPct, waterHoldingMmPerM } = analysis;
  const complete = analysis.status === 'READY' && ph != null && organicMatterPct != null && clayPct != null && sandPct != null && waterHoldingMmPerM != null;
  return { ...analysis, indices: complete ? soilIndices({ ph, organicMatterPct, clayPct, sandPct, waterHoldingMmPerM }) : null };
}

/** Analyses the parcel soil. An error remains visible in the soil section. */
async function refreshSoil(parcel: Pick<ParcelDoc, 'id' | 'centerLat' | 'centerLng'>) {
  const data = await fetchSoilProfile(parcel.centerLat, parcel.centerLng)
    .then((profile) =>
      profile ? { status: 'READY', ...profile, errorMessage: null } : { status: 'FAILED', errorMessage: 'SoilGrids has no data for this location.' },
    )
    .catch(() => ({ status: 'FAILED', errorMessage: 'SoilGrids is not responding right now. Try again later.' }));
  await queueWrites([{ kind: 'set', collection: 'soilAnalysis', docId: parcel.id, data: { ...farmFields(), parcelId: parcel.id, ...data } }]);
  return data;
}

function parcelView(parcel: ParcelDoc, seasons: readonly CropHistoryDoc[], soil?: SoilAnalysisDoc) {
  const year = new Date().getFullYear();
  return {
    id: parcel.id,
    name: parcel.name,
    code: parcel.code,
    geometry: parcelGeometry(parcel),
    areaHa: parcel.area,
    perimeterM: parcel.perimeter,
    centerLat: parcel.centerLat,
    centerLng: parcel.centerLng,
    locality: parcel.locality,
    county: parcel.county,
    notes: parcel.notes ?? null,
    irrigatedAreaHa: parcel.irrigatedAreaHa ?? null,
    tillageSystem: isTillageSystem(parcel.tillageSystem) ? parcel.tillageSystem : null,
    tillageHistory: parseTillageHistory(parcel.tillageHistory),
    archivedAt: parcel.archivedAt,
    seasons: seasons.filter((item) => item.parcelId === parcel.id && item.year === year).map(({ crop, year: seasonYear }) => ({ crop, year: seasonYear })),
    soilProfile: soil ? soilView(soil) : null,
    satellite: [],
  };
}

const seasonView = (season: CropHistoryDoc) => ({
  ...season,
  yieldT: season.yieldT ?? null,
  harvestMoisture: season.harvestMoisture ?? null,
  notes: season.notes ?? null,
});

function progressOf(run: MachineWorkDoc | IrrigationDoc) {
  const active = ACTIVE.has(run.status);
  const progress = runProgress(run, run.finishDate ? new Date(run.finishDate) : new Date());
  return {
    percent: progress.percent,
    elapsedSeconds: Math.round(progress.elapsedMs / 1000),
    remainingSeconds: active ? Math.round(progress.remainingMs / 1000) : 0,
    eta: run.status === 'RUNNING' ? progress.eta.toISOString() : null,
    finished: progress.finished,
  };
}

interface Lookups {
  parcels: readonly ParcelDoc[];
  machines: readonly MachineDoc[];
  members: readonly Member[];
}

const loadLookups = async (): Promise<Lookups> => ({
  parcels: await liveItems<ParcelDoc>('parcels'),
  machines: await liveItems<MachineDoc>('machines'),
  members: await liveItems<Member>('users'),
});

const parcelRef = (id: string, { parcels }: Lookups) => {
  const parcel = parcels.find((item) => item.id === id);
  return { id, name: parcel?.name ?? '', code: parcel?.code ?? '' };
};

/** Diesel consumed so far, based on the consumption set when the operation started. */
function fuelOf(work: MachineWorkDoc, machine: MachineDoc | undefined, elapsedSeconds: number) {
  const perHour = work.fuelLPerH ?? machine?.fuelConsumption;
  return perHour ? Math.round((perHour * elapsedSeconds) / 36) / 100 : null;
}

function workView(work: MachineWorkDoc, lookups: Lookups) {
  const progress = progressOf(work);
  const machine = lookups.machines.find((item) => item.id === work.machineId);
  const operator = lookups.members.find((item) => item.uid === work.operatorId);
  return {
    ...work,
    startedAt: work.startDate,
    progressPct: progress.percent,
    progress,
    parcel: parcelRef(work.parcelId, lookups),
    capacityHaH: work.durationHours > 0 ? work.areaHa / work.durationHours : 0,
    areaDoneHa: Math.round(work.areaHa * progress.percent) / 100,
    fuelLiters: fuelOf(work, machine, progress.elapsedSeconds),
    machine: machine ? { id: machine.id, name: machine.name } : null,
    operator: { id: work.operatorId, fullName: operator?.name ?? '' },
    lastLocation: work.lastLocation ?? null,
  };
}

function irrigationView(run: IrrigationDoc, lookups: Lookups) {
  const progress = progressOf(run);
  return {
    ...run,
    startedAt: run.startDate,
    systemType: run.type,
    routeLengthM: run.length,
    speedMPerH: run.speed,
    progressPct: progress.percent,
    progress,
    parcel: parcelRef(run.parcelId, lookups),
  };
}

function taskView(task: TaskDoc, lookups: Lookups) {
  const assignee = lookups.members.find((item) => item.uid === task.responsibleUser);
  const machine = task.machineId ? lookups.machines.find((item) => item.id === task.machineId) : undefined;
  return {
    ...task,
    parcel: task.parcelId ? parcelRef(task.parcelId, lookups) : null,
    assignee: assignee ? { id: assignee.uid, fullName: assignee.name } : null,
    machine: machine ? { id: machine.id, name: machine.name } : null,
  };
}

/** Looks for the operation in local data, then directly on the server. */
async function findRun<T extends FarmDoc>(name: CollectionName, id: string): Promise<T> {
  const local = (await liveItems<T>(name)).find((item) => item.id === id);
  if (local) return local;
  const snapshot = await getDoc(doc(firestore, name, id)).catch(() => null);
  if (snapshot?.exists()) return { ...(snapshot.data() as T), id };
  throw new ApiError(404, 'NOT_FOUND', 'The operation was not found. Pull the screen down to reload the list.');
}

const byNewest = <T extends { startDate: string }>(items: T[]) => [...items].sort((a, b) => b.startDate.localeCompare(a.startDate));

const labelled = <T extends IrrigationDoc>(runs: readonly T[], lookups: Lookups) =>
  runs.map((run, index) => ({ ...irrigationView(run, lookups), label: `${SYSTEM_LABELS[run.type]} #${index + 1}` }));

/** The last crop declared at setup is not the crop in the field when the plan starts in a future year. */
function fieldSeason(seasons: readonly CropHistoryDoc[], plan: CropPlanDoc | undefined, year: number) {
  const recorded = seasons.find((item) => item.year === year);
  return recorded && plan && plan.year > year && !recorded.sowingDate ? undefined : recorded;
}

const activePlanOf = (plans: readonly CropPlanDoc[], parcelId: string, year: number) =>
  plans.filter((plan) => plan.parcelId === parcelId && plan.active && plan.year >= year).sort((a, b) => a.year - b.year)[0];

async function mapOverview() {
  const now = new Date();
  const year = now.getFullYear();
  const lookups = await loadLookups();
  const [seasons, plans, tasks, work, irrigation] = await Promise.all([
    liveItems<CropHistoryDoc>('cropHistory'),
    liveItems<CropPlanDoc>('cropPlans'),
    liveItems<TaskDoc>('tasks'),
    liveItems<MachineWorkDoc>('machineWork'),
    liveItems<IrrigationDoc>('irrigations'),
  ]);
  return lookups.parcels
    .filter((parcel) => !parcel.archivedAt)
    .map((parcel) => {
      const planned = activePlanOf(plans, parcel.id, year);
      const season = fieldSeason(seasons.filter((item) => item.parcelId === parcel.id), planned, year);
      const code = season?.crop ?? planned?.crop ?? null;
      const progress = season
        ? cropProgress({ crop: season.crop, sowingDate: season.sowingDate ? new Date(season.sowingDate) : null, harvestDate: season.harvestDate ? new Date(season.harvestDate) : null, now })
        : null;
      return {
        parcelId: parcel.id,
        crop: code ? { code, name: CROPS[code].name, planned: !season, year: season?.year ?? planned!.year } : null,
        growthPct: progress && progress.status !== 'NOT_SOWN' ? progress.percent : null,
        openTasks: tasks.filter((task) => task.parcelId === parcel.id && task.status !== 'DONE').length,
        ndvi: parcel.ndviScore,
        work: work.filter((run) => run.parcelId === parcel.id && ACTIVE.has(run.status)).map((run) => workView(run, lookups)),
        irrigation: labelled(irrigation.filter((run) => run.parcelId === parcel.id && ACTIVE.has(run.status)), lookups),
      };
    });
}

async function dashboard() {
  const year = new Date().getFullYear();
  const lookups = await loadLookups();
  const parcels = lookups.parcels.filter((parcel) => !parcel.archivedAt);
  const [seasons, tasks, work, irrigation] = await Promise.all([
    liveItems<CropHistoryDoc>('cropHistory'),
    liveItems<TaskDoc>('tasks'),
    liveItems<MachineWorkDoc>('machineWork'),
    liveItems<IrrigationDoc>('irrigations'),
  ]);
  const areas = new Map<CropCode, number>();
  for (const season of seasons.filter((item) => item.year === year)) {
    const parcel = parcels.find((item) => item.id === season.parcelId);
    if (parcel) areas.set(season.crop, (areas.get(season.crop) ?? 0) + parcel.area);
  }
  const totalAreaHa = parcels.reduce((sum, parcel) => sum + parcel.area, 0);
  const cultivatedAreaHa = [...areas.values()].reduce((sum, area) => sum + area, 0);
  const open = tasks.filter((task) => task.status !== 'DONE');
  const alerts = (await liveItems<NotificationDoc>('notifications'))
    .filter((item) => item.kind === 'WEATHER')
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    .slice(0, 5)
    .map((item) => ({
      id: item.id,
      type: item.alertType ?? 'RAIN',
      severity: item.severity ?? 'WARNING',
      title: item.title,
      message: item.body,
      createdAt: item.createdAt,
      parcel: parcelRef(item.parcelId ?? '', lookups),
    }));
  return {
    year,
    parcels: { count: parcels.length, totalAreaHa, cultivatedAreaHa, freeAreaHa: Math.max(0, totalAreaHa - cultivatedAreaHa) },
    crops: [...areas.entries()].map(([crop, areaHa]) => ({ crop, name: CROPS[crop].name, areaHa })),
    activeWork: work.filter((run) => ACTIVE.has(run.status)).map((run) => workView(run, lookups)),
    activeIrrigation: irrigation.filter((run) => ACTIVE.has(run.status)).map((run) => irrigationView(run, lookups)),
    tasks: { open: open.length, overdue: open.filter((task) => new Date(task.dueDate) < new Date()).length },
    alerts,
  };
}

function nextPlanStep(plan: CropPlanDoc, tasks: readonly TaskDoc[], now: Date): NextAction | null {
  const next = tasks.filter((task) => task.planStepKey && task.status !== 'DONE').sort((a, b) => a.dueDate.localeCompare(b.dueDate))[0];
  if (!next) return null;
  return {
    code: 'PLAN_STEP',
    action: next.title,
    reason: `It is the next step in the technology plan for ${plan.cropName.toLowerCase()}.`,
    priority: 'MEDIUM',
    riskLevel: 'LOW',
    confidencePct: 80,
    dueInDays: Math.max(0, Math.ceil((new Date(next.dueDate).getTime() - now.getTime()) / DAY_MS)),
  };
}

async function intelligence(parcelId: string) {
  const now = new Date();
  const year = now.getFullYear();
  const parcel = await requireParcel(parcelId);
  const lookups = await loadLookups();
  const canSeeFinance = session().role !== 'OPERATOR';
  const [seasons, plans, reports, allTasks, work, irrigation, observations, soil, expenses, photos, weather] = await Promise.all([
    seasonsOf(parcelId),
    liveItems<CropPlanDoc>('cropPlans'),
    canSeeFinance ? liveItems<ReportDoc>('reports') : Promise.resolve([]),
    liveItems<TaskDoc>('tasks'),
    liveItems<MachineWorkDoc>('machineWork'),
    liveItems<IrrigationDoc>('irrigations'),
    liveItems<ObservationDoc>('observations'),
    liveItems<SoilAnalysisDoc>('soilAnalysis'),
    canSeeFinance ? liveItems<ExpenseDoc>('expenses') : Promise.resolve([]),
    parcelPhotos(parcelId),
    parcelWeather(parcel.centerLat, parcel.centerLng).catch(() => null),
  ]);
  const analysis = soil.find((item) => item.parcelId === parcelId);
  if (!analysis) void refreshSoil(parcel);
  const week = weather?.next7 ?? [];
  const mean = (values: number[]) => values.reduce((sum, value) => sum + value, 0) / Math.max(1, values.length);
  const weatherInput: NextActionInput['weather'] = week.length
    ? {
        meanHumidityPct: mean(week.map((day) => day.humidityPct ?? 0)),
        meanTempC: mean(week.map((day) => day.tMeanC ?? (day.tMaxC + day.tMinC) / 2)),
        minTempC: Math.min(...week.map((day) => day.tMinC)),
        rainyDays: week.filter((day) => day.precipitationMm >= 1).length,
        precipitationMm: week.reduce((sum, day) => sum + day.precipitationMm, 0),
        et0Mm: week.reduce((sum, day) => sum + (day.et0Mm ?? 0), 0),
      }
    : null;
  const recentWork = work
    .filter((run) => run.parcelId === parcelId && run.status === 'COMPLETED')
    .map((run) => ({ type: run.type, date: new Date(run.startDate) }));

  const parcelPlans = plans.filter((plan) => plan.parcelId === parcelId).sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  const plan = activePlanOf(parcelPlans, parcelId, year);
  const season = fieldSeason(seasons, plan, year);
  const progress = season
    ? cropProgress({ crop: season.crop, sowingDate: season.sowingDate ? new Date(season.sowingDate) : null, harvestDate: season.harvestDate ? new Date(season.harvestDate) : null, now })
    : null;
  const tasks = allTasks.filter((task) => task.parcelId === parcelId).sort((a, b) => a.dueDate.localeCompare(b.dueDate));
  const count = (status: TaskDoc['status']) => tasks.filter((task) => task.status === status).length;
  const history = historyOf(seasons);
  const current = seasons.find((item) => item.year === year);
  const irrigated = parcel.irrigatedAreaHa == null ? null : Math.min(parcel.irrigatedAreaHa, parcel.area);
  const finance = plan ? reports.find((report) => report.planId === plan.id) : undefined;
  const named = history.map((item) => ({ ...item, name: CROPS[item.crop].name }));
  const cropCode = season?.crop ?? plan?.crop ?? null;
  const ranking = cropCode ? await seedRanking(parcel, cropCode, season?.year ?? plan?.year ?? year) : [];
  const chosenSeason = season ?? seasons.find((item) => item.year === plan?.year);
  const chosen = ranking.find((item) => sameEntry(item.seed, chosenSeason?.seedId));
  const nextTask = tasks.find((task) => task.status !== 'DONE' && OPERATION_BY_TASK[task.type]);
  const operation: WorkOperation | null = nextTask ? OPERATION_BY_TASK[nextTask.type]! : null;

  return {
    generatedAt: now.toISOString(),
    header: { id: parcel.id, name: parcel.name, code: parcel.code, areaHa: parcel.area, county: parcel.county, locality: parcel.locality, lat: parcel.centerLat, lng: parcel.centerLng },
    crop: season && progress ? { seasonId: season.id, code: season.crop, name: CROPS[season.crop].name, year: season.year, sowingDate: season.sowingDate, ...progress } : null,
    rotation: current
      ? { ...evaluateRotation(current.crop, year, history), mode: 'CURRENT', history: named }
      : { ...rankRotationOptions(year, history, CROP_CODES)[0]!, mode: 'SUGGESTED', history: named },
    irrigation: {
      configured: irrigated !== null,
      irrigatedHa: irrigated,
      notIrrigatedHa: irrigated === null ? null : parcel.area - irrigated,
      coveragePct: irrigated === null ? null : Math.round((irrigated / parcel.area) * 100),
    },
    activeIrrigation: labelled(irrigation.filter((run) => run.parcelId === parcelId && ACTIVE.has(run.status)), lookups),
    activeWork: work.filter((run) => run.parcelId === parcelId && ACTIVE.has(run.status)).map((run) => workView(run, lookups)),
    nextAction: (!season && plan ? nextPlanStep(plan, tasks, now) : null) ?? nextRecommendedAction({ crop: season?.crop, progress, weather: weatherInput, recentWork, now }),
    plan: plan ? { id: plan.id, year: plan.year, createdAt: plan.createdAt, data: { ...plan, report: { ...plan.report, ...(finance ? { expectedProfitEurHa: finance.expectedProfitEurHa, priceEurPerT: finance.priceEurPerT, costEurHa: finance.costEurHa } : {}) } } } : null,
    planHistory: parcelPlans.map((item) => ({ id: item.id, year: item.year, cropName: item.cropName, trigger: item.trigger, active: item.active, createdAt: item.createdAt })),
    tasks: { summary: { todo: count('TODO'), inProgress: count('IN_PROGRESS'), done: count('DONE') }, items: tasks.map((task) => taskView(task, lookups)) },
    catalog: {
      cropName: cropCode ? CROPS[cropCode].name : null,
      seasonId: chosenSeason?.id ?? null,
      seed: chosen ? { ...chosen.seed, compatibility: chosen.score, reason: chosen.reason } : null,
      recommended: ranking.slice(0, 3).map((item) => ({ id: item.seed.id, name: item.seed.name, score: item.score, reason: item.reason })),
      operation,
      machine: operation ? await machineRecommendation(parcel.area, operation) : null,
    },
    weather,
    soil: soilView(analysis),
    economics: canSeeFinance ? economicsOf(parcel, seasons.find((item) => item.year === year) ?? null, expenses, year) : null,
    satellite: { latest: null, sevenDaysAgo: null, thirtyDaysAgo: null },
    photos,
    observations: observations
      .filter((item) => item.parcelId === parcelId)
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
      .map((item) => ({ ...item, author: { id: item.authorId, fullName: lookups.members.find((member) => member.uid === item.authorId)?.name ?? '' } })),
  };
}

function economicsOf(parcel: ParcelDoc, season: CropHistoryDoc | null, expenses: readonly ExpenseDoc[], year: number) {
  const costs = expenses.filter((item) => item.parcelId === parcel.id && item.year === year).map(({ category, amount }) => ({ category, amount }));
  return {
    year,
    seasonId: season?.id ?? null,
    expectedYieldTHa: season?.expectedYieldTHa ?? null,
    expectedPricePerT: season?.expectedPricePerT ?? null,
    ...parcelEconomics({
      areaHa: parcel.area,
      costs,
      expectedYieldTHa: season?.expectedYieldTHa,
      expectedPricePerT: season?.expectedPricePerT,
      actualYieldT: season?.yieldT,
      actualPricePerT: season?.actualPricePerT,
    }),
  };
}

async function waterView(parcel: ParcelDoc, year: number) {
  const season = (await seasonsOf(parcel.id)).find((item) => item.year === year);
  if (!season) throw new ApiError(404, 'NOT_FOUND', `There is no crop recorded for ${year}.`);
  // Without sowing and harvest dates, the April-September growing season is used.
  const start = (season.sowingDate ?? new Date(Date.UTC(year, 3, 1)).toISOString()).slice(0, 10);
  const latest = new Date(Date.now() - 2 * DAY_MS).toISOString().slice(0, 10);
  // A sown and unharvested crop receives water up to the present.
  const seasonEnd = (season.harvestDate ?? (season.sowingDate ? new Date().toISOString() : new Date(Date.UTC(year, 8, 30)).toISOString())).slice(0, 10);
  const end = seasonEnd < latest ? seasonEnd : latest;
  const days = start <= end ? await getHistory(parcel.centerLat, parcel.centerLng, start, end) : [];
  const precipitationMm = Math.round(days.reduce((sum, day) => sum + day.precipitationMm, 0) * 10) / 10;
  const runs = (await liveItems<IrrigationDoc>('irrigations')).filter(
    (run) => run.parcelId === parcel.id && run.status === 'COMPLETED' && run.doseMm != null && run.startDate.slice(0, 10) >= start,
  );
  const irrigationMm = Math.round(runs.reduce((sum, run) => sum + (run.doseMm! * progressOf(run).percent) / 100, 0) * 10) / 10;
  return { year, cropName: CROPS[season.crop].name, precipitationMm, irrigationMm, ...waterBalance({ crop: season.crop, precipitationMm, irrigationMm }) };
}

export async function firebaseGet<T>(path: string, params?: Record<string, string | number | undefined>): Promise<T> {
  return (await routeGet(segments(path), params)) as T;
}

async function routeGet(parts: string[], params?: Record<string, string | number | undefined>): Promise<unknown> {
  const [root, id, sub, detail] = parts;
  if (root === 'catalog') {
    if (id === 'seeds') return catalogSeeds();
    if (id === 'tractors') return catalogTractors();
    if (id === 'implements') return catalogImplements();
  }
  if (root === 'economics') {
    const economics = await import('./economics');
    if (id === 'settings') return economics.loadSettings();
    if (id === 'farm') return economics.farmEconomics(Number(params?.year ?? new Date().getFullYear()), params?.parcelId ? String(params.parcelId) : undefined);
    if (id === 'sheets' && sub && detail) return { parcelId: sub, year: Number(detail), sheet: (await economics.loadSheets(Number(detail))).get(sub) ?? (await import('@fermapp/domain')).emptySheet() };
    if (id === 'sheets') {
      const year = Number(params?.year ?? new Date().getFullYear());
      return [...(await economics.loadSheets(year)).entries()].map(([parcelId, sheet]) => ({ parcelId, year, sheet }));
    }
  }
  if (root === 'dashboard') return dashboard();
  if (root === 'map' && id === 'overview') return mapOverview();
  if (root === 'reports' && id) return buildReport(id as ReportType, Number(params?.year ?? new Date().getFullYear()));
  if (root === 'team') {
    return (await liveItems<Member>('users')).map((member) => ({ userId: member.uid, fullName: member.name, email: member.email, role: member.role }));
  }
  if (root === 'machines' && !id) {
    const { machines, members } = await loadLookups();
    return machines.map((machine) => {
      const operator = members.find((member) => member.uid === machine.operator);
      return { ...machine, avgConsumptionLh: machine.fuelConsumption, operator: operator ? { id: operator.uid, fullName: operator.name } : null };
    });
  }
  if (root === 'tasks' && !id) {
    const lookups = await loadLookups();
    return (await liveItems<TaskDoc>('tasks')).sort((a, b) => a.dueDate.localeCompare(b.dueDate)).map((task) => taskView(task, lookups));
  }
  if (root === 'irrigation' && !id) {
    const lookups = await loadLookups();
    return byNewest(await liveItems<IrrigationDoc>('irrigations')).map((run) => irrigationView(run, lookups));
  }
  if (root === 'work') {
    const lookups = await loadLookups();
    const work = await liveItems<MachineWorkDoc>('machineWork');
    if (!id) return byNewest(work).map((run) => workView(run, lookups));
    if (sub === 'location') return work.find((run) => run.id === id)?.lastLocation ?? null;
  }
  if (root === 'parcels') {
    const seasons = await liveItems<CropHistoryDoc>('cropHistory');
    const soil = await liveItems<SoilAnalysisDoc>('soilAnalysis');
    const soilOf = (parcelId: string) => soil.find((item) => item.parcelId === parcelId);
    if (!id) return (await liveItems<ParcelDoc>('parcels')).filter((parcel) => !parcel.archivedAt).sort((a, b) => a.name.localeCompare(b.name)).map((parcel) => parcelView(parcel, seasons, soilOf(parcel.id)));
    if (!sub) {
      const parcel = await requireParcel(id);
      return { ...parcelView(parcel, seasons, soilOf(id)), seasons: (await seasonsOf(id)).map(seasonView) };
    }
    if (sub === 'soil') return { parcelId: id, ...soilView(soilOf(id)) };
    if (sub === 'seed-ranking') {
      return seedRanking(await requireParcel(id), params?.crop as CropCode, Number(params?.year ?? nextSeasonYear()));
    }
    if (sub === 'machine-recommendation') {
      return machineRecommendation((await requireParcel(id)).area, params?.operation as WorkOperation);
    }
    if (sub === 'water') return waterView(await requireParcel(id), Number(params?.year ?? new Date().getFullYear()));
    if (sub === 'satellite') {
      if (detail === 'compare') throw new ApiError(404, 'NOT_FOUND', 'There are no satellite observations for these dates.');
      return { configured: false, observations: [] };
    }
    if (sub === 'weather') {
      const parcel = await requireParcel(id);
      if (detail === 'forecast') return forecastView(parcel.centerLat, parcel.centerLng, String(params?.range ?? '7d'));
      if (detail === 'history') return historyView(parcel.centerLat, parcel.centerLng, String(params?.range ?? '30d'));
      if (detail === 'windows') return workWindows(parcel.centerLat, parcel.centerLng);
    }
    if (sub === 'costs' && detail === 'summary') {
      const parcel = await requireParcel(id);
      const year = Number(params?.year ?? new Date().getFullYear());
      const season = (await seasonsOf(id)).find((item) => item.year === year);
      const costs = (await liveItems<ExpenseDoc>('expenses')).filter((item) => item.parcelId === id && item.year === year);
      return {
        year,
        areaHa: parcel.area,
        crop: season?.crop ?? null,
        ...summarizeCosts({
          areaHa: parcel.area,
          costs: costs.map(({ category, amount }) => ({ category, amount })),
          expectedYieldTHa: season?.expectedYieldTHa,
          expectedPricePerT: season?.expectedPricePerT,
          actualYieldT: season?.yieldT,
          actualPricePerT: season?.actualPricePerT,
        }),
      };
    }
    if (sub === 'seasons') return (await seasonsOf(id)).map(seasonView);
    if (sub === 'intelligence') return intelligence(id);
    if (sub === 'rotation' && detail === 'options') {
      return rankRotationOptions(Number(params?.year ?? new Date().getFullYear() + 1), historyOf(await seasonsOf(id)), CROP_CODES);
    }
    if (sub === 'recommendations') {
      const parcel = await requireParcel(id);
      const year = Number(params?.year ?? new Date().getFullYear() + 1);
      const site = await cachedSiteData(parcel.centerLat, parcel.centerLng);
      return { year, soil: site.soil?.label ?? null, climate: site.climate, recommendations: rankCropsForParcel({ year, history: historyOf(await seasonsOf(id)), ...site }) };
    }
  }
  throw notMigrated();
}

async function login(body: Body): Promise<Session> {
  try {
    const { user } = await signInWithEmail(body.email, body.password);
    const ref = doc(firestore, 'users', user.uid);
    if (!(await getDoc(ref)).exists()) {
      await joinInvitedFarm().catch(() => {
        throw new ApiError(403, 'NOT_INVITED', 'Confirm your email address from the message you received and check that you have an invitation to the farm.');
      });
    }
    return sessionFor((await getDoc(ref)).data() as UserProfile);
  } catch (error) {
    if (error instanceof ApiError) throw error;
    throw new ApiError(400, 'AUTH', authErrorMessage(error));
  }
}

function sessionFor(profile: UserProfile): Session {
  const farm = { id: profile.farmId, name: profile.farmName, role: profile.role };
  return {
    user: { id: profile.uid, email: profile.email, fullName: profile.name },
    farm,
    farms: [farm],
    role: profile.role,
    accessToken: 'firebase',
    refreshToken: 'firebase',
  };
}

async function register(body: Body): Promise<Session> {
  try {
    if (!body.farmName?.trim()) {
      await registerInvited({ name: body.fullName, email: body.email, password: body.password });
      await signOutFirebase();
      throw new ApiError(200, 'VERIFY_EMAIL', 'We sent you a confirmation email. Confirm the address, then sign in.');
    }
    await registerFarm({ name: body.fullName, email: body.email, password: body.password, farmName: body.farmName.trim() });
    const uid = auth.currentUser!.uid;
    return sessionFor((await getDoc(doc(firestore, 'users', uid))).data() as UserProfile);
  } catch (error) {
    if (error instanceof ApiError) throw error;
    throw new ApiError(400, 'AUTH', authErrorMessage(error));
  }
}

async function planContext(parcelId: string) {
  const [tasks, plans] = await Promise.all([liveItems<TaskDoc>('tasks'), liveItems<CropPlanDoc>('cropPlans')]);
  return { tasks: tasks.filter((task) => task.parcelId === parcelId), plans: plans.filter((plan) => plan.parcelId === parcelId && plan.active) };
}

/** The parcel the plan is calculated for: existing or only drawn. */
async function planTarget(body: Body) {
  if (body.parcelId) {
    const parcel = await requireParcel(body.parcelId);
    return { parcel, history: historyOf(await seasonsOf(parcel.id), nextSeasonYear()) };
  }
  const geometry = (body.geometry ?? body.draft?.geometry) as PolygonGeometry;
  const metrics = computeGeoMetrics(geometry);
  const parcel = { id: '', name: body.name ?? body.draft?.name ?? 'New parcel', area: metrics.areaHa, centerLat: metrics.center.lat, centerLng: metrics.center.lng };
  return { parcel, history: [] as CropHistoryEntry[] };
}

async function analyzePlan(body: Body): Promise<ParcelPlanData> {
  const { parcel, history } = await planTarget(body);
  return previewPlan({ parcel, history, plannedCrop: body.plannedCrop as CropCode, lastCrop: body.lastCrop as LastCrop, lastCropName: body.lastCropName });
}

async function completePlan(body: Body) {
  const plan = await analyzePlan(body);
  const { parcel: target } = await planTarget(body);
  const parcelId = body.parcelId ?? (await createParcel(body.draft));
  if (!body.parcelId) void refreshSoil({ ...target, id: parcelId });
  const context = await planContext(parcelId);
  await savePlan(
    { parcel: { ...target, id: parcelId }, lastCrop: body.lastCrop as LastCrop },
    plan,
    { tasks: context.tasks, activePlanIds: context.plans.filter((item) => item.year === plan.year).map((item) => item.id) },
  );
  if (body.seedId) {
    await queueWrites([{ kind: 'update', collection: 'cropHistory', docId: `${parcelId}_${plan.year}`, data: { seedId: body.seedId, seedName: body.seedName ?? null } }]);
  }
  return { parcel: { id: parcelId }, plan };
}

/** Recalculates the plan without keeping the user waiting. */
function replan(parcelId: string, year: number) {
  void (async () => {
    if (year < new Date().getFullYear()) return;
    const [parcel, seasons, context] = await Promise.all([requireParcel(parcelId), seasonsOf(parcelId), planContext(parcelId)]);
    const season = seasons.find((item) => item.year === year);
    if (!season) return;
    await regeneratePlan({
      parcel,
      crop: season.crop,
      year,
      sowingDate: season.sowingDate ? new Date(season.sowingDate) : null,
      history: historyOf(seasons, year),
      tasks: context.tasks,
      activePlanIds: context.plans.filter((item) => item.year === year).map((item) => item.id),
    });
  })().catch((error: unknown) => console.warn('The plan could not be recalculated', error));
}

async function removeSeason(id: string) {
  const seasons = await liveItems<CropHistoryDoc>('cropHistory');
  const season = seasons.find((item) => item.id === id);
  if (!season) throw new ApiError(404, 'NOT_FOUND', 'The crop was not found.');
  const { tasks, plans } = await planContext(season.parcelId);
  const yearPlans = plans.filter((plan) => plan.year === season.year);
  const planIds = new Set(yearPlans.map((plan) => plan.id));
  const writes: PendingWrite[] = [
    { kind: 'delete', collection: 'cropHistory', docId: id },
    ...tasks.filter((task) => task.planId && planIds.has(task.planId) && task.status === 'TODO').map((task): PendingWrite => ({ kind: 'delete', collection: 'tasks', docId: task.id })),
    ...yearPlans.map((plan): PendingWrite => ({ kind: 'update', collection: 'cropPlans', docId: plan.id, data: { active: false } })),
  ];
  await queueWrites(writes);
}

async function updateSeason(id: string, body: Body) {
  const seasons = await liveItems<CropHistoryDoc>('cropHistory');
  const before = seasons.find((item) => item.id === id);
  if (!before) throw new ApiError(404, 'NOT_FOUND', 'The crop was not found.');
  const data = { ...body, ...('sowingDate' in body ? { sowingDate: isoDate(body.sowingDate) } : {}), ...('harvestDate' in body ? { harvestDate: isoDate(body.harvestDate) } : {}) };
  await queueWrites([{ kind: 'update', collection: 'cropHistory', docId: id, data }]);
  const replanned = (body.crop && body.crop !== before.crop) || ('sowingDate' in body && data.sowingDate !== before.sowingDate);
  if (replanned) replan(before.parcelId, before.year);
}

export async function firebaseSend<T>(method: 'POST' | 'PATCH' | 'DELETE', path: string, body: Body = {}): Promise<T> {
  return (await routeSend(method, segments(path), body)) as T;
}

async function routeSend(method: string, parts: string[], body: Body): Promise<unknown> {
  const [root, id, sub, detail] = parts;

  if (root === 'auth') {
    if (id === 'login') return login(body);
    if (id === 'register') return register(body);
    if (id === 'logout') return signOutFirebase();
    throw notMigrated();
  }

  if (root === 'parcels') {
    if (id === 'wizard' && sub === 'analyze') return analyzePlan(body);
    if (id === 'wizard' && sub === 'complete') return completePlan(body);
    if (method === 'POST' && !id) {
      const created = await createParcel(body as Parameters<typeof createParcel>[0]);
      const { center } = computeGeoMetrics(body.geometry as PolygonGeometry);
      void refreshSoil({ id: created, centerLat: center.lat, centerLng: center.lng });
      return { id: created };
    }
    if (id && sub === 'soil') return refreshSoil(await requireParcel(id));
    if (id && sub === 'satellite') {
      throw new ApiError(503, 'NOT_CONFIGURED', 'Satellite imagery needs a Copernicus account and a server to keep its password safe.');
    }
    if (method === 'PATCH' && id && !sub) return updateParcel(id, body);
    if (id && sub === 'tillage') {
      if (!isTillageSystem(body.system)) throw new ApiError(400, 'VALIDATION', 'Choose a tillage technology.');
      return setParcelTillage(await requireParcel(id), body.system, Number(body.year ?? new Date().getFullYear()));
    }
    if (id && sub === 'archive') return archiveParcel(id);
    if (id && sub === 'observations') return addObservation(id, body.text);
    if (id && sub === 'seasons') {
      await recordCrop({
        parcelId: id,
        year: body.year,
        crop: body.crop,
        sowingDate: isoDate(body.sowingDate),
        harvestDate: isoDate(body.harvestDate),
        yieldT: body.yieldT ?? null,
        harvestMoisture: body.harvestMoisture ?? null,
        notes: body.notes ?? null,
        seedId: body.seedId ?? null,
        seedName: body.seedName ?? null,
      });
      replan(id, body.year);
      return { id: `${id}_${body.year}` };
    }
  }

  if (root === 'catalog') {
    const collection = id === 'seeds' ? 'seedCatalog' : 'machineCatalog';
    if (method === 'DELETE' && sub) return deleteEntry(collection, sub);
    if (id === 'seeds') return saveSeed(sub ?? body.id, body);
    if (id === 'tractors') return saveTractor(sub ?? body.id, body);
  }

  if (root === 'ai' && id === 'chat') {
    // The copilot screen calls the engine directly; this keeps the old endpoint answering from the same engine.
    const { buildFarmSnapshot } = await import('./copilot');
    const snapshot = await buildFarmSnapshot();
    const parcel = body.parcelId ? snapshot.parcels.find((item) => item.id === body.parcelId) : undefined;
    const { answer } = askCopilot(String(body.message ?? ''), snapshot, { ...EMPTY_MEMORY, parcelId: parcel?.id ?? null });
    const content = [answer.direct, ...answer.reasoning.slice(0, 3), ...answer.risks.slice(0, 2)].join('\n');
    return { conversationId: 'local', message: { id: `local-${Date.now()}`, content, source: 'rules' } };
  }

  if (root === 'economics' && method === 'POST') {
    const economics = await import('./economics');
    if (id === 'settings') return economics.saveSettings(body);
    if (id === 'sheets' && sub && detail) return economics.saveSheet(sub, Number(detail), body);
  }

  if (root === 'costs' && method === 'POST') {
    const docId = newId('expenses');
    await queueWrites([{
      kind: 'set',
      collection: 'expenses',
      docId,
      data: {
        ...farmFields(),
        parcelId: body.parcelId,
        year: body.year,
        category: body.category as CostCategory,
        amount: body.amount,
        currency: 'EUR',
        date: new Date().toISOString(),
        notes: body.notes ?? null,
      },
    }]);
    return { id: docId };
  }

  if (root === 'seasons' && id) return method === 'DELETE' ? removeSeason(id) : updateSeason(id, body);

  if (root === 'tasks') {
    if (!id) {
      return {
        id: await createTask({
          type: body.type,
          title: body.title ?? TASK_TYPES[body.type] ?? 'Task',
          priority: body.priority,
          dueDate: new Date(body.dueDate).toISOString(),
          parcelId: body.parcelId ?? null,
          responsibleUser: body.assigneeId ?? null,
        }),
      };
    }
    if (sub === 'status') return setTaskStatus(id, body.status);
  }

  if (root === 'machines') {
    if (method === 'POST') return { id: await createMachine(body) };
    if (method === 'PATCH' && id) return updateMachine(id, body);
    if (method === 'DELETE' && id) return queueWrites([{ kind: 'delete', collection: 'machines', docId: id }]);
  }

  if (root === 'work') {
    if (!id) {
      const parcel = await requireParcel(body.parcelId);
      return { id: await startWork({ parcel, machineId: body.machineId ?? null, type: body.type, workingWidthM: body.workingWidthM, speedKmh: body.speedKmh, efficiencyPct: body.efficiencyPct, fuelLPerH: body.fuelLPerH ?? null, fuelPricePerL: body.fuelPricePerL ?? null }) };
    }
    if (sub === 'location') return updateWorkLocation(id, body as Parameters<typeof updateWorkLocation>[1]);
    if (sub === 'pause' || sub === 'resume' || sub === 'complete' || sub === 'cancel') {
      return controlRun('machineWork', await findRun<MachineWorkDoc>('machineWork', id), sub, await liveItems<TaskDoc>('tasks'));
    }
  }

  if (root === 'irrigation') {
    if (!id) {
      return { id: await startIrrigation({ parcelId: body.parcelId, type: body.systemType, length: body.routeLengthM, speed: body.speedMPerH, doseMm: body.doseMm ?? null }) };
    }
    if (sub === 'pause' || sub === 'resume' || sub === 'complete' || sub === 'cancel') {
      return controlRun('irrigations', await findRun<IrrigationDoc>('irrigations', id), sub);
    }
  }

  if (root === 'team' && method === 'POST') return inviteMember(body.email, body.role as FarmRole);

  throw notMigrated();
}

function queryOf(path: string) {
  const pairs = (path.split('?')[1] ?? '').split('&').map((pair) => pair.split('=') as [string, string?]);
  return { get: (name: string) => decodeURIComponent(pairs.find(([key]) => key === name)?.[1] ?? '') || null };
}

/** Imports parcels or attaches a photo, based on the file chosen on the phone. */
export async function firebaseUpload<T>(path: string, file: { uri: string }, fields?: Record<string, string>): Promise<T> {
  const parts = segments(path);
  if (parts[0] === 'catalog' && parts[1] === 'import') {
    return (await importCatalog(queryOf(path).get('kind') === 'tractors' ? 'tractors' : 'seeds', file.uri)) as T;
  }
  if (parts[0] === 'parcels' && parts[2] === 'photos') return (await addPhoto(parts[1]!, file.uri, fields?.notes)) as T;
  if (parts[0] === 'parcels' && parts[1] === 'import') {
    const params = queryOf(path);
    const { parcels, problems } = await parseGeoBytes(params.get('format') as GeoFormat, await new File(file.uri).bytes());
    const preview = parcels.map((parcel) => ({ name: parcel.name, code: parcel.code, areaHa: computeGeoMetrics(parcel.geometry).areaHa }));
    if (params.get('dryRun') === 'true') return { dryRun: true, parcels: preview, problems, created: 0 } as T;
    for (const parcel of parcels) {
      const created = await createParcel(parcel);
      const { center } = computeGeoMetrics(parcel.geometry);
      void refreshSoil({ id: created, centerLat: center.lat, centerLng: center.lng });
    }
    return { dryRun: false, parcels: preview, problems, created: parcels.length } as T;
  }
  throw notMigrated();
}

function parcelReportHtml(data: Awaited<ReturnType<typeof intelligence>>) {
  const row = (label: string, value: unknown) => `<tr><th>${escapeHtml(label)}</th><td>${escapeHtml(value ?? '-')}</td></tr>`;
  const { header, crop, soil, tasks, nextAction, plan } = data;
  return `<h1>Parcel ${escapeHtml(header.name)}</h1><p>${escapeHtml(header.code)}, ${escapeHtml(header.areaHa)} ha</p>
    <table>${row('Crop', crop?.name ?? plan?.data.cropName)}${row('Soil type', soil.status === 'READY' ? (soil as SoilAnalysisDoc).label : null)}${row('Rotation score', data.rotation.score)}${row('Next action', nextAction.action)}${row('Open tasks', tasks.summary.todo)}${row('Completed tasks', tasks.summary.done)}</table>
    ${plan ? `<h2>Technology plan ${escapeHtml(plan.year)}</h2><table>${plan.data.steps.map((step) => row(step.date.slice(0, 10), step.title)).join('')}</table>` : ''}`;
}

/** Generates the requested file on the phone and returns its address. */
export async function firebaseDownload(path: string, filename: string): Promise<string> {
  const parts = segments(path);
  const params = queryOf(path);
  if (parts[0] === 'reports' && parts[1]) {
    const report = await buildReport(parts[1] as ReportType, Number(params.get('year') ?? new Date().getFullYear()));
    const { farmName } = session();
    if (params.get('format') === 'pdf') {
      const { reportToHtml } = await import('../features/reports/pdfReport');
      const requested = params.get('orientation');
      const printed = reportToHtml(report, { farmName, orientation: requested === 'portrait' || requested === 'landscape' ? requested : undefined });
      return htmlToPdf(printed.html, filename, printed.orientation);
    }
    return saveGeneratedFile(filename, await reportToXlsx(report, farmName));
  }
  if (parts[0] === 'parcels' && parts[1] === 'export') {
    const parcels = (await liveItems<ParcelDoc>('parcels')).filter((parcel) => !parcel.archivedAt);
    const rows = parcels.map((parcel) => ({
      name: parcel.name,
      code: parcel.code,
      areaHa: parcel.area,
      perimeterM: parcel.perimeter,
      locality: parcel.locality,
      county: parcel.county,
      notes: parcel.notes ?? null,
      geometry: parcelGeometry(parcel),
    }));
    return saveGeneratedFile(filename, await exportBytes(params.get('format') as GeoFormat, rows));
  }
  if (parts[0] === 'parcels' && parts[2] === 'report') return htmlToPdf(parcelReportHtml(await intelligence(parts[1]!)), filename);
  throw notMigrated();
}
