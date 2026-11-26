import { validatePolygon, type PolygonGeometry, type Position } from '@fermapp/domain';
import { File, Paths } from 'expo-file-system';
import { ApiError } from '../api/client';

const MAX_FEATURES = 2000;
const MAX_ARCHIVE_ENTRIES = 50;

export type GeoFormat = 'geojson' | 'kml' | 'kmz' | 'shp' | 'lpis';

interface Feature {
  type: 'Feature';
  geometry: { type: string; coordinates: unknown } | null;
  properties: Record<string, unknown> | null;
}

export interface ImportedParcel {
  name: string;
  code?: string;
  locality?: string;
  county?: string;
  notes?: string;
  geometry: PolygonGeometry;
}

export interface ExportParcel {
  name: string;
  code: string;
  areaHa: number;
  perimeterM: number;
  locality: string | null;
  county: string | null;
  notes: string | null;
  geometry: PolygonGeometry;
}

/** Common field names from GIS exports and APIA/LPIS files. */
const FIELD_ALIASES = {
  name: ['name', 'nume', 'denumire', 'parcela', 'nume_parcela', 'nr_parcela', 'parcel', 'title'],
  code: ['code', 'cod', 'cod_parcela', 'bloc_fizic', 'blocfizic', 'bf', 'nr_bloc', 'id_parcela'],
  locality: ['locality', 'localitate', 'uat', 'comuna', 'siruta_nume'],
  county: ['county', 'judet', 'județ', 'jud'],
  notes: ['notes', 'observatii', 'observații', 'description', 'descriere'],
} as const;

const unreadable = (message: string) => new ApiError(400, 'BAD_FILE', message);

function pick(properties: Record<string, unknown>, aliases: readonly string[]) {
  const lower = new Map(Object.entries(properties).map(([key, value]) => [key.toLowerCase(), value]));
  for (const alias of aliases) {
    const value = lower.get(alias);
    if (value !== undefined && value !== null && String(value).trim() !== '') return String(value).trim().slice(0, 200);
  }
  return undefined;
}

function toPositions(ring: unknown): Position[] {
  if (!Array.isArray(ring)) throw new Error('invalid ring');
  return ring.map((point) => {
    if (!Array.isArray(point) || typeof point[0] !== 'number' || typeof point[1] !== 'number') throw new Error('invalid coordinates');
    return [point[0], point[1]] as Position;
  });
}

function polygonsOf(geometry: Feature['geometry']): PolygonGeometry[] {
  if (!geometry) return [];
  const coordinates = geometry.coordinates as unknown[];
  if (geometry.type === 'Polygon') return [{ type: 'Polygon', coordinates: coordinates.map(toPositions) }];
  if (geometry.type === 'MultiPolygon') {
    return coordinates.map((polygon) => ({ type: 'Polygon' as const, coordinates: (polygon as unknown[]).map(toPositions) }));
  }
  if (geometry.type === 'GeometryCollection') {
    return (geometry as unknown as { geometries: Feature['geometry'][] }).geometries.flatMap(polygonsOf);
  }
  return [];
}

function featuresToParcels(features: Feature[]) {
  if (features.length > MAX_FEATURES) throw unreadable(`The file exceeds the limit of ${MAX_FEATURES} parcels.`);
  const parcels: ImportedParcel[] = [];
  const problems: { index: number; name: string; reason: string }[] = [];
  features.forEach((feature, index) => {
    const properties = feature.properties ?? {};
    const baseName = pick(properties, FIELD_ALIASES.name) ?? `Imported parcel ${index + 1}`;
    let polygons: PolygonGeometry[];
    try {
      polygons = polygonsOf(feature.geometry);
    } catch (error) {
      problems.push({ index, name: baseName, reason: `Invalid geometry: ${(error as Error).message}.` });
      return;
    }
    if (polygons.length === 0) {
      problems.push({ index, name: baseName, reason: 'The element does not contain a polygon.' });
      return;
    }
    polygons.forEach((geometry, part) => {
      const suffix = polygons.length > 1 ? ` (${part + 1})` : '';
      const reason = validatePolygon(geometry);
      if (reason) {
        problems.push({ index, name: `${baseName}${suffix}`, reason });
        return;
      }
      const code = pick(properties, FIELD_ALIASES.code);
      parcels.push({
        name: `${baseName}${suffix}`,
        code: code ? `${code}${polygons.length > 1 ? `-${part + 1}` : ''}` : undefined,
        locality: pick(properties, FIELD_ALIASES.locality),
        county: pick(properties, FIELD_ALIASES.county),
        notes: pick(properties, FIELD_ALIASES.notes),
        geometry,
      });
    });
  });
  return { parcels, problems };
}

