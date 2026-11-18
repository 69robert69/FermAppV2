import { computeGeoMetrics, validatePolygon, type PolygonGeometry, type Position } from '@fermapp/domain';
import { kml as kmlToGeoJson } from '@tmcw/togeojson';
import shpwrite from '@mapbox/shp-write';
import { DOMParser } from '@xmldom/xmldom';
import JSZip from 'jszip';
import { parseZip } from 'shpjs';
import { badRequest } from '../../lib/errors';

export const IMPORT_FORMATS = ['geojson', 'kml', 'kmz', 'shp', 'lpis'] as const;
export type GeoFormat = (typeof IMPORT_FORMATS)[number];

const MAX_ARCHIVE_ENTRIES = 50;
const MAX_UNCOMPRESSED_BYTES = 200 * 1024 * 1024;
const MAX_FEATURES = 2000;

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

export interface ImportProblem {
  index: number;
  name: string;
  reason: string;
}

/** Numele de câmpuri uzuale din exporturile GIS și din fișierele APIA/LPIS. */
const FIELD_ALIASES = {
  name: ['name', 'nume', 'denumire', 'parcela', 'nume_parcela', 'nr_parcela', 'parcel', 'title'],
  code: ['code', 'cod', 'cod_parcela', 'bloc_fizic', 'blocfizic', 'bf', 'nr_bloc', 'id_parcela'],
  locality: ['locality', 'localitate', 'uat', 'comuna', 'siruta_nume'],
  county: ['county', 'judet', 'județ', 'jud'],
  notes: ['notes', 'observatii', 'observații', 'description', 'descriere'],
} as const;

function pick(properties: Record<string, unknown>, aliases: readonly string[]): string | undefined {
  const lower = new Map(Object.entries(properties).map(([key, value]) => [key.toLowerCase(), value]));
  for (const alias of aliases) {
    const value = lower.get(alias);
    if (value !== undefined && value !== null && String(value).trim() !== '') return String(value).trim().slice(0, 200);
  }
  return undefined;
}

/** Elimină altitudinea și verifică structura fiecărui punct. */
function toPositions(ring: unknown): Position[] {
  if (!Array.isArray(ring)) throw new Error('inel invalid');
  return ring.map((point) => {
    if (!Array.isArray(point) || typeof point[0] !== 'number' || typeof point[1] !== 'number') {
      throw new Error('coordonate invalide');
    }
    return [point[0], point[1]] as Position;
  });
}

function polygonsOf(geometry: Feature['geometry']): PolygonGeometry[] {
  if (!geometry) return [];
  const coordinates = geometry.coordinates as unknown[];
  if (geometry.type === 'Polygon') return [{ type: 'Polygon', coordinates: coordinates.map(toPositions) }];
  if (geometry.type === 'MultiPolygon') {
    return coordinates.map((polygon) => ({
      type: 'Polygon' as const,
      coordinates: (polygon as unknown[]).map(toPositions),
    }));
  }
  if (geometry.type === 'GeometryCollection') {
    const geometries = (geometry as unknown as { geometries: Feature['geometry'][] }).geometries;
    return geometries.flatMap(polygonsOf);
  }
  return [];
}

