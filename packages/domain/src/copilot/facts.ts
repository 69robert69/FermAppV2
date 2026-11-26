import { CROPS, type CropCode, type SoilTexture } from '../crops';
import { cropProgress, soilIndices, type CropProgress } from '../intelligence';
import { CROP_CALENDARS } from '../parcelPlan';
import { TILLAGE_SYSTEMS } from '../tillage';
import { findWorkWindows, type WorkWindow } from '../windows';
import { addDays, daysBetween } from './language';
import type { CopilotParcel, CopilotRun, CopilotSeason, CopilotTask, FarmSnapshot } from './types';

/** What the copilot derives about one parcel before it reasons about it. */
export interface ParcelFacts {
  parcel: CopilotParcel;
  today: string;
  year: number;
  /** The season in progress or planned for this year. */
  season: CopilotSeason | null;
  crop: CropCode | null;
  progress: CropProgress | null;
  /** Earlier seasons, newest first. */
  previous: CopilotSeason[];
  yields: { year: number; crop: CropCode; tHa: number }[];
  /** Mean of recorded yields relative to each crop's reference yield; 1 = typical. */
  productivity: number | null;
  texture: SoilTexture | null;
  soilScore: number | null;
  open: CopilotTask[];
  overdue: CopilotTask[];
  /** Open tasks due within 14 days. */
  upcoming: CopilotTask[];
  done: CopilotTask[];
  runs: CopilotRun[];
  activeRuns: CopilotRun[];
  /** Day of the last finished work of each type. */
  lastWork: Map<string, string>;
  irrigated: boolean;
}

export interface WeatherFacts {
  rain48h: number;
  rain7d: number;
  /** Highest rain probability in the next 48 hours. */
  rainChance48h: number;
  /** Hours until the first hour with meaningful rain, within the forecast. */
  hoursToRain: number | null;
  maxWind48h: number;
  minTemp7d: number | null;
  maxTemp7d: number | null;
  et0_7d: number;
  /** Rain minus reference evapotranspiration over 7 days. */
  balance7d: number;
  past30RainMm: number | null;
  sprayWindows: WorkWindow[];
  harvestWindows: WorkWindow[];
  fertilizeWindows: WorkWindow[];
  /** The first day of the next 7 without meaningful rain and with moderate wind. */
  firstDryDay: string | null;
}

const round1 = (value: number) => Math.round(value * 10) / 10;

export function textureOf(soil: CopilotParcel['soil']): SoilTexture | null {
  if (!soil) return null;
  const text = (soil.texture ?? soil.label ?? '').toLowerCase();
  if (soil.clayPct != null) {
    if (soil.clayPct >= 40) return 'CLAY';
    if (soil.clayPct >= 27) return 'CLAY_LOAM';
    if ((soil.sandPct ?? 0) >= 65) return 'SANDY';
    return 'LOAMY';
  }
  if (text.includes('clay loam')) return 'CLAY_LOAM';
  if (text.includes('clay')) return 'CLAY';
  if (text.includes('sand')) return 'SANDY';
  if (text.includes('loam') || text.includes('silt')) return 'LOAMY';
  return null;
}

export const TEXTURE_LABELS: Record<SoilTexture, string> = { SANDY: 'sandy', LOAMY: 'loamy', CLAY_LOAM: 'clay-loam', CLAY: 'clay' };

