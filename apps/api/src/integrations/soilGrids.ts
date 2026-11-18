import { fetchJson } from '../lib/http';

interface SoilGridsResponse {
  properties: {
    layers: {
      name: string;
      unit_measure: { d_factor: number };
      depths: { label: string; values: { mean: number | null } }[];
    }[];
  };
}

/** Grosimea straturilor analizate, în cm. */
const DEPTH_WEIGHTS: Record<string, number> = { '0-5cm': 5, '5-15cm': 10, '15-30cm': 15 };

export interface SoilGridsValues {
  ph: number;
  clayPct: number;
  sandPct: number;
  organicCarbonGKg: number;
  fieldCapacityPct: number | null;
  wiltingPointPct: number | null;
}

export function parseSoilGrids(data: SoilGridsResponse): SoilGridsValues | null {
  const layerMean = (name: string): number | null => {
    const layer = data.properties.layers.find((item) => item.name === name);
    if (!layer) return null;
    let sum = 0;
    let weight = 0;
    for (const depth of layer.depths) {
      const w = DEPTH_WEIGHTS[depth.label];
      if (w === undefined || depth.values.mean == null) continue;
      sum += (depth.values.mean / layer.unit_measure.d_factor) * w;
      weight += w;
    }
    return weight > 0 ? Math.round((sum / weight) * 100) / 100 : null;
  };

  const ph = layerMean('phh2o');
  const clayPct = layerMean('clay');
  const sandPct = layerMean('sand');
  const organicCarbonGKg = layerMean('soc');
  // Punctele fără date (ape, zone urbane) nu pot fi clasificate.
  if (ph === null || clayPct === null || sandPct === null || organicCarbonGKg === null) return null;
  return {
    ph,
    clayPct,
    sandPct,
    organicCarbonGKg,
    fieldCapacityPct: layerMean('wv0033'),
    wiltingPointPct: layerMean('wv1500'),
  };
}

export async function fetchSoilGrids(lat: number, lng: number): Promise<SoilGridsValues | null> {
  const params = new URLSearchParams({ lon: lng.toFixed(5), lat: lat.toFixed(5), value: 'mean' });
  for (const property of ['phh2o', 'clay', 'sand', 'soc', 'wv0033', 'wv1500']) params.append('property', property);
  for (const depth of Object.keys(DEPTH_WEIGHTS)) params.append('depth', depth);
  const data = await fetchJson<SoilGridsResponse>(
    `https://rest.isric.org/soilgrids/v2.0/properties/query?${params}`,
    { service: 'SoilGrids', timeoutMs: 30_000 },
  );
  return parseSoilGrids(data);
}
