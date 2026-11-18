import { readFileSync } from 'node:fs';
import { initializeTestEnvironment } from '@firebase/rules-unit-testing';
import { disableNetwork, enableNetwork } from 'firebase/firestore';
import ExcelJS from 'exceljs';
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import { firestore } from './stubs/firebaseApp';
import { startFirebase } from '@mobile/firebase/bootstrap';
import { firebaseDownload, firebaseGet, firebaseSend, firebaseUpload } from '@mobile/firebase/gateway';
import { flushWrites } from '@mobile/firebase/outbox';
import { files, File } from './stubs/fileSystem';
import { rejected } from './stubs/sqlite';

const square = {
  type: 'Polygon',
  coordinates: [[[25, 45], [25.01, 45], [25.01, 45.01], [25, 45.01], [25, 45]]],
};

const settle = async () => {
  await flushWrites();
  await new Promise((resolve) => setTimeout(resolve, 400));
};

/** Răspunsuri simulate pentru serviciile publice apelate direct de pe telefon. */
function externalResponse(url: string): unknown {
  if (url.includes('rest.isric.org')) {
    const layer = (name: string, value: number) => ({
      name,
      unit_measure: { d_factor: 10 },
      depths: ['0-5cm', '5-15cm', '15-30cm'].map((label) => ({ label, values: { mean: value } })),
    });
    return { properties: { layers: [layer('phh2o', 68), layer('clay', 300), layer('sand', 250), layer('soc', 200), layer('wv0033', 340), layer('wv1500', 180)] } };
  }
  if (url.includes('archive-api.open-meteo.com')) {
    return { daily: { time: ['2026-05-01', '2026-05-02'], temperature_2m_max: [20, 22], temperature_2m_min: [8, 10], precipitation_sum: [100, 50], et0_fao_evapotranspiration: [3, 3] } };
  }
  if (url.includes('api.open-meteo.com')) {
    const start = Date.UTC(new Date().getUTCFullYear(), new Date().getUTCMonth(), new Date().getUTCDate()) - 86_400_000;
    const time = Array.from({ length: 384 }, (_, i) => new Date(start + i * 3_600_000).toISOString().slice(0, 16));
    const fill = (value: number) => time.map(() => value);
    const days = Array.from({ length: 16 }, (_, i) => new Date(start + i * 86_400_000).toISOString().slice(0, 10));
    const dayFill = (value: number) => days.map(() => value);
    return {
      timezone: 'UTC',
      utc_offset_seconds: 0,
      hourly: { time, temperature_2m: fill(16), relative_humidity_2m: fill(65), precipitation: fill(0), precipitation_probability: fill(0), wind_speed_10m: fill(22), weather_code: fill(1) },
      daily: { time: days, temperature_2m_max: dayFill(22), temperature_2m_min: dayFill(10), temperature_2m_mean: dayFill(16), precipitation_sum: dayFill(1), et0_fao_evapotranspiration: dayFill(3), wind_speed_10m_max: dayFill(22), relative_humidity_2m_mean: dayFill(65) },
    };
  }
  if (url.includes('power.larc.nasa.gov')) {
    return { properties: { parameter: { T2M: { '20240601': 20 }, PRECTOTCORR: { '20240601': 2 } } } };
  }
  throw new Error(`Apel extern neașteptat: ${url}`);
}

let stop: () => void;
let parcelId: string;

beforeAll(async () => {
  const realFetch = globalThis.fetch;
  vi.stubGlobal('fetch', async (input: string | URL | Request, init?: RequestInit) => {
    const url = String(input instanceof Request ? input.url : input);
    if (url.includes('127.0.0.1') || url.includes('localhost')) return realFetch(input, init);
    return new Response(JSON.stringify(externalResponse(url)), { status: 200, headers: { 'Content-Type': 'application/json' } });
  });
  const env = await initializeTestEnvironment({
    projectId: 'demo-fermapp',
    firestore: { rules: readFileSync('firestore.rules', 'utf8'), host: '127.0.0.1', port: 8180 },
  });
  await env.clearFirestore();
  await env.cleanup();
  stop = startFirebase();
  await firebaseSend('POST', '/auth/register', {
    email: `test${Date.now()}@ferma.ro`,
    password: 'parola-sigura-123',
    fullName: 'Ana Pop',
    farmName: 'Ferma Test',
  });
  ({ id: parcelId } = await firebaseSend<{ id: string }>('POST', '/parcels', { name: 'Sud', geometry: square }));
  await settle();
});