function asFeatures(data: unknown): Feature[] {
  return (Array.isArray(data) ? data : [data]).flatMap((item: any): Feature[] => {
    if (item?.type === 'FeatureCollection' && Array.isArray(item.features)) return item.features;
    if (item?.type === 'Feature') return [item];
    if (item?.type === 'Polygon' || item?.type === 'MultiPolygon') return [{ type: 'Feature', geometry: item, properties: {} }];
    throw unreadable('The file does not contain valid GeoJSON data.');
  });
}

async function parseKml(text: string): Promise<Feature[]> {
  const [{ kml }, { DOMParser }] = await Promise.all([import('@tmcw/togeojson'), import('@xmldom/xmldom')]);
  const document = new DOMParser({ onError: () => undefined }).parseFromString(text, 'text/xml');
  return kml(document as never).features as Feature[];
}

async function openArchive(bytes: Uint8Array) {
  const { default: JSZip } = await import('jszip');
  const zip = await JSZip.loadAsync(bytes);
  if (Object.keys(zip.files).length > MAX_ARCHIVE_ENTRIES) throw unreadable('The archive contains too many files.');
  return zip;
}

const decode = (bytes: Uint8Array) => new TextDecoder('utf-8').decode(bytes);

/** Reads each Shapefile layer in the archive, including the coordinate system from the .prj file. */
async function parseShapefileZip(bytes: Uint8Array) {
  const zip = await openArchive(bytes);
  const { combine, parseDbf, parseShp } = await import('shpjs');
  const entries = Object.values(zip.files).filter((file) => !file.dir && !file.name.includes('__MACOSX'));
  const part = (base: string, extension: string) => entries.find((file) => file.name.toLowerCase() === `${base}.${extension}`);
  const layers = entries.filter((file) => file.name.toLowerCase().endsWith('.shp'));
  if (layers.length === 0) throw unreadable('The archive does not contain a Shapefile.');
  return Promise.all(
    layers.map(async (layer) => {
      const base = layer.name.slice(0, -4).toLowerCase();
      const [shp, prj, dbf, cpg] = await Promise.all([
        layer.async('arraybuffer'),
        part(base, 'prj')?.async('string'),
        part(base, 'dbf')?.async('arraybuffer'),
        part(base, 'cpg')?.async('string'),
      ]);
      return combine([parseShp(shp, prj), dbf ? parseDbf(dbf, cpg) : undefined]);
    }),
  );
}

/** Reads parcels from a GeoJSON, KML, KMZ, Shapefile or APIA LPIS file. */
export async function parseGeoBytes(format: GeoFormat, bytes: Uint8Array) {
  let features: Feature[];
  try {
    if (format === 'geojson') features = asFeatures(JSON.parse(decode(bytes)));
    else if (format === 'kml') features = await parseKml(decode(bytes));
    else if (format === 'kmz') {
      const zip = await openArchive(bytes);
      const entry = Object.values(zip.files).find((file) => file.name.toLowerCase().endsWith('.kml'));
      if (!entry) throw unreadable('The KMZ archive does not contain a KML file.');
      features = await parseKml(await entry.async('string'));
    } else if (bytes[0] === 0x7b) features = asFeatures(JSON.parse(decode(bytes)));
    else features = asFeatures(await parseShapefileZip(bytes));
  } catch (error) {
    if (error instanceof ApiError) throw error;
    throw unreadable(`The ${format.toUpperCase()} file cannot be read.`);
  }
  return featuresToParcels(features);
}

