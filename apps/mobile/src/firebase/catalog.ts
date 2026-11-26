import {
  IMPLEMENT_CATALOG,
  parseCsv,
  recommendMachine,
  rankSeedsForParcel,
  SEED_CATALOG,
  seedFromRow,
  tableToRows,
  TRACTOR_CATALOG,
  tractorFromRow,
  type CropCode,
  type CropHistoryEntry,
  type ImplementEntry,
  type SeedEntry,
  type SoilTexture,
  type TractorEntry,
  type TractorOption,
  type WorkOperation,
} from '@fermapp/domain';
import { File } from 'expo-file-system';
import { doc, getDoc } from 'firebase/firestore';
import { ApiError } from '../api/client';
import { useCatalogPrefs } from '../store/catalogPrefs';
import { firestore } from './app';
import { liveItems } from './liveStore';
import { queueWrites, type PendingWrite } from './outbox';
import { cachedSiteData } from './plans';
import { farmFields, newId, session } from './services';
import type { CollectionName, CropHistoryDoc, FarmDoc, MachineDoc, ParcelDoc, UserProfile } from './types';

export type SeedDoc = SeedEntry & FarmDoc & { catalogKey?: string };
export type TractorDoc = TractorEntry & FarmDoc & { catalogKey?: string };
export type ImplementDoc = ImplementEntry & FarmDoc & { catalogKey?: string };

const MAX_BATCH = 400;

const seedOffset = (entry: { id: string }, farmId: string) => `${farmId}_${entry.id}`;

function catalogWrites(farmId: string, collection: CollectionName, entries: readonly { id: string }[]): PendingWrite[] {
  return entries.map((entry) => ({
    kind: 'set',
    collection,
    docId: seedOffset(entry, farmId),
    data: { ...entry, id: undefined, catalogKey: entry.id, farmId, createdAt: new Date().toISOString() },
  }));
}

/** Loads the starter products into the farm catalog only once. */
export async function ensureCatalogSeeded(profile: UserProfile) {
  if (profile.role !== 'ADMIN') return;
  const farm = await getDoc(doc(firestore, 'farms', profile.farmId)).catch(() => null);
  if (!farm?.exists() || farm.data().catalogSeededAt) return;
  const writes = [
    ...catalogWrites(profile.farmId, 'seedCatalog', SEED_CATALOG),
    ...catalogWrites(profile.farmId, 'machineCatalog', TRACTOR_CATALOG),
    ...catalogWrites(profile.farmId, 'implementCatalog', IMPLEMENT_CATALOG),
  ];
  await queueWrites([...writes, { kind: 'update', collection: 'farms', docId: profile.farmId, data: { catalogSeededAt: new Date().toISOString() } }]);
}

/** Until the first sync, the products bundled with the app are shown. */
async function withBuiltIn<T extends FarmDoc>(name: CollectionName, builtIn: readonly { id: string }[]): Promise<T[]> {
  const items = await liveItems<T>(name);
  if (items.length > 0) return items;
  const { farmId } = session();
  return builtIn.map((entry) => ({ ...entry, farmId, createdAt: '', catalogKey: entry.id }) as unknown as T);
}

export const catalogSeeds = () => withBuiltIn<SeedDoc>('seedCatalog', SEED_CATALOG);
export const catalogTractors = () => withBuiltIn<TractorDoc>('machineCatalog', TRACTOR_CATALOG);
export const catalogImplements = () => withBuiltIn<ImplementDoc>('implementCatalog', IMPLEMENT_CATALOG);

export const sameEntry = (entry: { id: string; catalogKey?: string }, id: string | null | undefined) =>
  Boolean(id) && (entry.id === id || entry.catalogKey === id);

const clean = (entry: Record<string, unknown>) => Object.fromEntries(Object.entries(entry).filter(([key]) => key !== 'id'));

async function saveEntry(collection: CollectionName, id: string | undefined, entry: Record<string, unknown>) {
  const docId = id ?? newId(collection);
  await queueWrites([{ kind: 'set', collection, docId, data: { ...farmFields(), ...clean(entry) } }]);
  return { id: docId };
}

export const saveSeed = (id: string | undefined, entry: Record<string, unknown>) => saveEntry('seedCatalog', id, entry);
export const saveTractor = (id: string | undefined, entry: Record<string, unknown>) => saveEntry('machineCatalog', id, entry);
export const deleteEntry = (collection: CollectionName, id: string) => queueWrites([{ kind: 'delete', collection, docId: id }]);