afterAll(() => stop?.());

describe('irigații', () => {
  it('apare și când conexiunea cade în timpul salvării', async () => {
    await disableNetwork(firestore);
    const { id } = await firebaseSend<{ id: string }>('POST', '/irrigation', { parcelId, systemType: 'REEL', routeLengthM: 300, speedMPerH: 30 });
    await new Promise((resolve) => setTimeout(resolve, 800));
    await enableNetwork(firestore);
    await settle();
    await new Promise((resolve) => setTimeout(resolve, 1500));
    await settle();
    const runs = await firebaseGet<{ id: string }[]>('/irrigation');
    expect(runs.some((run) => run.id === id)).toBe(true);
    await firebaseSend('POST', `/irrigation/${id}/complete`);
    await settle();
  });

  it('rămâne vizibilă pe hartă după sincronizare și poate fi finalizată', async () => {
    const { id } = await firebaseSend<{ id: string }>('POST', '/irrigation', {
      parcelId,
      systemType: 'PIVOT',
      routeLengthM: 400,
      speedMPerH: 40,
    });
    await settle();
    expect(rejected()).toEqual([]);

    const overview = await firebaseGet<{ parcelId: string; irrigation: { id: string; label: string }[] }[]>('/map/overview');
    expect(overview.find((item) => item.parcelId === parcelId)?.irrigation[0]).toMatchObject({ id, label: 'Pivot #1' });

    await firebaseSend('POST', `/irrigation/${id}/complete`);
    await settle();
    expect(rejected()).toEqual([]);
    const runs = await firebaseGet<{ id: string; status: string }[]>('/irrigation');
    expect(runs.find((run) => run.id === id)?.status).toBe('COMPLETED');
  });
});

