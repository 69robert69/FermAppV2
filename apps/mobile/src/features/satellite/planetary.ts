import type { IndexStats, LngLat } from '@fermapp/domain';

/**
 * Copernicus Sentinel-2 L2A through Microsoft Planetary Computer: free, public,
 * no account or key. The service computes indices and renders images on its
 * servers, so the phone downloads only small statistics and PNGs.
 */

const STAC = 'https://planetarycomputer.microsoft.com/api/stac/v1/search';
const DATA = 'https://planetarycomputer.microsoft.com/api/data/v1';
const COLLECTION = 'sentinel-2-l2a';
const TIMEOUT_MS = 30_000;

export const EXPRESSIONS = {
  NDVI: '(B08-B04)/(B08+B04)',
  NDRE: '(B8A-B05)/(B8A+B05)',
  NDMI: '(B8A-B11)/(B8A+B11)',
  /** Scene classes 3 (cloud shadow) and 8-11 (clouds, cirrus, snow) hide the ground. */
  CLOUD: 'where(SCL>7,1,0)+where(SCL==3,1,0)',
  HEALTH: '0.7*(B08-B04)/(B08+B04)+0.3*(B8A-B05)/(B8A+B05)',
  /** Chlorophyll deficit: high where NDRE is low for a crop. */
  STRESS: '0.55-(B8A-B05)/(B8A+B05)',
} as const;

/** Colour scales of the layers, for legends (low → high). */
export const LAYER_SCALES: Record<ImageLayer | MapLayer, { low: string; high: string; colors: string[] } | null> = {
  RGB: null,
  NDVI: { low: 'Bare / weak', high: 'Dense, healthy', colors: ['#a50026', '#fdae61', '#ffffbf', '#a6d96a', '#006837'] },
  NDRE: { low: 'Low chlorophyll', high: 'High chlorophyll', colors: ['#440154', '#3b528b', '#21918c', '#5ec962', '#fde725'] },
  MOISTURE: { low: 'Dry', high: 'Wet', colors: ['#a50026', '#fdae61', '#ffffbf', '#abd9e9', '#313695'] },
  IRRIGATION: { low: 'Dry: irrigation risk', high: 'Well watered', colors: ['#a50026', '#fdae61', '#ffffbf', '#abd9e9', '#313695'] },
  HEALTH: { low: 'Weak', high: 'Healthy', colors: ['#ffffe5', '#d9f0a3', '#78c679', '#238443', '#004529'] },
  STRESS: { low: 'No stress', high: 'High stress', colors: ['#ffffcc', '#fed976', '#fd8d3c', '#e31a1c', '#800026'] },
};

/** The field view's stress layer is relative: the weakest parts of the field are dark red. */
export const FIELD_STRESS_SCALE = { low: 'Weakest in field', high: 'Strongest in field', colors: ['#800026', '#e31a1c', '#fd8d3c', '#fed976', '#ffffcc'] };

export type ImageLayer = 'RGB' | 'NDVI' | 'NDRE' | 'MOISTURE' | 'HEALTH' | 'STRESS';
export type MapLayer = 'NDVI' | 'HEALTH' | 'IRRIGATION' | 'STRESS';

export const IMAGE_LAYERS: { value: ImageLayer; label: string; hint: string }[] = [
  { value: 'RGB', label: 'RGB', hint: 'Natural colour, as the eye sees the field.' },
  { value: 'NDVI', label: 'NDVI', hint: 'Green biomass: red is bare or weak, green is dense, healthy canopy.' },
  { value: 'NDRE', label: 'NDRE', hint: 'Chlorophyll and nitrogen status, sensitive in dense crops.' },
  { value: 'MOISTURE', label: 'Moisture', hint: 'Water in the leaves (NDMI): red is dry, blue is well watered.' },
  { value: 'HEALTH', label: 'Health', hint: 'Vegetation health: vigour and chlorophyll combined.' },
  { value: 'STRESS', label: 'Stress', hint: 'Relative stress: the weakest parts of this field in red, compared with the rest of it.' },
];