async function readXlsxTable(bytes: Uint8Array): Promise<string[][]> {
  const [{ default: JSZip }, { DOMParser }] = await Promise.all([import('jszip'), import('@xmldom/xmldom')]);
  const zip = await JSZip.loadAsync(bytes);
  const parse = (text: string) => new DOMParser({ onError: () => undefined }).parseFromString(text, 'text/xml');
  const textOf = (node: { getElementsByTagName(name: string): ArrayLike<{ textContent: string | null }> }) =>
    Array.from(node.getElementsByTagName('t')).map((item) => item.textContent ?? '').join('');
  const stringsFile = zip.file('xl/sharedStrings.xml');
  const shared = stringsFile ? Array.from(parse(await stringsFile.async('string')).getElementsByTagName('si')).map(textOf) : [];
  const sheetFile = zip.file('xl/worksheets/sheet1.xml');
  if (!sheetFile) throw new ApiError(400, 'BAD_FILE', 'The Excel file does not contain a worksheet.');
  const columnIndex = (ref: string) => [...ref.replace(/\d+/g, '')].reduce((total, letter) => total * 26 + letter.charCodeAt(0) - 64, 0) - 1;
  return Array.from(parse(await sheetFile.async('string')).getElementsByTagName('row')).map((row) => {
    const cells: string[] = [];
    for (const cell of Array.from(row.getElementsByTagName('c'))) {
      const type = cell.getAttribute('t');
      const value = cell.getElementsByTagName('v')[0]?.textContent ?? '';
      cells[columnIndex(cell.getAttribute('r') ?? 'A1')] = type === 's' ? (shared[Number(value)] ?? '') : type === 'inlineStr' ? textOf(cell) : value;
    }
    return Array.from(cells, (cell) => cell ?? '');
  });
}

/** The rows from a CSV or Excel file. */
export async function readCatalogFile(bytes: Uint8Array): Promise<Record<string, string>[]> {
  const isZip = bytes[0] === 0x50 && bytes[1] === 0x4b;
  if (!isZip) return parseCsv(new TextDecoder('utf-8').decode(bytes));
  const table = await readXlsxTable(bytes);
  // Exported reports have a title above the table.
  const headerIndex = table.findIndex((row) => row.filter((cell) => cell.trim()).length >= 2);
  return tableToRows(headerIndex > 0 ? table.slice(headerIndex) : table);
}

/** Adds the valid products to the catalog and reports the rejected rows. */
export async function importCatalog(kind: 'seeds' | 'tractors', fileUri: string) {
  const rows = await readCatalogFile(await new File(fileUri).bytes());
  if (rows.length === 0) throw new ApiError(400, 'BAD_FILE', 'The file contains no rows to import.');
  const parsed: (SeedEntry | TractorEntry | string)[] = rows.map((row) => (kind === 'seeds' ? seedFromRow(row) : tractorFromRow(row)));
  const entries = parsed.filter((item): item is SeedEntry | TractorEntry => typeof item !== 'string');
  const problems = parsed.filter((item): item is string => typeof item === 'string');
  const writes = catalogWrites(session().farmId, kind === 'seeds' ? 'seedCatalog' : 'machineCatalog', entries);
  for (let i = 0; i < writes.length; i += MAX_BATCH) await queueWrites(writes.slice(i, i + MAX_BATCH));
  return { imported: entries.length, problems };
}

/** Compatibility of a crop's products with the parcel. */
export async function seedRanking(parcel: Pick<ParcelDoc, 'id' | 'centerLat' | 'centerLng' | 'irrigatedAreaHa'>, crop: CropCode, year: number) {
  const [seeds, seasons, site] = await Promise.all([
    catalogSeeds(),
    liveItems<CropHistoryDoc>('cropHistory'),
    cachedSiteData(parcel.centerLat, parcel.centerLng),
  ]);
  const history: CropHistoryEntry[] = seasons
    .filter((item) => item.parcelId === parcel.id && item.year < year)
    .map(({ crop: code, year: seasonYear }) => ({ crop: code, year: seasonYear }));
  return rankSeedsForParcel({
    seeds,
    crop,
    year,
    history,
    soil: site.soil ? { ph: site.soil.ph, texture: site.soil.texture as SoilTexture } : null,
    climate: site.climate,
    irrigated: (parcel.irrigatedAreaHa ?? 0) > 0,
  });
}

/** The farm's tractors or, if there are none, the catalog models. */
export async function tractorOptions(): Promise<TractorOption[]> {
  const machines = (await liveItems<MachineDoc>('machines')).filter((machine) => machine.type === 'TRACTOR' && machine.powerHp);
  const owned = machines.map((machine) => ({
    id: machine.id,
    name: machine.name,
    horsePower: machine.powerHp!,
    fuelConsumptionMin: machine.fuelConsumptionMin ?? machine.fuelConsumption,
    fuelConsumptionMax: machine.fuelConsumptionMax ?? machine.fuelConsumption,
    owned: true,
  }));
  if (owned.length > 0) return owned;
  return (await catalogTractors()).map((tractor) => ({
    id: tractor.id,
    name: `${tractor.brand} ${tractor.model}`,
    horsePower: tractor.horsePower,
    fuelConsumptionMin: tractor.fuelConsumptionMin,
    fuelConsumptionMax: tractor.fuelConsumptionMax,
    owned: false,
  }));
}

export async function machineRecommendation(areaHa: number, operation: WorkOperation) {
  const [tractors, implementsList] = await Promise.all([tractorOptions(), catalogImplements()]);
  return recommendMachine({
    areaHa,
    operation,
    tractors,
    implements: implementsList,
    fuelPricePerL: useCatalogPrefs.getState().fuelPricePerL,
  });
}

/** The operation the recommendation is for, based on the next task in the plan. */
export const OPERATION_BY_TASK: Record<string, WorkOperation> = {
  SOIL_PREP: 'DISCING',
  SOWING: 'SOWING',
  FERTILIZATION: 'FERTILIZING',
  HERBICIDE: 'SPRAYING',
  FUNGICIDE: 'SPRAYING',
  INSECTICIDE: 'SPRAYING',
};