describe('funcțiile mutate pe Firebase', () => {
  it('analizează solul parcelei', async () => {
    await firebaseSend('POST', `/parcels/${parcelId}/soil/refresh`);
    await settle();
    const parcel = await firebaseGet<{ soilProfile: { status: string; label: string; indices: { soilScore: number } } }>(`/parcels/${parcelId}`);
    expect(parcel.soilProfile.status).toBe('READY');
    expect(parcel.soilProfile.label).toContain('lutos-argilos');
    expect(parcel.soilProfile.indices.soilScore).toBeGreaterThan(0);
  });

  it('afișează prognoza, istoricul și ferestrele de lucru', async () => {
    const forecast = await firebaseGet<{ days: unknown[] }>(`/parcels/${parcelId}/weather/forecast`, { range: '7d' });
    expect(forecast.days).toHaveLength(7);
    const history = await firebaseGet<{ totals: { precipitationMm: number } }>(`/parcels/${parcelId}/weather/history`, { range: '30d' });
    expect(history.totals.precipitationMm).toBe(150);
    const windows = await firebaseGet<{ operation: string }[]>(`/parcels/${parcelId}/weather/windows`);
    expect(windows.map((item) => item.operation)).toContain('SPRAYING');
  });

  it('calculează costurile și bilanțul de apă', async () => {
    const year = new Date().getFullYear();
    await firebaseSend('POST', `/parcels/${parcelId}/seasons`, { crop: 'CORN', year, sowingDate: new Date(Date.now() - 30 * 86_400_000) });
    await firebaseSend('POST', '/costs', { parcelId, year, category: 'SEED', amount: 1000 });
    await settle();
    const summary = await firebaseGet<{ totalCost: number }>(`/parcels/${parcelId}/costs/summary`, { year });
    expect(summary.totalCost).toBe(1000);
    const water = await firebaseGet<{ cropName: string; precipitationMm: number }>(`/parcels/${parcelId}/water`, { year });
    expect(water).toMatchObject({ cropName: 'Porumb', precipitationMm: 150 });
  });

  it('completează panoul parcelei cu meteo și economie', async () => {
    const panel = await firebaseGet<{ weather: { next14: unknown[]; alerts: { code: string }[] }; economics: { seedsCost: number }; crop: { name: string } }>(`/parcels/${parcelId}/intelligence`);
    expect(panel.weather.next14).toHaveLength(14);
    expect(panel.weather.alerts[0]?.code).toBe('WIND');
    expect(panel.economics.seedsCost).toBe(1000);
    expect(panel.crop.name).toBe('Porumb');
  });

  it('salvează fotografia și o afișează în panou', async () => {
    await firebaseUpload(`/parcels/${parcelId}/photos`, { uri: 'file://poza.jpg' }, { notes: 'Rând rărit' });
    await settle();
    expect(rejected()).toEqual([]);
    const panel = await firebaseGet<{ photos: { notes: string; imageUri: string }[] }>(`/parcels/${parcelId}/intelligence`);
    expect(panel.photos[0]).toMatchObject({ notes: 'Rând rărit', imageUri: 'data:image/jpeg;base64,aW1hZ2luZQ==' });
  });

  it('generează toate rapoartele, inclusiv PDF și Excel', async () => {
    const year = new Date().getFullYear();
    for (const type of ['work', 'fuel', 'costs', 'yields', 'rotation', 'irrigation']) {
      const report = await firebaseGet<{ columns: unknown[] }>(`/reports/${type}`, { year });
      expect(report.columns.length).toBeGreaterThan(0);
    }
    const uri = await firebaseDownload(`/reports/costs?year=${year}&format=xlsx`, 'raport.xlsx');
    const workbook = new ExcelJS.Workbook();
    await workbook.xlsx.load(files.get(uri) as never);
    const sheet = workbook.worksheets[0]!;
    expect(String(sheet.getCell('A1').value)).toContain('Costuri și profit');
    expect(sheet.getCell('A5').value).toBe('Sud');
    expect(await firebaseDownload(`/reports/work?year=${year}&format=pdf`, 'raport.pdf')).toContain('raport.pdf');
    expect(await firebaseDownload(`/parcels/${parcelId}/report`, 'parcela.pdf')).toContain('parcela.pdf');
  });

  it.each(['geojson', 'kml', 'kmz', 'shp', 'lpis'])('exportă și reimportă parcelele în format %s', async (format) => {
    const uri = await firebaseDownload(`/parcels/export?format=${format}`, `parcele.${format}`);
    const preview = await firebaseUpload<{ parcels: { areaHa: number }[]; problems: unknown[] }>(`/parcels/import?format=${format}&dryRun=true`, { uri });
    expect(preview.problems).toEqual([]);
    expect(preview.parcels.length).toBeGreaterThan(0);
    expect(preview.parcels[0]!.areaHa).toBeGreaterThan(50);
  });

  it('importă efectiv parcelele dintr-un fișier', async () => {
    const file = new File('cache', 'import.geojson');
    file.write(JSON.stringify({ type: 'Feature', properties: { nume: 'Importată' }, geometry: { type: 'Polygon', coordinates: [[[26, 46], [26.01, 46], [26.01, 46.01], [26, 46]]] } }));
    await firebaseUpload('/parcels/import?format=geojson', { uri: file.uri });
    await settle();
    const parcels = await firebaseGet<{ name: string }[]>('/parcels');
    expect(parcels.some((parcel) => parcel.name === 'Importată')).toBe(true);
  });

  it('răspunde la întrebări din datele parcelei', async () => {
    const answer = await firebaseSend<{ message: { content: string; source: string } }>('POST', '/ai/chat', { message: 'Ce fac?', parcelId });
    expect(answer.message.source).toBe('rules');
    expect(answer.message.content).toContain('Sud');
  });

  it('explică de ce imaginile satelitare nu sunt disponibile', async () => {
    const satellite = await firebaseGet<{ configured: boolean }>(`/parcels/${parcelId}/satellite`);
    expect(satellite.configured).toBe(false);
    await expect(firebaseSend('POST', `/parcels/${parcelId}/satellite/refresh`, { days: 90 })).rejects.toThrow('Copernicus');
  });

  it('spune clar când o operațiune nu mai există', async () => {
    await expect(firebaseSend('POST', '/irrigation/inexistent/complete')).rejects.toThrow('nu a fost găsită');
    expect(rejected()).toEqual([]);
  });
});

