import { env } from '../config/env';
import { cached } from '../lib/cache';
import { notConfigured } from '../lib/errors';
import { fetchJson, fetchUpstream } from '../lib/http';

const SERVICE = 'Sentinel Hub';
const CRS84 = 'http://www.opengis.net/def/crs/OGC/1.3/CRS84';
/** Aproximativ 10 m, rezoluția benzilor folosite pentru NDVI. */
const RESOLUTION_DEG = 0.0001;
/** Observațiile cu mai mult de 60% din pixeli acoperiți de nori se ignoră. */
const MAX_CLOUD_RATIO = 0.6;

export const isSentinelConfigured = () => Boolean(env.SENTINEL_CLIENT_ID && env.SENTINEL_CLIENT_SECRET);

async function getToken(): Promise<string> {
  if (!isSentinelConfigured()) throw notConfigured('Serviciul de imagini satelitare');
  return cached('sentinel:token', 240, async () => {
    const data = await fetchJson<{ access_token: string }>(env.SENTINEL_TOKEN_URL, {
      service: SERVICE,
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'client_credentials',
        client_id: env.SENTINEL_CLIENT_ID!,
        client_secret: env.SENTINEL_CLIENT_SECRET!,
      }),
    });
    return data.access_token;
  });
}

const STATS_SCRIPT = `//VERSION=3
function setup() {
  return {
    input: [{ bands: ["B04", "B05", "B08", "SCL", "dataMask"] }],
    output: [
      { id: "ndvi", bands: 1, sampleType: "FLOAT32" },
      { id: "ndre", bands: 1, sampleType: "FLOAT32" },
      { id: "dataMask", bands: 1 }
    ]
  };
}
function evaluatePixel(s) {
  var clear = (s.SCL === 4 || s.SCL === 5) ? 1 : 0;
  return {
    ndvi: [(s.B08 - s.B04) / (s.B08 + s.B04 + 1e-6)],
    ndre: [(s.B08 - s.B05) / (s.B08 + s.B05 + 1e-6)],
    dataMask: [s.dataMask * clear]
  };
}`;

export interface SentinelObservation {
  date: string;
  ndvi: number;
  ndre: number | null;
  cloudPct: number;
}

interface StatsResponse {
  data: {
    interval: { from: string };
    outputs: Record<string, { bands: { B0: { stats: { mean: number | string; sampleCount: number; noDataCount: number } } } }>;
  }[];
}

export function parseStatistics(response: StatsResponse): SentinelObservation[] {
  return response.data.flatMap((item) => {
    const ndvi = item.outputs.ndvi?.bands.B0.stats;
    const ndre = item.outputs.ndre?.bands.B0.stats;
    if (!ndvi || typeof ndvi.mean !== 'number' || ndvi.sampleCount === 0) return [];
    const cloudRatio = ndvi.noDataCount / ndvi.sampleCount;
    if (cloudRatio > MAX_CLOUD_RATIO) return [];
    return [
      {
        date: item.interval.from.slice(0, 10),
        ndvi: Math.round(ndvi.mean * 1000) / 1000,
        ndre: typeof ndre?.mean === 'number' ? Math.round(ndre.mean * 1000) / 1000 : null,
        cloudPct: Math.round(cloudRatio * 100),
      },
    ];
  });
}

export async function fetchObservations(geometry: unknown, from: string, to: string): Promise<SentinelObservation[]> {
  const token = await getToken();
  const response = await fetchJson<StatsResponse>(`${env.SENTINEL_API_URL}/api/v1/statistics`, {
    service: SERVICE,
    method: 'POST',
    timeoutMs: 60_000,
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      input: {
        bounds: { geometry, properties: { crs: CRS84 } },
        data: [{ type: 'sentinel-2-l2a', dataFilter: { mosaickingOrder: 'leastCC' } }],
      },
      aggregation: {
        timeRange: { from: `${from}T00:00:00Z`, to: `${to}T23:59:59Z` },
        aggregationInterval: { of: 'P1D' },
        evalscript: STATS_SCRIPT,
        resx: RESOLUTION_DEG,
        resy: RESOLUTION_DEG,
      },
    }),
  });
  return parseStatistics(response);
}

const imageScript = (index: 'NDVI' | 'NDRE') => {
  const red = index === 'NDVI' ? 's.B04' : 's.B05';
  return `//VERSION=3
function setup() {
  return { input: [{ bands: ["B04", "B05", "B08", "dataMask"] }], output: { bands: 4 } };
}
var ramp = [[0.1, 0xb5362a], [0.3, 0xd9a521], [0.5, 0xc7cf4a], [0.7, 0x5da342], [0.9, 0x1f6b35]];
var viz = new ColorRampVisualizer(ramp);
function evaluatePixel(s) {
  var v = (s.B08 - ${red}) / (s.B08 + ${red} + 1e-6);
  var c = viz.process(v);
  return [c[0], c[1], c[2], s.dataMask];
}`;
};

export async function fetchIndexImage(geometry: unknown, date: string, index: 'NDVI' | 'NDRE'): Promise<Buffer> {
  const token = await getToken();
  const response = await fetchUpstream(`${env.SENTINEL_API_URL}/api/v1/process`, {
    service: SERVICE,
    method: 'POST',
    timeoutMs: 60_000,
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', Accept: 'image/png' },
    body: JSON.stringify({
      input: {
        bounds: { geometry, properties: { crs: CRS84 } },
        data: [
          {
            type: 'sentinel-2-l2a',
            dataFilter: { timeRange: { from: `${date}T00:00:00Z`, to: `${date}T23:59:59Z` } },
          },
        ],
      },
      output: { width: 512, height: 512, responses: [{ identifier: 'default', format: { type: 'image/png' } }] },
      evalscript: imageScript(index),
    }),
  });
  return Buffer.from(await response.arrayBuffer());
}
