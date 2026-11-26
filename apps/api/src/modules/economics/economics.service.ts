import {
  computeParcelEconomics,
  CROPS,
  isTillageSystem,
  parseSettings,
  parseSheet,
  summarizeFarmEconomics,
  toEuro,
  type EconomicSettings,
  type EconomicSheet,
  type FarmParcelEconomics,
  type OperationInput,
} from '@fermapp/domain';
import type { Prisma } from '@prisma/client';
import { prisma } from '../../lib/prisma';
import { liveProgress } from '../work/work.service';

export const SETTINGS_KEY = 'settings';
export const sheetKey = (parcelId: string, year: number) => `sheet:${parcelId}:${year}`;

export async function loadSettings(farmId: string): Promise<EconomicSettings> {
  const doc = await prisma.economicDocument.findUnique({ where: { farmId_key: { farmId, key: SETTINGS_KEY } } });
  return parseSettings(doc?.data);
}

export async function saveSettings(farmId: string, settings: EconomicSettings) {
  const data = settings as unknown as Prisma.InputJsonValue;
  const doc = await prisma.economicDocument.upsert({
    where: { farmId_key: { farmId, key: SETTINGS_KEY } },
    create: { farmId, key: SETTINGS_KEY, data },
    update: { data },
  });
  return parseSettings(doc.data);
}

export async function loadSheets(farmId: string, year: number): Promise<Map<string, EconomicSheet>> {
  const docs = await prisma.economicDocument.findMany({ where: { farmId, year, key: { startsWith: 'sheet:' } } });
  return new Map(docs.filter((doc) => doc.parcelId).map((doc) => [doc.parcelId!, parseSheet(doc.data)]));
}

export async function saveSheet(farmId: string, parcelId: string, year: number, sheet: EconomicSheet) {
  const key = sheetKey(parcelId, year);
  const data = sheet as unknown as Prisma.InputJsonValue;
  const doc = await prisma.economicDocument.upsert({
    where: { farmId_key: { farmId, key } },
    create: { farmId, key, parcelId, year, data },
    update: { data },
  });
  return parseSheet(doc.data);
}

/**
 * Real cost and profit of every parcel in a season: the sheet's inputs, the
 * work log priced with the farm's diesel, machine and labour figures, the
 * expenses recorded by category and the operations still to come.
 */
export async function farmEconomics(farmId: string, year: number, parcelId?: string) {
  const start = new Date(Date.UTC(year, 0, 1));
  const end = new Date(Date.UTC(year + 1, 0, 1));
  const [parcels, settings, sheets] = await Promise.all([
    prisma.parcel.findMany({
      where: { farmId, archivedAt: null, ...(parcelId ? { id: parcelId } : {}) },
      include: {
        seasons: { where: { year }, take: 1 },
        costs: { where: { year } },
        workSessions: {
          where: { startedAt: { gte: start, lt: end } },
          include: { machine: { select: { id: true, name: true, type: true, avgConsumptionLh: true } }, operator: { select: { id: true, fullName: true } } },
        },
      },
      orderBy: { name: 'asc' },
    }),
    loadSettings(farmId),
    loadSheets(farmId, year),
  ]);

  const items: FarmParcelEconomics[] = parcels.map((parcel) => {
    const crop = parcel.seasons[0]?.crop ?? null;
    const operations: OperationInput[] = parcel.workSessions.map((work) => {
      const percent = liveProgress(work).percent;
      return {
        id: work.id,
        type: work.type,
        status: work.status,
        areaHa: work.areaHa,
        progressPct: percent,
        // Progress follows the clock, so the hours worked are the same share of the planned duration.
        hours: (work.durationHours * percent) / 100,
        fuelLiters: work.fuelLiters,
        machine: work.machine ? { id: work.machine.id, name: work.machine.name, type: work.machine.type, fuelLPerH: work.machine.avgConsumptionLh } : null,
        operator: { id: work.operator.id, name: work.operator.fullName },
        startedAt: work.startedAt.toISOString(),
      };
    });
    const result = computeParcelEconomics({
      areaHa: parcel.areaHa,
      sheet: sheets.get(parcel.id) ?? parseSheet(null),
      operations,
      settings,
      tillage: isTillageSystem(parcel.tillageSystem) ? parcel.tillageSystem : null,
      crop,
      recorded: parcel.costs.map((cost) => ({ category: cost.category, amount: toEuro(Number(cost.amount), cost.currency) })),
    });
    return {
      parcelId: parcel.id,
      name: parcel.name,
      crop,
      cropName: crop ? CROPS[crop].name : null,
      tillage: isTillageSystem(parcel.tillageSystem) ? parcel.tillageSystem : null,
      result,
    };
  });
  return { year, settings, parcels: items, summary: summarizeFarmEconomics(items) };
}