export function featuresToParcels(features: Feature[]) {
  if (features.length > MAX_FEATURES) throw badRequest(`Fișierul depășește limita de ${MAX_FEATURES} parcele.`);
  const parcels: ImportedParcel[] = [];
  const problems: ImportProblem[] = [];

  features.forEach((feature, index) => {
    const properties = feature.properties ?? {};
    const baseName = pick(properties, FIELD_ALIASES.name) ?? `Parcelă importată ${index + 1}`;
    let polygons: PolygonGeometry[];
    try {
      polygons = polygonsOf(feature.geometry);
    } catch (error) {
      problems.push({ index, name: baseName, reason: `Geometrie invalidă: ${(error as Error).message}.` });
      return;
    }
    if (polygons.length === 0) {
      problems.push({ index, name: baseName, reason: 'Elementul nu conține un poligon.' });
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
  const collections = Array.isArray(data) ? data : [data];
  return collections.flatMap((item: any): Feature[] => {
    if (item?.type === 'FeatureCollection' && Array.isArray(item.features)) return item.features;
    if (item?.type === 'Feature') return [item];
    if (item?.type === 'Polygon' || item?.type === 'MultiPolygon') {
      return [{ type: 'Feature', geometry: item, properties: {} }];
    }
    throw badRequest('Fișierul nu conține date GeoJSON valide.');
  });
}

/** Verifică arhiva înainte de dezarhivare, ca protecție împotriva arhivelor supradimensionate. */
async function openArchive(buffer: Buffer): Promise<JSZip> {
  let zip: JSZip;
  try {
    zip = await JSZip.loadAsync(buffer);
  } catch {
    throw badRequest('Arhiva nu poate fi citită.');
  }
  const entries = Object.values(zip.files) as (JSZip.JSZipObject & { _data?: { uncompressedSize?: number } })[];
  if (entries.length > MAX_ARCHIVE_ENTRIES) throw badRequest('Arhiva conține prea multe fișiere.');
  const total = entries.reduce((sum, entry) => sum + (entry._data?.uncompressedSize ?? 0), 0);
  if (total > MAX_UNCOMPRESSED_BYTES) throw badRequest('Arhiva este prea mare după dezarhivare.');
  return zip;
}

function parseKml(text: string): Feature[] {
  const silent = () => undefined;
  const document = new DOMParser({ onError: silent }).parseFromString(text, 'text/xml');
  return kmlToGeoJson(document as never).features as Feature[];
}

export async function parseGeoFile(format: GeoFormat, buffer: Buffer) {
  let features: Feature[];
  try {
    switch (format) {
      case 'geojson':
        features = asFeatures(JSON.parse(buffer.toString('utf8')));
        break;
      case 'kml':
        features = parseKml(buffer.toString('utf8'));
        break;
      case 'kmz': {
        const zip = await openArchive(buffer);
        const entry = Object.values(zip.files).find((file) => file.name.toLowerCase().endsWith('.kml'));
        if (!entry) throw badRequest('Arhiva KMZ nu conține un fișier KML.');
        features = parseKml(await entry.async('string'));
        break;
      }
      case 'shp':
      case 'lpis': {
        // Fișierele LPIS pot veni ca GeoJSON sau ca arhivă Shapefile (inclusiv Stereo 70, prin .prj).
        if (buffer[0] === 0x7b) {
          features = asFeatures(JSON.parse(buffer.toString('utf8')));
          break;
        }
        await openArchive(buffer);
        features = asFeatures(await parseZip(buffer));
        break;
      }
    }
  } catch (error) {
    if ((error as { status?: number }).status) throw error;
    throw badRequest(`Fișierul ${format.toUpperCase()} nu poate fi citit.`);
  }
  return featuresToParcels(features);
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

export function toFeatureCollection(parcels: ExportParcel[]) {
  return {
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
  };
}

const xml = (value: string) =>
  value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&apos;');

export function toKml(parcels: ExportParcel[]): string {
  const ring = (points: Position[]) => points.map(([lng, lat]) => `${lng},${lat},0`).join(' ');
  const placemarks = parcels.map((parcel) => {
    const [outer, ...holes] = parcel.geometry.coordinates;
    const inner = holes
      .map((hole) => `<innerBoundaryIs><LinearRing><coordinates>${ring(hole)}</coordinates></LinearRing></innerBoundaryIs>`)
      .join('');
    return `<Placemark><name>${xml(parcel.name)}</name><description>${xml(`${parcel.code}, ${parcel.areaHa} ha`)}</description><ExtendedData><Data name="code"><value>${xml(parcel.code)}</value></Data><Data name="localitate"><value>${xml(parcel.locality ?? '')}</value></Data><Data name="judet"><value>${xml(parcel.county ?? '')}</value></Data></ExtendedData><Polygon><outerBoundaryIs><LinearRing><coordinates>${ring(outer!)}</coordinates></LinearRing></outerBoundaryIs>${inner}</Polygon></Placemark>`;
  });
  return `<?xml version="1.0" encoding="UTF-8"?><kml xmlns="http://www.opengis.net/kml/2.2"><Document><name>FermApp</name>${placemarks.join('')}</Document></kml>`;
}

async function toShapefile(collection: unknown): Promise<Buffer> {
  const result = await shpwrite.zip(collection, {
    outputType: 'nodebuffer',
    compression: 'DEFLATE',
    types: { polygon: 'parcele' },
  });
  return result as Buffer;
}

/** Export pentru verificarea în GIS, cu câmpurile de identificare folosite la import. */
function toLpisCollection(parcels: ExportParcel[]) {
  return {
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
  };
}

export async function exportParcels(format: GeoFormat, parcels: ExportParcel[]) {
  switch (format) {
    case 'geojson':
      return {
        buffer: Buffer.from(JSON.stringify(toFeatureCollection(parcels))),
        contentType: 'application/geo+json',
        extension: 'geojson',
      };
    case 'kml':
      return { buffer: Buffer.from(toKml(parcels)), contentType: 'application/vnd.google-earth.kml+xml', extension: 'kml' };
    case 'kmz': {
      const zip = new JSZip();
      zip.file('doc.kml', toKml(parcels));
      return {
        buffer: await zip.generateAsync({ type: 'nodebuffer', compression: 'DEFLATE' }),
        contentType: 'application/vnd.google-earth.kmz',
        extension: 'kmz',
      };
    }
    case 'shp':
      return { buffer: await toShapefile(toFeatureCollection(parcels)), contentType: 'application/zip', extension: 'shp.zip' };
    case 'lpis':
      return { buffer: await toShapefile(toLpisCollection(parcels)), contentType: 'application/zip', extension: 'lpis.zip' };
  }
}

export const metricsFor = (geometry: PolygonGeometry) => computeGeoMetrics(geometry);