describe('catalog agricol', () => {
  it('încarcă automat produsele în catalogul fermei', async () => {
    await settle();
    const seeds = await firebaseGet<{ name: string; catalogKey: string }[]>('/catalog/seeds');
    const tractors = await firebaseGet<{ model: string }[]>('/catalog/tractors');
    expect(seeds).toHaveLength(23);
    expect(tractors.some((item) => item.model === '8R 340')).toBe(true);
    expect(rejected()).toEqual([]);
  });

  it('afișează hibridul ales și recomandările în panoul parcelei', async () => {
    const year = new Date().getFullYear();
    const seeds = await firebaseGet<{ id: string; name: string }[]>('/catalog/seeds');
    const seed = seeds.find((item) => item.name === 'Pioneer P0710')!;
    await firebaseSend('PATCH', `/seasons/${parcelId}_${year}`, { seedId: seed.id, seedName: seed.name });
    await firebaseSend('POST', '/tasks', { type: 'HERBICIDE', priority: 'MEDIUM', dueDate: new Date(Date.now() + 86_400_000), parcelId });
    await settle();
    const panel = await firebaseGet<{ catalog: { seed: { name: string; fao: number; compatibility: number }; recommended: unknown[]; operation: string; machine: { tractorName: string; fuelCost: number } } }>(`/parcels/${parcelId}/intelligence`);
    expect(panel.catalog.seed).toMatchObject({ name: 'Pioneer P0710', fao: 410 });
    expect(panel.catalog.seed.compatibility).toBeGreaterThan(0);
    expect(panel.catalog.recommended).toHaveLength(3);
    expect(panel.catalog.operation).toBe('SPRAYING');
    expect(panel.catalog.machine.fuelCost).toBeGreaterThan(0);
  });

  it('adaugă, editează și șterge un produs', async () => {
    const { id } = await firebaseSend<{ id: string }>('POST', '/catalog/seeds', { name: 'Test 100', crop: 'CORN', type: 'HYBRID', fao: 300 });
    await firebaseSend('POST', `/catalog/seeds/${id}`, { name: 'Test 200', crop: 'CORN', type: 'HYBRID', fao: 310 });
    await settle();
    const edited = (await firebaseGet<{ id: string; name: string }[]>('/catalog/seeds')).find((item) => item.id === id);
    expect(edited?.name).toBe('Test 200');
    await firebaseSend('DELETE', `/catalog/seeds/${id}`);
    await settle();
    expect((await firebaseGet<{ id: string }[]>('/catalog/seeds')).some((item) => item.id === id)).toBe(false);
  });

  it('importă produse din CSV și din Excel', async () => {
    const csv = new File('cache', 'seminte.csv');
    csv.write('Nume,Cultura,FAO\nHibrid CSV,Porumb,330\nGreșit,,\n');
    const fromCsv = await firebaseUpload<{ imported: number; problems: string[] }>('/catalog/import?kind=seeds', { uri: csv.uri });
    expect(fromCsv).toMatchObject({ imported: 1 });
    expect(fromCsv.problems).toHaveLength(1);

    const workbook = new ExcelJS.Workbook();
    const sheet = workbook.addWorksheet('Tractoare');
    sheet.addRow(['Marca', 'Model', 'CP']);
    sheet.addRow(['Zetor', 'Forterra 140', 140]);
    const excel = new File('cache', 'tractoare.xlsx');
    excel.write(new Uint8Array(await workbook.xlsx.writeBuffer()));
    expect(await firebaseUpload('/catalog/import?kind=tractors', { uri: excel.uri })).toMatchObject({ imported: 1, problems: [] });
    await settle();
    expect((await firebaseGet<{ model: string }[]>('/catalog/tractors')).some((item) => item.model === 'Forterra 140')).toBe(true);
    expect(rejected()).toEqual([]);
  });
});
