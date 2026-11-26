import {
  buildParcelPlan,
  classifySoil,
  nextSeasonYear,
  OTHER_CROP,
  type CropCode,
  type CropHistoryEntry,
  type LastCrop,
  type ParcelPlanData,
  type PlanClimate,
  type PlanSoil,
} from '@fermapp/domain';
import { fetchJson } from './http';
import { queueWrites, readSnapshot, writeSnapshot, type PendingWrite } from './outbox';
import { farmFields, newId } from './services';
import type { ParcelDoc, TaskDoc } from './types';

const DEPTHS: Record<string, number> = { '0-5cm': 5, '5-15cm': 10, '15-30cm': 15 };
const CLIMATE_YEARS = 5;

interface SoilResponse {
  properties: { layers: { name: string; unit_measure: { d_factor: number }; depths: { label: string; values: { mean: number | null } }[] }[] };
}

/** The full soil profile of the location, from SoilGrids. */
export async function fetchSoilProfile(lat: number, lng: number) {
  const properties = ['phh2o', 'clay', 'sand', 'soc', 'wv0033', 'wv1500'].map((name) => `property=${name}`);
  const depths = Object.keys(DEPTHS).map((depth) => `depth=${depth}`);
  const data = await fetchJson<SoilResponse>(
    `https://rest.isric.org/soilgrids/v2.0/properties/query?lon=${lng.toFixed(5)}&lat=${lat.toFixed(5)}&value=mean&${[...properties, ...depths].join('&')}`,
  );
  const mean = (name: string) => {
    const layer = data.properties.layers.find((item) => item.name === name);
    let sum = 0;
    let weight = 0;
    for (const depth of layer?.depths ?? []) {
      const thickness = DEPTHS[depth.label];
      if (!layer || thickness === undefined || depth.values.mean == null) continue;
      sum += (depth.values.mean / layer.unit_measure.d_factor) * thickness;
      weight += thickness;
    }
    return weight > 0 ? sum / weight : null;
  };
  const [ph, clayPct, sandPct, organicCarbonGKg] = [mean('phh2o'), mean('clay'), mean('sand'), mean('soc')];
  if (ph === null || clayPct === null || sandPct === null || organicCarbonGKg === null) return null;
  const soil = classifySoil({ ph, clayPct, sandPct, organicCarbonGKg, fieldCapacityPct: mean('wv0033'), wiltingPointPct: mean('wv1500') });
  const rounded = (value: number) => Math.round(value * 100) / 100;
  return {
    ph: Math.round(ph * 10) / 10,
    clayPct: rounded(clayPct),
    sandPct: rounded(sandPct),
    siltPct: soil.siltPct,
    organicCarbonGKg: rounded(organicCarbonGKg),
    organicMatterPct: soil.organicMatterPct,
    waterHoldingMmPerM: soil.waterHoldingMmPerM,
    texture: soil.texture,
    soilType: soil.soilType,
    label: soil.label,
  };
}

async function loadSoil(lat: number, lng: number): Promise<PlanSoil | null> {
  const profile = await fetchSoilProfile(lat, lng);
  return profile && { ph: profile.ph, texture: profile.texture, label: profile.label };
}

async function loadClimate(lat: number, lng: number): Promise<PlanClimate | null> {
  const lastYear = new Date().getUTCFullYear() - 1;
  const data = await fetchJson<{ properties: { parameter: { T2M: Record<string, number>; PRECTOTCORR: Record<string, number> } } }>(
    `https://power.larc.nasa.gov/api/temporal/daily/point?parameters=T2M,PRECTOTCORR&community=AG&latitude=${lat.toFixed(4)}&longitude=${lng.toFixed(4)}&start=${lastYear - CLIMATE_YEARS + 1}0101&end=${lastYear}1231&format=JSON`,
  );
  const { T2M, PRECTOTCORR } = data.properties.parameter;
  let rain = 0;
  let degreeDays = 0;
  let seasonSum = 0;
  let seasonDays = 0;
  for (const [day, temperature] of Object.entries(T2M)) {
    const month = Number(day.slice(4, 6));
    rain += Math.max(0, PRECTOTCORR[day] ?? 0);
    degreeDays += Math.max(0, temperature - 10);
    if (temperature > -900 && month >= 4 && month <= 9) {
      seasonSum += temperature;
      seasonDays += 1;
    }
  }
  if (seasonDays === 0) return null;
  return { seasonTempC: Math.round((seasonSum / seasonDays) * 10) / 10, annualPrecipitationMm: Math.round(rain / CLIMATE_YEARS), gddBase10: Math.round(degreeDays / CLIMATE_YEARS) };
}

/** Unavailable sources leave the score indicative, without stopping planning. */
const siteKey = (lat: number, lng: number) => `site:${lat.toFixed(3)}:${lng.toFixed(3)}`;

/** Data already saved. If it is missing, loading starts in the background, without blocking the screen. */
export async function cachedSiteData(lat: number, lng: number) {
  const saved = await readSnapshot<{ soil: PlanSoil | null; climate: PlanClimate | null }>(siteKey(lat, lng));
  if (!saved?.soil || !saved.climate) void loadSiteData(lat, lng).catch(() => undefined);
  return saved ?? { soil: null, climate: null };
}

export async function loadSiteData(lat: number, lng: number) {
  const key = siteKey(lat, lng);
  const saved = await readSnapshot<{ soil: PlanSoil | null; climate: PlanClimate | null }>(key);
  if (saved?.soil && saved.climate) return saved;
  const [soil, climate] = await Promise.all([loadSoil(lat, lng).catch(() => saved?.soil ?? null), loadClimate(lat, lng).catch(() => saved?.climate ?? null)]);
  await writeSnapshot(key, { soil, climate });
  return { soil, climate };
}