export function parcelFacts(snapshot: FarmSnapshot, parcel: CopilotParcel): ParcelFacts {
  const today = snapshot.now.slice(0, 10);
  const year = Number(today.slice(0, 4));
  const now = new Date(snapshot.now);
  // The season in the field: this year's, or one sown and not yet harvested (winter crops).
  const season =
    parcel.seasons.find((item) => item.year === year) ??
    parcel.seasons.find((item) => item.sowingDate && !item.harvestDate && item.year >= year - 1) ??
    null;
  const crop = season?.crop ?? null;
  const progress = season
    ? cropProgress({
        crop: season.crop,
        sowingDate: season.sowingDate ? new Date(season.sowingDate) : null,
        harvestDate: season.harvestDate ? new Date(season.harvestDate) : null,
        now,
      })
    : null;
  const previous = parcel.seasons.filter((item) => item !== season && item.year <= year).sort((a, b) => b.year - a.year);
  const yields = parcel.seasons
    .filter((item) => item.yieldT != null && item.yieldT > 0 && parcel.areaHa > 0)
    .map((item) => ({ year: item.year, crop: item.crop, tHa: round1(item.yieldT! / parcel.areaHa) }));
  const productivity = yields.length
    ? Math.round((yields.reduce((sum, item) => sum + item.tHa / CROPS[item.crop].referenceYieldTHa, 0) / yields.length) * 100) / 100
    : null;
  const soil = parcel.soil;
  const soilScore =
    soil && soil.ph != null && soil.organicMatterPct != null && soil.clayPct != null && soil.sandPct != null && soil.waterHoldingMmPerM != null
      ? soilIndices({ ph: soil.ph, organicMatterPct: soil.organicMatterPct, clayPct: soil.clayPct, sandPct: soil.sandPct, waterHoldingMmPerM: soil.waterHoldingMmPerM }).soilScore
      : parcel.tillage
        ? TILLAGE_SYSTEMS[parcel.tillage].soilHealth
        : null;
  const tasks = snapshot.tasks.filter((task) => task.parcelId === parcel.id);
  const open = tasks.filter((task) => task.status !== 'DONE').sort((a, b) => a.dueDate.localeCompare(b.dueDate));
  const runs = snapshot.runs.filter((run) => run.parcelId === parcel.id);
  const lastWork = new Map<string, string>();
  for (const run of runs) {
    if (run.status !== 'COMPLETED') continue;
    const day = (run.finishDate ?? run.startDate).slice(0, 10);
    if ((lastWork.get(run.type) ?? '') < day) lastWork.set(run.type, day);
  }
  return {
    parcel,
    today,
    year,
    season,
    crop,
    progress,
    previous,
    yields,
    productivity,
    texture: textureOf(soil),
    soilScore,
    open,
    overdue: open.filter((task) => task.dueDate.slice(0, 10) < today),
    upcoming: open.filter((task) => task.dueDate.slice(0, 10) >= today && daysBetween(today, task.dueDate) <= 14),
    done: tasks.filter((task) => task.status === 'DONE'),
    runs,
    activeRuns: runs.filter((run) => run.status === 'RUNNING' || run.status === 'PAUSED'),
    lastWork,
    irrigated: (parcel.irrigatedHa ?? 0) > 0,
  };
}

export function weatherFacts(snapshot: FarmSnapshot): WeatherFacts | null {
  const weather = snapshot.weather;
  if (!weather || !weather.days.length) return null;
  const week = weather.days.slice(0, 7);
  const hours48 = weather.hours.slice(0, 48);
  const rain7d = round1(week.reduce((sum, day) => sum + day.precipitationMm, 0));
  const et0_7d = round1(week.reduce((sum, day) => sum + (day.et0Mm ?? 0), 0));
  const rainIndex = weather.hours.findIndex((hour) => hour.precipitationMm >= 0.5 || (hour.precipitationProbabilityPct ?? 0) >= 60);
  const from = weather.hours[0]?.time;
  return {
    rain48h: round1(hours48.length ? hours48.reduce((sum, hour) => sum + hour.precipitationMm, 0) : week.slice(0, 2).reduce((sum, day) => sum + day.precipitationMm, 0)),
    rain7d,
    rainChance48h: Math.max(0, ...hours48.map((hour) => hour.precipitationProbabilityPct ?? 0), ...week.slice(0, 2).map((day) => day.rainProbabilityPct ?? 0)),
    hoursToRain: rainIndex >= 0 ? rainIndex : null,
    maxWind48h: Math.round(Math.max(0, ...hours48.map((hour) => hour.windKmh), ...week.slice(0, 2).map((day) => day.windMaxKmh ?? 0))),
    minTemp7d: week.length ? Math.min(...week.map((day) => day.tMinC)) : null,
    maxTemp7d: week.length ? Math.max(...week.map((day) => day.tMaxC)) : null,
    et0_7d,
    balance7d: round1(rain7d - et0_7d),
    past30RainMm: weather.past30RainMm,
    sprayWindows: weather.hours.length ? findWorkWindows('SPRAYING', weather.hours, 2, from) : [],
    harvestWindows: weather.hours.length ? findWorkWindows('HARVESTING', weather.hours, 4, from) : [],
    fertilizeWindows: weather.hours.length ? findWorkWindows('FERTILIZING', weather.hours, 3, from) : [],
    firstDryDay: week.find((day) => day.precipitationMm < 1 && (day.windMaxKmh ?? 0) < 25)?.date ?? null,
  };
}

/** The usual day a crop is sown this season, from the planning calendar. */
export function usualSowingDate(crop: CropCode, today: string): string {
  const [month, day] = CROP_CALENDARS[crop].sowing;
  const year = Number(today.slice(0, 4));
  const candidate = `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
  return candidate < today ? `${year + 1}${candidate.slice(4)}` : candidate;
}

/** The expected harvest day: recorded sowing plus the crop calendar, or the progress estimate. */
export function expectedHarvest(facts: ParcelFacts): string | null {
  if (!facts.season || !facts.crop) return null;
  if (facts.season.harvestDate) return facts.season.harvestDate.slice(0, 10);
  if (facts.season.sowingDate) return addDays(facts.season.sowingDate, CROP_CALENDARS[facts.crop].harvestDays);
  return facts.progress?.estimatedHarvestDate?.slice(0, 10) ?? null;
}

export const describeSeason = (season: CopilotSeason) => `${CROPS[season.crop].name.toLowerCase()} (${season.year})`;