export const MAP_LAYERS: { value: MapLayer; label: string }[] = [
  { value: 'NDVI', label: 'NDVI layer' },
  { value: 'HEALTH', label: 'Vegetation health' },
  { value: 'IRRIGATION', label: 'Irrigation risk' },
  { value: 'STRESS', label: 'Crop stress' },
];

export interface Scene {
  id: string;
  /** UTC acquisition time. */
  datetime: string;
  /** Cloud cover of the whole 100 km scene, 0-100. */
  sceneCloudPct: number;
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  try {
    const response = await fetch(url, { ...init, signal: controller.signal });
    if (!response.ok) throw new Error(`Satellite service HTTP ${response.status}`);
    return (await response.json()) as T;
  } finally {
    clearTimeout(timer);
  }
}

export type BBox = [minLng: number, minLat: number, maxLng: number, maxLat: number];

export function bboxOf(ring: readonly LngLat[]): BBox {
  const lngs = ring.map((point) => point[0]);
  const lats = ring.map((point) => point[1]);
  return [Math.min(...lngs), Math.min(...lats), Math.max(...lngs), Math.max(...lats)];
}

/** Scenes over the area between two days, newest first. */
export async function searchScenes(bbox: BBox, from: string, to: string, maxSceneCloud = 70): Promise<Scene[]> {
  const scenes: Scene[] = [];
  let body: Record<string, unknown> | null = {
    collections: [COLLECTION],
    bbox,
    datetime: `${from}T00:00:00Z/${to}T23:59:59Z`,
    query: { 'eo:cloud_cover': { lt: maxSceneCloud } },
    sortby: [{ field: 'properties.datetime', direction: 'desc' }],
    fields: { include: ['id', 'properties.datetime', 'properties.eo:cloud_cover'], exclude: ['assets', 'links', 'geometry'] },
    limit: 100,
  };
  // Two pages are plenty: a field is imaged every 2-5 days.
  for (let page = 0; body && page < 2; page++) {
    const data: { features: { id: string; properties: { datetime: string; 'eo:cloud_cover'?: number } }[]; links?: { rel: string; body?: Record<string, unknown> }[] } = await request(STAC, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    for (const feature of data.features) scenes.push({ id: feature.id, datetime: feature.properties.datetime, sceneCloudPct: Math.round(feature.properties['eo:cloud_cover'] ?? 100) });
    body = data.links?.find((link) => link.rel === 'next')?.body ?? null;
  }
  // The same pass can appear twice when a field sits on a tile edge.
  const seen = new Set<string>();
  return scenes.filter((scene) => {
    const key = scene.datetime.slice(0, 13);
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

type StatsBody = { properties: { statistics: Record<string, { mean: number; std: number; percentile_2: number; percentile_98: number; valid_percent: number; count: number }> } };

export interface PolygonStats {
  ndvi: IndexStats | null;
  ndre: number | null;
  ndmi: number | null;
  cloudPct: number;
}

const finite = (value: unknown): value is number => typeof value === 'number' && Number.isFinite(value);
const r3 = (value: number) => Math.round(value * 1000) / 1000;

/** NDVI, NDRE, NDMI and cloud share over a polygon, in a single request. */
export async function polygonStats(sceneId: string, ring: readonly LngLat[], withCloud = true): Promise<PolygonStats> {
  const expressions = [EXPRESSIONS.NDVI, EXPRESSIONS.NDRE, EXPRESSIONS.NDMI, ...(withCloud ? [EXPRESSIONS.CLOUD] : [])];
  const url = `${DATA}/item/statistics?collection=${COLLECTION}&item=${encodeURIComponent(sceneId)}&expression=${encodeURIComponent(expressions.join(';'))}&asset_as_band=true&max_size=256`;
  const data = await request<StatsBody>(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ type: 'Feature', properties: {}, geometry: { type: 'Polygon', coordinates: [ring] } }),
  });
  const stats = data.properties.statistics;
  const ndvi = stats[EXPRESSIONS.NDVI];
  return {
    ndvi: ndvi && finite(ndvi.mean) && ndvi.count > 0 ? { mean: r3(ndvi.mean), std: r3(ndvi.std), p2: r3(ndvi.percentile_2), p98: r3(ndvi.percentile_98) } : null,
    ndre: finite(stats[EXPRESSIONS.NDRE]?.mean) ? r3(stats[EXPRESSIONS.NDRE]!.mean) : null,
    ndmi: finite(stats[EXPRESSIONS.NDMI]?.mean) ? r3(stats[EXPRESSIONS.NDMI]!.mean) : null,
    cloudPct: withCloud && finite(stats[EXPRESSIONS.CLOUD]?.mean) ? Math.round(stats[EXPRESSIONS.CLOUD]!.mean * 100) : 0,
  };
}

function render(layer: ImageLayer | MapLayer, stretch?: { min: number; max: number }) {
  switch (layer) {
    case 'RGB':
      return 'assets=visual&asset_bidx=visual%7C1%2C2%2C3';
    case 'NDVI':
      return `expression=${encodeURIComponent(EXPRESSIONS.NDVI)}&asset_as_band=true&rescale=0%2C0.9&colormap_name=rdylgn`;
    // Each layer has its own colour scale, so they are told apart at a glance.
    case 'NDRE':
      return `expression=${encodeURIComponent(EXPRESSIONS.NDRE)}&asset_as_band=true&rescale=0%2C0.6&colormap_name=viridis`;
    case 'MOISTURE':
    case 'IRRIGATION':
      return `expression=${encodeURIComponent(EXPRESSIONS.NDMI)}&asset_as_band=true&rescale=-0.4%2C0.4&colormap_name=rdylbu`;
    case 'HEALTH':
      return `expression=${encodeURIComponent(EXPRESSIONS.HEALTH)}&asset_as_band=true&rescale=0.05%2C0.8&colormap_name=ylgn`;
    case 'STRESS': {
      if (!stretch) {
        // Map layer: chlorophyll deficit, pale where the crop is fine and dark red where it is stressed.
        return `expression=${encodeURIComponent(EXPRESSIONS.STRESS)}&asset_as_band=true&rescale=0%2C0.45&colormap_name=ylorrd`;
      }
      // Field view: stretched to this field's own range, so its weakest parts turn dark red.
      const range = stretch.max - stretch.min > 0.05 ? `${stretch.min.toFixed(2)}%2C${stretch.max.toFixed(2)}` : '0.2%2C0.85';
      return `expression=${encodeURIComponent(EXPRESSIONS.NDVI)}&asset_as_band=true&rescale=${range}&colormap_name=ylorrd_r`;
    }
  }
}

/**
 * A PNG of the field's bounding box in longitude/latitude, so overlays drawn
 * with a linear mapping line up with the pixels.
 */
export function fieldImageUrl(sceneId: string, bbox: BBox, layer: ImageLayer, size: { width: number; height: number }, stretch?: { min: number; max: number }) {
  const [minLng, minLat, maxLng, maxLat] = bbox.map((value) => value.toFixed(6));
  return `${DATA}/item/bbox/${minLng},${minLat},${maxLng},${maxLat}/${size.width}x${size.height}.png?collection=${COLLECTION}&item=${encodeURIComponent(sceneId)}&${render(layer, stretch)}&dst_crs=epsg%3A4326`;
}

/** Map tiles for react-native-maps; `{z}/{x}/{y}` are filled in by the map. */
export function tileUrlTemplate(sceneId: string, layer: MapLayer) {
  return `${DATA}/item/tiles/WebMercatorQuad/{z}/{x}/{y}@1x.png?collection=${COLLECTION}&item=${encodeURIComponent(sceneId)}&${render(layer)}`;
}