type PlanParcel = Pick<ParcelDoc, 'id' | 'name' | 'area' | 'centerLat' | 'centerLng'>;

export interface PlanRequest {
  parcel: PlanParcel;
  plannedCrop: CropCode;
  lastCrop: LastCrop;
  lastCropName?: string;
  history: readonly CropHistoryEntry[];
}

/** Generates the plan on the phone, without saving anything. */
export async function previewPlan(request: PlanRequest, now = new Date()) {
  const { parcel, plannedCrop, lastCrop, lastCropName, history } = request;
  const lastYear = nextSeasonYear(now) - 1;
  const fullHistory = lastCrop === OTHER_CROP ? [...history] : [...history.filter((item) => item.year !== lastYear), { crop: lastCrop, year: lastYear }];
  const site = await loadSiteData(parcel.centerLat, parcel.centerLng);
  return buildParcelPlan({
    crop: plannedCrop,
    parcelName: parcel.name,
    areaHa: parcel.area,
    history: fullHistory,
    lastCropName: lastCrop === OTHER_CROP ? lastCropName?.trim() || 'Other crop' : undefined,
    ...site,
    now,
  });
}

interface ExistingPlan {
  tasks: readonly TaskDoc[];
  activePlanIds: readonly string[];
}

/** Recalculates the plan of an already recorded crop, for example after changing it. */
export async function regeneratePlan(input: ExistingPlan & {
  parcel: PlanParcel;
  crop: CropCode;
  year: number;
  sowingDate: Date | null;
  history: readonly CropHistoryEntry[];
}) {
  const { parcel, crop, year, sowingDate, history, tasks, activePlanIds } = input;
  const site = await loadSiteData(parcel.centerLat, parcel.centerLng);
  const plan = buildParcelPlan({ crop, parcelName: parcel.name, areaHa: parcel.area, history, ...site, year, sowingDate });
  return savePlan({ parcel, lastCrop: null }, plan, { tasks, activePlanIds }, 'CROP_CHANGED');
}

/** Saves the plan and replaces the not-started tasks of the previous plan. */
export async function savePlan(
  target: { parcel: PlanParcel; lastCrop: LastCrop | null },
  plan: ParcelPlanData,
  existing: ExistingPlan,
  trigger: 'WIZARD' | 'CROP_CHANGED' = 'WIZARD',
) {
  const { parcel, lastCrop } = target;
  const isWizard = trigger === 'WIZARD';
  const planId = newId('cropPlans');
  const { expectedProfitEurHa, priceEurPerT, costEurHa, ...publicReport } = plan.report;
  const lastYear = nextSeasonYear() - 1;
  const parcelTasks = existing.tasks.filter((task) => task.parcelId === parcel.id && task.planId);
  const started = new Set(parcelTasks.filter((task) => task.status !== 'TODO').map((task) => task.planStepKey));

  const writes: PendingWrite[] = [
    ...parcelTasks
      .filter((task) => task.status === 'TODO')
      .map((task): PendingWrite => ({ kind: 'delete', collection: 'tasks', docId: task.id })),
    ...existing.activePlanIds.map((id): PendingWrite => ({ kind: 'update', collection: 'cropPlans', docId: id, data: { active: false } })),
    ...(!isWizard || !lastCrop || lastCrop === OTHER_CROP
      ? []
      : [{
          kind: 'set' as const,
          collection: 'cropHistory' as const,
          docId: `${parcel.id}_${lastYear}`,
          data: { ...farmFields(), parcelId: parcel.id, year: lastYear, crop: lastCrop, sowingDate: null, harvestDate: null },
        }]),
    // On a recalculation, the crop already exists and keeps its entered data.
    ...(isWizard
      ? [{
          kind: 'set' as const,
          collection: 'cropHistory' as const,
          docId: `${parcel.id}_${plan.year}`,
          data: { ...farmFields(), parcelId: parcel.id, year: plan.year, crop: plan.crop, sowingDate: null, harvestDate: null },
        }]
      : []),
    {
      kind: 'set',
      collection: 'cropPlans',
      docId: planId,
      data: { ...farmFields(), ...plan, report: publicReport, parcelId: parcel.id, active: true, trigger },
    },
    {
      kind: 'set',
      collection: 'reports',
      docId: planId,
      data: { ...farmFields(), parcelId: parcel.id, planId, year: plan.year, expectedProfitEurHa, priceEurPerT, costEurHa },
    },
    {
      kind: 'update',
      collection: 'parcels',
      docId: parcel.id,
      data: { plannedCrop: plan.crop, rotationScore: plan.rotationScore },
    },
    ...plan.steps
      .filter((step) => !step.past && !started.has(step.key))
      .map((step): PendingWrite => ({
        kind: 'set',
        collection: 'tasks',
        docId: `${planId}_${step.key}`,
        data: {
          ...farmFields(),
          parcelId: parcel.id,
          planId,
          planStepKey: step.key,
          type: step.taskType,
          title: `${step.title}: ${plan.cropName}`,
          description: `Generated automatically from the ${plan.year} technology plan.`,
          priority: step.priority,
          status: 'TODO',
          responsibleUser: null,
          dueDate: step.date,
          completedAt: null,
        },
      })),
  ];
  await queueWrites(writes);
  return planId;
}
