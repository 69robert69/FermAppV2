import {
  computeParcelEconomics,
  CROPS,
  isTillageSystem,
  parseSettings,
  parseSheet,
  RON_PER_EUR,
  summarizeFarmEconomics,
  toEuro,
  type EconomicSettings,
  type EconomicSheet,
  type FarmParcelEconomics,
  type OperationInput,
} from '@fermapp/domain';
import { doc, runTransaction, serverTimestamp } from 'firebase/firestore';
import { firestore } from './app';
import { liveItems } from './liveStore';
import { queueWrites, type PendingWrite } from './outbox';
import { farmFields, runProgress, session } from './services';
import type { CropHistoryDoc, EconomicDoc, ExpenseDoc, FarmDoc, MachineDoc, MachineWorkDoc, ParcelDoc, UserProfile } from './types';

const settingsId = () => `${session().farmId}__settings`;
const sheetId = (parcelId: string, year: number) => `${session().farmId}__sheet_${parcelId}_${year}`;

export async function loadSettings(): Promise<EconomicSettings> {
  const stored = (await liveItems<EconomicDoc>('economics')).find((item) => item.id === settingsId());
  return parseSettings(stored?.data);
}

export async function saveSettings(input: unknown) {
  const settings = parseSettings(input);
  await queueWrites([{ kind: 'set', collection: 'economics', docId: settingsId(), data: { ...farmFields(), kind: 'settings', parcelId: null, year: null, data: settings } }]);
  return settings;
}

export async function loadSheets(year: number): Promise<Map<string, EconomicSheet>> {
  const docs = (await liveItems<EconomicDoc>('economics')).filter((item) => item.kind === 'sheet' && item.year === year && item.parcelId);
  return new Map(docs.map((item) => [item.parcelId!, parseSheet(item.data)]));
}

export async function saveSheet(parcelId: string, year: number, input: unknown) {
  const sheet = parseSheet(input);
  await queueWrites([{ kind: 'set', collection: 'economics', docId: sheetId(parcelId, year), data: { ...farmFields(), kind: 'sheet', parcelId, year, data: sheet } }]);
  return { parcelId, year, sheet };
}

/** The same calculation as the server: inputs, priced work log, recorded expenses and projected operations. */
export async function farmEconomics(year: number, parcelId?: string) {
  const [parcels, seasons, expenses, work, machines, settings, sheets] = await Promise.all([
    liveItems<ParcelDoc>('parcels'),
    liveItems<CropHistoryDoc>('cropHistory'),
    liveItems<ExpenseDoc>('expenses'),
    liveItems<MachineWorkDoc>('machineWork'),
    liveItems<MachineDoc>('machines'),
    loadSettings(),
    loadSheets(year),
  ]);
  const members = await liveItems<UserProfile & FarmDoc>('users');
  const items: FarmParcelEconomics[] = parcels
    .filter((parcel) => !parcel.archivedAt && (!parcelId || parcel.id === parcelId))
    .sort((a, b) => a.name.localeCompare(b.name))
    .map((parcel) => {
      const crop = seasons.find((season) => season.parcelId === parcel.id && season.year === year)?.crop ?? null;
      const operations: OperationInput[] = work
        .filter((run) => run.parcelId === parcel.id && new Date(run.startDate).getFullYear() === year)
        .map((run) => {
          const progress = runProgress(run, run.finishDate ? new Date(run.finishDate) : new Date());
          const machine = machines.find((item) => item.id === run.machineId);
          const perHour = run.fuelLPerH ?? machine?.fuelConsumption ?? null;
          const hours = progress.elapsedMs / 3_600_000;
          return {
            id: run.id,
            type: run.type,
            status: run.status,
            areaHa: run.areaHa,
            progressPct: progress.percent,
            hours,
            fuelLiters: perHour ? perHour * hours : null,
            machine: machine ? { id: machine.id, name: machine.name, type: machine.type, fuelLPerH: perHour } : null,
            operator: { id: run.operatorId, name: members.find((member) => member.uid === run.operatorId)?.name },
            startedAt: run.startDate,
          };
        });
      const tillage = isTillageSystem(parcel.tillageSystem) ? parcel.tillageSystem : null;
      return {
        parcelId: parcel.id,
        name: parcel.name,
        crop,
        cropName: crop ? CROPS[crop].name : null,
        tillage,
        result: computeParcelEconomics({
          areaHa: parcel.area,
          sheet: sheets.get(parcel.id) ?? parseSheet(null),
          operations,
          settings,
          tillage,
          crop,
          recorded: expenses
            .filter((item) => item.parcelId === parcel.id && item.year === year)
            .map((item) => ({ category: item.category, amount: toEuro(item.amount, item.currency) })),
        }),
      };
    });
  return { year, settings, parcels: items, summary: summarizeFarmEconomics(items) };
}

const toEur = (value: number | null | undefined) => (value == null ? value : Math.round((value / RON_PER_EUR) * 100) / 100);

/**
 * Moves the amounts a farm recorded in lei to euro, once. A marker document is
 * claimed in a transaction, so only one phone converts even if several open the
 * app at the same time. Expenses carry their currency; season prices and the
 * diesel price of past operations were always in lei before this version.
 */
export async function migrateFarmToEuro(profile: UserProfile) {
  if (profile.role !== 'ADMIN' && profile.role !== 'FARM_MANAGER') return;
  const marker = doc(firestore, 'economics', `${profile.farmId}__currency`);
  const claimed = await runTransaction(firestore, async (transaction) => {
    const existing = await transaction.get(marker);
    if (existing.exists()) return false;
    transaction.set(marker, { farmId: profile.farmId, kind: 'currency', currency: 'EUR', ronPerEur: RON_PER_EUR, migratedBy: profile.uid, createdAt: serverTimestamp() });
    return true;
  });
  if (!claimed) return;

  const [expenses, seasons, work] = await Promise.all([
    liveItems<ExpenseDoc>('expenses'),
    liveItems<CropHistoryDoc>('cropHistory'),
    liveItems<MachineWorkDoc>('machineWork'),
  ]);
  const writes: PendingWrite[] = [
    ...expenses
      .filter((item) => item.currency === 'RON')
      .map((item): PendingWrite => ({ kind: 'update', collection: 'expenses', docId: item.id, data: { amount: toEuro(item.amount, 'RON'), currency: 'EUR' } })),
    ...seasons
      .filter((item) => item.expectedPricePerT != null || item.actualPricePerT != null)
      .map((item): PendingWrite => ({ kind: 'update', collection: 'cropHistory', docId: item.id, data: { expectedPricePerT: toEur(item.expectedPricePerT) ?? null, actualPricePerT: toEur(item.actualPricePerT) ?? null } })),
    ...work
      .filter((item) => item.fuelPricePerL != null)
      .map((item): PendingWrite => ({ kind: 'update', collection: 'machineWork', docId: item.id, data: { fuelPricePerL: toEur(item.fuelPricePerL) } })),
  ];
  for (let i = 0; i < writes.length; i += 200) await queueWrites(writes.slice(i, i + 200));
}
