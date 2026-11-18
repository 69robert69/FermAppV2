import { CROPS, rankRotationOptions, CROP_CODES, vegetationHealth } from '@fermapp/domain';
import { getForecast, upcomingHours } from '../../integrations/openMeteo';
import { logger } from '../../lib/logger';
import { prisma } from '../../lib/prisma';
import { WORK_LABELS } from '../work/work.service';

export interface ParcelContext {
  parcel: { name: string; code: string; areaHa: number; locality: string | null; county: string | null };
  year: number;
  currentCrop: string | null;
  cropHistory: { year: number; crop: string; yieldTHa: number | null }[];
  soil: { label: string | null; ph: number | null; organicMatterPct: number | null; clayPct: number | null; waterHoldingMmPerM: number | null } | null;
  vegetation: { date: string; ndvi: number; ndre: number | null; healthIndex: number; label: string } | null;
  recentWork: { date: string; work: string; status: string }[];
  rotationNextYear: { crop: string; score: number; verdict: string; explanation: string }[];
  weatherNext7Days: { precipitationMm: number; tMinC: number; tMaxC: number; maxWindKmh: number } | null;
  currentWindKmh: number | null;
}

/** Datele parcelei, citite doar din ferma utilizatorului autentificat. */
export async function buildParcelContext(farmId: string, parcelId: string): Promise<ParcelContext | null> {
  const parcel = await prisma.parcel.findFirst({
    where: { id: parcelId, farmId },
    include: {
      seasons: { orderBy: { year: 'desc' }, take: 10 },
      soilProfile: true,
      satellite: { orderBy: { date: 'desc' }, take: 1 },
      workSessions: { orderBy: { startedAt: 'desc' }, take: 8 },
    },
  });
  if (!parcel) return null;

  const year = new Date().getFullYear();
  const current = parcel.seasons.find((season) => season.year === year);
  const soil = parcel.soilProfile?.status === 'READY' ? parcel.soilProfile : null;
  const observation = parcel.satellite[0];

  const forecast = await getForecast(parcel.centerLat, parcel.centerLng).catch((error) => {
    logger.warn('Prognoză indisponibilă pentru asistent', (error as Error).message);
    return null;
  });
  const week = forecast?.days.slice(1, 8) ?? [];

  return {
    parcel: {
      name: parcel.name,
      code: parcel.code,
      areaHa: Math.round(parcel.areaHa * 100) / 100,
      locality: parcel.locality,
      county: parcel.county,
    },
    year,
    currentCrop: current ? CROPS[current.crop].name : null,
    cropHistory: parcel.seasons.map((season) => ({
      year: season.year,
      crop: CROPS[season.crop].name,
      yieldTHa: season.yieldT != null ? Math.round((season.yieldT / parcel.areaHa) * 100) / 100 : null,
    })),
    soil: soil
      ? {
          label: soil.label,
          ph: soil.ph,
          organicMatterPct: soil.organicMatterPct,
          clayPct: soil.clayPct,
          waterHoldingMmPerM: soil.waterHoldingMmPerM,
        }
      : null,
    vegetation: observation
      ? {
          date: observation.date.toISOString().slice(0, 10),
          ndvi: observation.ndvi,
          ndre: observation.ndre,
          healthIndex: observation.healthIndex,
          label: vegetationHealth(observation.ndvi, observation.ndre).label,
        }
      : null,
    recentWork: parcel.workSessions.map((work) => ({
      date: work.startedAt.toISOString().slice(0, 10),
      work: WORK_LABELS[work.type],
      status: work.status,
    })),
    rotationNextYear: rankRotationOptions(year + 1, parcel.seasons, CROP_CODES)
      .slice(0, 5)
      .map((option) => ({
        crop: CROPS[option.crop].name,
        score: option.score,
        verdict: option.verdict,
        explanation: option.explanation,
      })),
    weatherNext7Days: week.length
      ? {
          precipitationMm: Math.round(week.reduce((sum, day) => sum + day.precipitationMm, 0) * 10) / 10,
          tMinC: Math.min(...week.map((day) => day.tMinC)),
          tMaxC: Math.max(...week.map((day) => day.tMaxC)),
          maxWindKmh: Math.max(...week.map((day) => day.windMaxKmh ?? 0)),
        }
      : null,
    currentWindKmh: forecast ? (upcomingHours(forecast)[0]?.windKmh ?? null) : null,
  };
}

/** Răspuns calculat din date, folosit când modelul AI nu este configurat sau nu răspunde. */
export function summarizeWithoutModel(context: ParcelContext | null): string {
  if (!context) {
    return 'Alege o parcelă pentru a primi o analiză bazată pe istoricul, solul și prognoza ei.';
  }
  const parts: string[] = [];
  if (context.soil?.ph != null && context.soil.organicMatterPct != null) {
    parts.push(`Parcela ${context.parcel.name} are pH ${context.soil.ph} și materie organică ${context.soil.organicMatterPct}%.`);
  } else {
    parts.push(`Parcela ${context.parcel.name} are ${context.parcel.areaHa} ha, iar profilul de sol nu este încă disponibil.`);
  }
  const last = context.cropHistory.find((season) => season.year < context.year);
  if (context.currentCrop) parts.push(`Cultura din ${context.year} este ${context.currentCrop.toLowerCase()}.`);
  else if (last) parts.push(`Ultima cultură înregistrată este ${last.crop.toLowerCase()}, în ${last.year}.`);

  const good = context.rotationNextYear.filter((option) => option.verdict === 'RECOMMENDED').slice(0, 2);
  if (good.length > 0) {
    parts.push(`Pentru sezonul următor se recomandă ${good.map((option) => option.crop.toLowerCase()).join(' sau ')}.`);
  }
  const avoid = context.rotationNextYear.length === 0 ? null : context.rotationNextYear.at(-1);
  if (avoid && avoid.verdict === 'NOT_RECOMMENDED') parts.push(avoid.explanation);
  if (context.weatherNext7Days) {
    parts.push(
      `În următoarele 7 zile sunt estimate ${context.weatherNext7Days.precipitationMm} mm de precipitații, cu temperaturi între ${Math.round(context.weatherNext7Days.tMinC)} și ${Math.round(context.weatherNext7Days.tMaxC)} °C.`,
    );
  }
  if (context.vegetation) {
    parts.push(`Ultima imagine satelitară (${context.vegetation.date}) arată NDVI ${context.vegetation.ndvi}: ${context.vegetation.label.toLowerCase()}.`);
  }
  return parts.join(' ');
}