const xml = (value: string) =>
  value.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;').replaceAll("'", '&apos;');

function toKml(parcels: ExportParcel[]) {
  const ring = (points: Position[]) => points.map(([lng, lat]) => `${lng},${lat},0`).join(' ');
  const placemarks = parcels.map((parcel) => {
    const [outer, ...holes] = parcel.geometry.coordinates;
    const inner = holes.map((hole) => `<innerBoundaryIs><LinearRing><coordinates>${ring(hole)}</coordinates></LinearRing></innerBoundaryIs>`).join('');
    return `<Placemark><name>${xml(parcel.name)}</name><description>${xml(`${parcel.code}, ${parcel.areaHa} ha`)}</description><ExtendedData><Data name="code"><value>${xml(parcel.code)}</value></Data><Data name="locality"><value>${xml(parcel.locality ?? '')}</value></Data><Data name="county"><value>${xml(parcel.county ?? '')}</value></Data></ExtendedData><Polygon><outerBoundaryIs><LinearRing><coordinates>${ring(outer!)}</coordinates></LinearRing></outerBoundaryIs>${inner}</Polygon></Placemark>`;
  });
  return `<?xml version="1.0" encoding="UTF-8"?><kml xmlns="http://www.opengis.net/kml/2.2"><Document><name>FermApp</name>${placemarks.join('')}</Document></kml>`;
}

const featureCollection = (parcels: ExportParcel[]) => ({
  type: 'FeatureCollection' as const,
  features: parcels.map((parcel) => ({
    type: 'Feature' as const,
    geometry: parcel.geometry,
    properties: {
      name: parcel.name,
      code: parcel.code,
      area_ha: parcel.areaHa,
      perimeter: parcel.perimeterM,
      locality: parcel.locality ?? '',
      county: parcel.county ?? '',
      notes: parcel.notes ?? '',
    },
  })),
});

const lpisCollection = (parcels: ExportParcel[]) => ({
  type: 'FeatureCollection' as const,
  features: parcels.map((parcel) => ({
    type: 'Feature' as const,
    geometry: parcel.geometry,
    properties: {
      NR_PARCELA: parcel.name.slice(0, 50),
      BLOC_FIZIC: parcel.code.slice(0, 50),
      SUPRAFATA: parcel.areaHa,
      UAT: (parcel.locality ?? '').slice(0, 50),
      JUDET: (parcel.county ?? '').slice(0, 50),
    },
  })),
});

async function toShapefile(collection: unknown) {
  const { default: shpwrite } = await import('@mapbox/shp-write');
  return (await shpwrite.zip(collection, { outputType: 'uint8array', compression: 'DEFLATE', types: { polygon: 'parcels' } })) as Uint8Array;
}

/** Content of the exported file. */
export async function exportBytes(format: GeoFormat, parcels: ExportParcel[]): Promise<Uint8Array | string> {
  if (format === 'geojson') return JSON.stringify(featureCollection(parcels));
  if (format === 'kml') return toKml(parcels);
  if (format === 'kmz') {
    const { default: JSZip } = await import('jszip');
    const zip = new JSZip();
    zip.file('doc.kml', toKml(parcels));
    return zip.generateAsync({ type: 'uint8array', compression: 'DEFLATE' });
  }
  return toShapefile(format === 'shp' ? featureCollection(parcels) : lpisCollection(parcels));
}

/** Saves a generated file and returns its address, for sharing. */
export function saveGeneratedFile(filename: string, content: Uint8Array | string) {
  const file = new File(Paths.cache, filename);
  if (file.exists) file.delete();
  file.create();
  file.write(content);
  return file.uri;
}
