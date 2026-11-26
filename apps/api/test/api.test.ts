import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import JSZip from 'jszip';
import { io as connectSocket } from 'socket.io-client';
import request from 'supertest';
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import { createApp } from '../src/app';
import { sendTaskReminders, tickProgress } from '../src/jobs/scheduler';
import { prisma } from '../src/lib/prisma';
import { initRealtime } from '../src/realtime/socket';

const app = createApp();
const api = '/api/v1';
const PASSWORD = 'parola-sigura-123';

const square = (lng = 25, lat = 45, size = 0.01) => ({
  type: 'Polygon' as const,
  coordinates: [[[lng, lat], [lng + size, lat], [lng + size, lat + size], [lng, lat + size], [lng, lat]]],
});

let counter = 0;
const uniqueEmail = () => `user${(counter += 1)}-${Date.now()}@farm.test`;

async function registerFarm() {
  const email = uniqueEmail();
  const res = await request(app)
    .post(`${api}/auth/register`)
    .send({ email, password: PASSWORD, fullName: 'Ana Pop', farmName: 'Test Farm' })
    .expect(201);
  return { ...res.body.data, email, token: res.body.data.accessToken as string };
}

const bearer = (token: string) => ({ Authorization: `Bearer ${token}` });

async function addMember(adminToken: string, role: string) {
  const email = uniqueEmail();
  await request(app)
    .post(`${api}/team`)
    .set(bearer(adminToken))
    .send({ email, fullName: `Member ${role}`, role, password: PASSWORD })
    .expect(201);
  const login = await request(app).post(`${api}/auth/login`).send({ email, password: PASSWORD }).expect(200);
  return { token: login.body.data.accessToken as string, userId: login.body.data.user.id as string };
}

async function createParcel(token: string, name = 'South-1', geometry = square()) {
  const res = await request(app).post(`${api}/parcels`).set(bearer(token)).send({ name, geometry }).expect(201);
  return res.body.data;
}

/** Mocked responses for all external services. */
function externalResponse(url: string): unknown {
  if (url.includes('rest.isric.org')) {
    const layer = (name: string, value: number) => ({
      name,
      unit_measure: { d_factor: 10 },
      depths: ['0-5cm', '5-15cm', '15-30cm'].map((label) => ({ label, values: { mean: value } })),
    });
    return {
      properties: {
        layers: [
          layer('phh2o', 68),
          layer('clay', 420),
          layer('sand', 200),
          layer('soc', 226),
          layer('wv0033', 340),
          layer('wv1500', 180),
        ],
      },
    };
  }
  if (url.includes('//api.open-meteo.com')) {
    const start = Date.UTC(new Date().getUTCFullYear(), new Date().getUTCMonth(), new Date().getUTCDate()) - 86_400_000;
    const time = Array.from({ length: 384 }, (_, i) => new Date(start + i * 3_600_000).toISOString().slice(0, 16));
    const fill = (value: number) => time.map(() => value);
    const days = Array.from({ length: 16 }, (_, i) => new Date(start + i * 86_400_000).toISOString().slice(0, 10));
    const dayFill = (value: number) => days.map(() => value);
    return {
      timezone: 'UTC',
      utc_offset_seconds: 0,
      hourly: {
        time,
        temperature_2m: fill(16),
        relative_humidity_2m: fill(65),
        precipitation: fill(0),
        precipitation_probability: fill(0),
        wind_speed_10m: fill(22),
        shortwave_radiation: fill(300),
        evapotranspiration: fill(0.1),
        et0_fao_evapotranspiration: fill(0.2),
        weather_code: fill(1),
      },
      daily: {
        time: days,
        temperature_2m_max: dayFill(22),
        temperature_2m_min: dayFill(10),
        temperature_2m_mean: dayFill(16),
        precipitation_sum: dayFill(1),
        shortwave_radiation_sum: dayFill(18),
        et0_fao_evapotranspiration: dayFill(3),
        wind_speed_10m_max: dayFill(22),
        relative_humidity_2m_mean: dayFill(65),
      },
    };
  }
  if (url.includes('archive-api.open-meteo.com')) {
    return {
      daily: {
        time: ['2026-05-01', '2026-05-02'],
        temperature_2m_max: [20, 22],
        temperature_2m_min: [8, 10],
        temperature_2m_mean: [14, 16],
        precipitation_sum: [100, 225],
        shortwave_radiation_sum: [18, 19],
        et0_fao_evapotranspiration: [3, 3],
        wind_speed_10m_max: [12, 14],
        relative_humidity_2m_mean: [60, 62],
      },
    };
  }
  if (url.includes('power.larc.nasa.gov')) {
    const series = (value: number) => ({ '20240601': value, '20240602': -999 });
    return {
      properties: {
        parameter: {
          T2M: series(20),
          T2M_MAX: series(28),
          T2M_MIN: series(12),
          PRECTOTCORR: series(2),
          ALLSKY_SFC_SW_DWN: series(20),
        },
      },
    };
  }
  if (url.includes('exp.host')) return { data: [] };
  throw new Error(`Unexpected external call: ${url}`);
}

beforeAll(() => {
  const realFetch = globalThis.fetch;
  vi.stubGlobal('fetch', async (input: string | URL | Request, init?: RequestInit) => {
    const url = String(input instanceof Request ? input.url : input);
    if (url.startsWith('http://127.0.0.1') || url.startsWith('http://localhost')) return realFetch(input, init);
    return new Response(JSON.stringify(externalResponse(url)), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    });
  });
});

afterAll(async () => {
  vi.unstubAllGlobals();
  await prisma.$disconnect();
});

describe('authentication', () => {
  it('rotates the refresh token and revokes the family on reuse', async () => {
    const farm = await registerFarm();
    const first = await request(app).post(`${api}/auth/refresh`).send({ refreshToken: farm.refreshToken }).expect(200);
    await request(app).post(`${api}/auth/refresh`).send({ refreshToken: farm.refreshToken }).expect(401);
    await request(app)
      .post(`${api}/auth/refresh`)
      .send({ refreshToken: first.body.data.refreshToken })
      .expect(401);
  });

  it('rejects a wrong password and a missing token', async () => {
    const farm = await registerFarm();
    await request(app).post(`${api}/auth/login`).send({ email: farm.email, password: 'wrong-123456' }).expect(401);
    await request(app).get(`${api}/parcels`).expect(401);
    await request(app).get(`${api}/parcels`).set(bearer('token-invalid')).expect(401);
  });

  it('keeps at least one administrator', async () => {
    const farm = await registerFarm();
    await request(app)
      .patch(`${api}/team/${farm.user.id}`)
      .set(bearer(farm.token))
      .send({ role: 'OPERATOR' })
      .expect(400);
  });
});

describe('roles and farm isolation', () => {
  it('applies the permissions of each role', async () => {
    const farm = await registerFarm();
    const operator = await addMember(farm.token, 'OPERATOR');
    const agronomist = await addMember(farm.token, 'AGRONOMIST');
    const parcel = await createParcel(farm.token);

    await request(app).post(`${api}/parcels`).set(bearer(operator.token)).send({ name: 'X', geometry: square() }).expect(403);
    await request(app).get(`${api}/costs`).set(bearer(operator.token)).expect(403);
    await request(app).get(`${api}/reports/costs`).set(bearer(operator.token)).expect(403);
    await request(app)
      .post(`${api}/parcels/${parcel.id}/seasons`)
      .set(bearer(agronomist.token))
      .send({ crop: 'WHEAT', year: 2025 })
      .expect(201);
    await request(app)
      .post(`${api}/work`)
      .set(bearer(agronomist.token))
      .send({ parcelId: parcel.id, type: 'SOWING', workingWidthM: 6, speedKmh: 8, efficiencyPct: 80 })
      .expect(403);
  });

  it('does not expose another farm’s data', async () => {
    const a = await registerFarm();
    const b = await registerFarm();
    const parcel = await createParcel(a.token);

    await request(app).get(`${api}/parcels/${parcel.id}`).set(bearer(b.token)).expect(404);
    await request(app).patch(`${api}/parcels/${parcel.id}`).set(bearer(b.token)).send({ name: 'Stolen' }).expect(404);
    await request(app).get(`${api}/parcels/${parcel.id}/soil`).set(bearer(b.token)).expect(404);
    await request(app)
      .post(`${api}/work`)
      .set(bearer(b.token))
      .send({ parcelId: parcel.id, type: 'SOWING', workingWidthM: 6, speedKmh: 8, efficiencyPct: 80 })
      .expect(404);
    await request(app)
      .post(`${api}/tasks`)
      .set(bearer(b.token))
      .send({ type: 'HARVEST', dueDate: new Date(), parcelId: parcel.id })
      .expect(404);
    const list = await request(app).get(`${api}/parcels`).set(bearer(b.token)).expect(200);
    expect(list.body.data).toEqual([]);
  });
});

describe('parcels', () => {
  it('computes the area automatically and generates the soil profile', async () => {
    const farm = await registerFarm();
    const parcel = await createParcel(farm.token);
    expect(parcel.areaHa).toBeGreaterThan(80);
    expect(parcel.perimeterM).toBeGreaterThan(3000);
    expect(parcel.code).toBe('P-0001');

    await vi.waitFor(async () => {
      const soil = await request(app).get(`${api}/parcels/${parcel.id}/soil`).set(bearer(farm.token)).expect(200);
      expect(soil.body.data.status).toBe('READY');
      expect(soil.body.data.label).toBe('Chernozem (clay)');
    });
  });

  it('rejects a self-intersecting polygon', async () => {
    const farm = await registerFarm();
    const res = await request(app)
      .post(`${api}/parcels`)
      .set(bearer(farm.token))
      .send({ name: 'Bad', geometry: { type: 'Polygon', coordinates: [[[25, 45], [25.01, 45.01], [25.01, 45], [25, 45.01], [25, 45]]] } })
      .expect(400);
    expect(JSON.stringify(res.body)).toContain('intersects');
  });

  it('archives the parcel and updates the dashboard', async () => {
    const farm = await registerFarm();
    const parcel = await createParcel(farm.token);
    await request(app)
      .post(`${api}/parcels/${parcel.id}/seasons`)
      .set(bearer(farm.token))
      .send({ crop: 'CORN', year: new Date().getFullYear() })
      .expect(201);
    const before = await request(app).get(`${api}/dashboard`).set(bearer(farm.token)).expect(200);
    expect(before.body.data.parcels.count).toBe(1);
    expect(before.body.data.parcels.freeAreaHa).toBe(0);

    await request(app).post(`${api}/parcels/${parcel.id}/archive`).set(bearer(farm.token)).expect(200);
    const after = await request(app).get(`${api}/dashboard`).set(bearer(farm.token)).expect(200);
    expect(after.body.data.parcels.count).toBe(0);
  });

  it.each(['geojson', 'kml', 'kmz', 'shp', 'lpis'] as const)('exports and re-imports the %s format', async (format) => {
    const source = await registerFarm();
    const original = await createParcel(source.token, 'North & West', square(24, 46));

    const exported = await request(app)
      .get(`${api}/parcels/export?format=${format}`)
      .set(bearer(source.token))
      .buffer(true)
      .parse((res, done) => {
        const chunks: Buffer[] = [];
        res.on('data', (chunk: Buffer) => chunks.push(chunk));
        res.on('end', () => done(null, Buffer.concat(chunks)));
      })
      .expect(200);

    const target = await registerFarm();
    const preview = await request(app)
      .post(`${api}/parcels/import?format=${format}&dryRun=true`)
      .set(bearer(target.token))
      .attach('file', exported.body as Buffer, `parcels.${format}`)
      .expect(201);
    expect(preview.body.data.parcels).toHaveLength(1);
    expect(preview.body.data.created).toBe(0);

    const imported = await request(app)
      .post(`${api}/parcels/import?format=${format}`)
      .set(bearer(target.token))
      .attach('file', exported.body as Buffer, `parcels.${format}`)
      .expect(201);
    expect(imported.body.data.created).toBe(1);
    const parcels = await request(app).get(`${api}/parcels`).set(bearer(target.token)).expect(200);
    expect(parcels.body.data[0].name).toBe('North & West');
    expect(parcels.body.data[0].areaHa).toBeCloseTo(original.areaHa, 0);
  });

  it('rejects a corrupt KMZ archive', async () => {
    const farm = await registerFarm();
    await request(app)
      .post(`${api}/parcels/import?format=kmz`)
      .set(bearer(farm.token))
      .attach('file', Buffer.from('not a zip'), 'x.kmz')
      .expect(400);
    const empty = await new JSZip().file('note.txt', 'no kml').generateAsync({ type: 'nodebuffer' });
    await request(app)
      .post(`${api}/parcels/import?format=kmz`)
      .set(bearer(farm.token))
      .attach('file', empty, 'x.kmz')
      .expect(400);
  });
});

describe('crops and rotation', () => {
  it('explains the rotation violation', async () => {
    const farm = await registerFarm();
    const parcel = await createParcel(farm.token);
    await request(app)
      .post(`${api}/parcels/${parcel.id}/seasons`)
      .set(bearer(farm.token))
      .send({ crop: 'SUNFLOWER', year: 2024 })
      .expect(201);
    const res = await request(app)
      .post(`${api}/parcels/${parcel.id}/rotation/evaluate`)
      .set(bearer(farm.token))
      .send({ crop: 'SUNFLOWER', year: 2026 })
      .expect(200);
    expect(res.body.data.verdict).toBe('NOT_RECOMMENDED');
    expect(res.body.data.explanation).toContain('2 years ago');
  });

  it('does not accept two crops in the same year', async () => {
    const farm = await registerFarm();
    const parcel = await createParcel(farm.token);
    const url = `${api}/parcels/${parcel.id}/seasons`;
    await request(app).post(url).set(bearer(farm.token)).send({ crop: 'WHEAT', year: 2025 }).expect(201);
    await request(app).post(url).set(bearer(farm.token)).send({ crop: 'CORN', year: 2025 }).expect(409);
  });

  it('recommends crops based on soil and climate', async () => {
    const farm = await registerFarm();
    const parcel = await createParcel(farm.token);
    const res = await request(app)
      .get(`${api}/parcels/${parcel.id}/recommendations`)
      .set(bearer(farm.token))
      .expect(200);
    expect(res.body.data.recommendations).toHaveLength(11);
    expect(res.body.data.recommendations[0].reasons.length).toBeGreaterThan(0);
  });
});

describe('work', () => {
  const workBody = (parcelId: string, machineId?: string) => ({
    parcelId,
    machineId,
    type: 'SOWING',
    workingWidthM: 6,
    speedKmh: 8,
    efficiencyPct: 80,
  });

  it('computes capacity and completes automatically at 100%', async () => {
    const farm = await registerFarm();
    const parcel = await createParcel(farm.token);
    const machine = await request(app)
      .post(`${api}/machines`)
      .set(bearer(farm.token))
      .send({ name: 'Fendt 724', type: 'TRACTOR', brand: 'Fendt', avgConsumptionLh: 20 })
      .expect(201);

    const started = await request(app)
      .post(`${api}/work`)
      .set(bearer(farm.token))
      .send(workBody(parcel.id, machine.body.data.id))
      .expect(201);
    expect(started.body.data.capacityHaH).toBe(3.84);
    expect(started.body.data.status).toBe('RUNNING');

    await request(app)
      .post(`${api}/work`)
      .set(bearer(farm.token))
      .send(workBody(parcel.id, machine.body.data.id))
      .expect(409);

    const end = new Date(Date.now() + started.body.data.durationHours * 3_600_000 + 60_000);
    await tickProgress(end);

    const done = await request(app).get(`${api}/work/${started.body.data.id}`).set(bearer(farm.token)).expect(200);
    expect(done.body.data.status).toBe('COMPLETED');
    expect(done.body.data.progressPct).toBe(100);
    expect(done.body.data.fuelLiters).toBeCloseTo(started.body.data.durationHours * 20, 0);

    const notes = await request(app).get(`${api}/notifications`).set(bearer(farm.token)).expect(200);
    expect(notes.body.data[0].title).toBe('Operation completed');
  });

  it('stops the timer on pause', async () => {
    const farm = await registerFarm();
    const parcel = await createParcel(farm.token);
    const started = await request(app).post(`${api}/work`).set(bearer(farm.token)).send(workBody(parcel.id)).expect(201);
    const id = started.body.data.id;
    await request(app).post(`${api}/work/${id}/pause`).set(bearer(farm.token)).expect(200);
    await request(app).post(`${api}/work/${id}/pause`).set(bearer(farm.token)).expect(409);
    const resumed = await request(app).post(`${api}/work/${id}/resume`).set(bearer(farm.token)).expect(200);
    expect(resumed.body.data.status).toBe('RUNNING');
  });

  it('does not let an operator control another operator’s work', async () => {
    const farm = await registerFarm();
    const first = await addMember(farm.token, 'OPERATOR');
    const second = await addMember(farm.token, 'OPERATOR');
    const parcel = await createParcel(farm.token);
    const started = await request(app).post(`${api}/work`).set(bearer(first.token)).send(workBody(parcel.id)).expect(201);
    await request(app).post(`${api}/work/${started.body.data.id}/cancel`).set(bearer(second.token)).expect(403);
  });

  it('sends events via Socket.IO only to the correct farm', async () => {
    const farm = await registerFarm();
    const other = await registerFarm();
    const parcel = await createParcel(farm.token);
    const server: Server = createServer(app);
    initRealtime(server);
    await new Promise<void>((resolve) => server.listen(0, resolve));
    const { port } = server.address() as AddressInfo;
    const url = `http://127.0.0.1:${port}`;
    const socket = connectSocket(url, { auth: { token: farm.token }, transports: ['websocket'] });
    const outsider = connectSocket(url, { auth: { token: other.token }, transports: ['websocket'] });
    const leaked = vi.fn();
    outsider.on('work:updated', leaked);
    await Promise.all([
      new Promise((resolve) => socket.on('connect', () => resolve(true))),
      new Promise((resolve) => outsider.on('connect', () => resolve(true))),
    ]);

    const received = new Promise<{ parcelId: string }>((resolve) => socket.on('work:updated', resolve));
    await request(app).post(`${api}/work`).set(bearer(farm.token)).send(workBody(parcel.id)).expect(201);
    expect((await received).parcelId).toBe(parcel.id);
    expect(leaked).not.toHaveBeenCalled();

    const denied = connectSocket(url, { auth: { token: 'invalid' }, transports: ['websocket'] });
    const error = await new Promise<Error>((resolve) => denied.on('connect_error', resolve));
    expect(error.message).toBe('UNAUTHORIZED');

    socket.close();
    outsider.close();
    denied.close();
    await new Promise((resolve) => server.close(resolve));
  });
});

describe('tasks', () => {
  it('sends a single reminder and restricts the operator', async () => {
    const farm = await registerFarm();
    const operator = await addMember(farm.token, 'OPERATOR');
    const created = await request(app)
      .post(`${api}/tasks`)
      .set(bearer(farm.token))
      .send({ type: 'HERBICIDE', priority: 'HIGH', dueDate: new Date(Date.now() + 3_600_000), assigneeId: operator.userId })
      .expect(201);
    expect(created.body.data.title).toBe('Herbicide application');

    await sendTaskReminders();
    await sendTaskReminders();
    const notes = await prisma.notification.count({ where: { userId: operator.userId, title: 'Deadline approaching' } });
    expect(notes).toBe(1);

    const other = await request(app)
      .post(`${api}/tasks`)
      .set(bearer(farm.token))
      .send({ type: 'HARVEST', dueDate: new Date() })
      .expect(201);
    await request(app)
      .post(`${api}/tasks/${other.body.data.id}/status`)
      .set(bearer(operator.token))
      .send({ status: 'DONE' })
      .expect(403);
    const done = await request(app)
      .post(`${api}/tasks/${created.body.data.id}/status`)
      .set(bearer(operator.token))
      .send({ status: 'DONE' })
      .expect(200);
    expect(done.body.data.completedAt).not.toBeNull();
  });
});

describe('irrigation and water', () => {
  it('completes the irrigation and includes the dose in the balance', async () => {
    const farm = await registerFarm();
    const parcel = await createParcel(farm.token, 'Irrigated', square(26, 44));
    const year = new Date().getFullYear();
    await request(app)
      .post(`${api}/parcels/${parcel.id}/seasons`)
      .set(bearer(farm.token))
      .send({ crop: 'CORN', year })
      .expect(201);

    const started = await request(app)
      .post(`${api}/irrigation`)
      .set(bearer(farm.token))
      .send({ parcelId: parcel.id, systemType: 'REEL', routeLengthM: 400, speedMPerH: 25, doseMm: 30 })
      .expect(201);
    expect(started.body.data.durationHours).toBe(16);
    await request(app)
      .post(`${api}/irrigation`)
      .set(bearer(farm.token))
      .send({ parcelId: parcel.id, systemType: 'REEL', routeLengthM: 400, speedMPerH: 25 })
      .expect(409);

    await tickProgress(new Date(Date.now() + 17 * 3_600_000));
    const finished = await request(app).get(`${api}/irrigation/${started.body.data.id}`).set(bearer(farm.token)).expect(200);
    expect(finished.body.data.status).toBe('COMPLETED');

    await prisma.irrigationRun.update({ where: { id: started.body.data.id }, data: { startedAt: new Date(Date.UTC(year, 3, 2)) } });
    const water = await request(app).get(`${api}/parcels/${parcel.id}/water`).set(bearer(farm.token)).expect(200);
    expect(water.body.data.precipitationMm).toBe(325);
    expect(water.body.data.irrigationMm).toBe(30);
    expect(water.body.data.deficitMm).toBe(270);
  });
});

describe('weather', () => {
  it('provides the forecast and the work windows', async () => {
    const farm = await registerFarm();
    const parcel = await createParcel(farm.token, 'Weather', square(23, 47));
    const day = await request(app).get(`${api}/parcels/${parcel.id}/weather/forecast?range=24h`).set(bearer(farm.token)).expect(200);
    expect(day.body.data.hours).toHaveLength(24);
    const week = await request(app).get(`${api}/parcels/${parcel.id}/weather/forecast?range=14d`).set(bearer(farm.token)).expect(200);
    expect(week.body.data.days).toHaveLength(14);

    const windows = await request(app).get(`${api}/parcels/${parcel.id}/weather/windows`).set(bearer(farm.token)).expect(200);
    const spraying = windows.body.data.find((item: { operation: string }) => item.operation === 'SPRAYING');
    expect(spraying.windows).toEqual([]);
    expect(spraying.message).toContain('No suitable window');
  });

  it('creates the wind alert only once per day', async () => {
    const farm = await registerFarm();
    const parcel = await createParcel(farm.token, 'South-1', square(22, 47));
    const url = `${api}/parcels/${parcel.id}/alerts/check`;
    const first = await request(app).post(url).set(bearer(farm.token)).expect(200);
    expect(first.body.data[0].message).toBe('Wind 22 km/h. Herbicide spraying is not recommended.');
    const second = await request(app).post(url).set(bearer(farm.token)).expect(200);
    expect(second.body.data).toEqual([]);
  });
});

describe('satellite', () => {
  it('explains the missing configuration without invented data', async () => {
    const farm = await registerFarm();
    const parcel = await createParcel(farm.token);
    const res = await request(app)
      .post(`${api}/parcels/${parcel.id}/satellite/refresh`)
      .set(bearer(farm.token))
      .send({ days: 30 })
      .expect(503);
    expect(res.body.error.code).toBe('SERVICE_NOT_CONFIGURED');
  });
});

describe('costs and reports', () => {
  it('computes the profit and generates PDF and Excel', async () => {
    const farm = await registerFarm();
    const parcel = await createParcel(farm.token, 'Șes Țarină'); // Diacritics must survive in PDF and Excel.
    const year = 2026;
    await request(app)
      .post(`${api}/parcels/${parcel.id}/seasons`)
      .set(bearer(farm.token))
      .send({ crop: 'WHEAT', year, expectedYieldTHa: 6, expectedPricePerT: 1000 })
      .expect(201);
    await request(app)
      .post(`${api}/costs`)
      .set(bearer(farm.token))
      .send({ parcelId: parcel.id, year, category: 'SEED', amount: 1234.56 })
      .expect(201);

    const summary = await request(app)
      .get(`${api}/parcels/${parcel.id}/costs/summary?year=${year}`)
      .set(bearer(farm.token))
      .expect(200);
    expect(summary.body.data.totalCost).toBe(1234.56);
    expect(summary.body.data.estimatedProfit).toBeGreaterThan(0);

    const json = await request(app).get(`${api}/reports/costs?year=${year}`).set(bearer(farm.token)).expect(200);
    expect(json.body.data.rows[0].parcel).toBe('Șes Țarină');

    const binary = (res: request.Response, done: (error: Error | null, body: Buffer) => void) => {
      const chunks: Buffer[] = [];
      res.on('data', (chunk: Buffer) => chunks.push(chunk));
      res.on('end', () => done(null, Buffer.concat(chunks)));
    };
    for (const type of ['work', 'fuel', 'costs', 'yields', 'rotation', 'irrigation']) {
      const pdf = await request(app)
        .get(`${api}/reports/${type}?year=${year}&format=pdf`)
        .set(bearer(farm.token))
        .buffer(true)
        .parse(binary as never)
        .expect(200);
      expect((pdf.body as Buffer).subarray(0, 4).toString()).toBe('%PDF');
      const xlsx = await request(app)
        .get(`${api}/reports/${type}?year=${year}&format=xlsx`)
        .set(bearer(farm.token))
        .buffer(true)
        .parse(binary as never)
        .expect(200);
      expect((xlsx.body as Buffer).subarray(0, 2).toString()).toBe('PK');
    }
  });
});

describe('offline sync', () => {
  it('does not duplicate a resent operation', async () => {
    const farm = await registerFarm();
    const body = { name: 'Offline', geometry: square() };
    const key = `op-${Date.now()}`;
    const first = await request(app).post(`${api}/parcels`).set(bearer(farm.token)).set('Idempotency-Key', key).send(body).expect(201);
    const second = await request(app).post(`${api}/parcels`).set(bearer(farm.token)).set('Idempotency-Key', key).send(body).expect(201);
    expect(second.headers['idempotency-replayed']).toBe('true');
    expect(second.body.data.id).toBe(first.body.data.id);
    const list = await request(app).get(`${api}/parcels`).set(bearer(farm.token)).expect(200);
    expect(list.body.data).toHaveLength(1);
  });

  it('allows a retry after an error', async () => {
    const farm = await registerFarm();
    const key = `retry-${Date.now()}`;
    await request(app).post(`${api}/parcels`).set(bearer(farm.token)).set('Idempotency-Key', key).send({ name: '' }).expect(400);
    await request(app)
      .post(`${api}/parcels`)
      .set(bearer(farm.token))
      .set('Idempotency-Key', key)
      .send({ name: 'Good', geometry: square() })
      .expect(201);
  });

  it('returns only the new changes', async () => {
    const farm = await registerFarm();
    await createParcel(farm.token, 'Old');
    const first = await request(app).get(`${api}/sync/changes`).set(bearer(farm.token)).expect(200);
    expect(first.body.data.parcels).toHaveLength(1);
    await new Promise((resolve) => setTimeout(resolve, 10));
    const since = new Date().toISOString();
    await new Promise((resolve) => setTimeout(resolve, 10));
    await createParcel(farm.token, 'New');
    const next = await request(app).get(`${api}/sync/changes`).query({ since }).set(bearer(farm.token)).expect(200);
    expect(next.body.data.parcels.map((p: { name: string }) => p.name)).toEqual(['New']);
  });
});

describe('AI assistant', () => {
  it('answers from the parcel data when the model is not configured', async () => {
    const farm = await registerFarm();
    const parcel = await createParcel(farm.token, 'North 3', square(25.5, 45.5));
    await request(app)
      .post(`${api}/parcels/${parcel.id}/seasons`)
      .set(bearer(farm.token))
      .send({ crop: 'SUNFLOWER', year: new Date().getFullYear() - 1 })
      .expect(201);
    await vi.waitFor(async () => {
      const soil = await prisma.soilProfile.findUnique({ where: { parcelId: parcel.id } });
      expect(soil?.status).toBe('READY');
    });
    const res = await request(app)
      .post(`${api}/ai/chat`)
      .set(bearer(farm.token))
      .send({ message: 'What should I grow next year?', parcelId: parcel.id })
      .expect(200);
    expect(res.body.data.message.source).toBe('rules');
    expect(res.body.data.message.content).toContain('pH 6.8');

    const other = await registerFarm();
    await request(app)
      .get(`${api}/ai/conversations/${res.body.data.conversationId}`)
      .set(bearer(other.token))
      .expect(404);
  });
});

describe('IoT', () => {
  it('receives readings with the device key and ignores duplicates', async () => {
    const farm = await registerFarm();
    const device = await request(app)
      .post(`${api}/iot/devices`)
      .set(bearer(farm.token))
      .send({ name: 'Soil sensor', kind: 'SOIL_MOISTURE', protocol: 'REST' })
      .expect(201);
    const payload = { readings: [{ metric: 'moisture', value: 31.5, unit: '%', recordedAt: '2026-05-01T08:00:00Z' }] };
    await request(app).post(`${api}/iot/ingest`).set('X-Device-Key', 'wrong-key').send(payload).expect(401);
    const first = await request(app).post(`${api}/iot/ingest`).set('X-Device-Key', device.body.data.deviceKey).send(payload).expect(202);
    expect(first.body.data.accepted).toBe(1);
    const again = await request(app).post(`${api}/iot/ingest`).set('X-Device-Key', device.body.data.deviceKey).send(payload).expect(202);
    expect(again.body.data.accepted).toBe(0);

    const list = await request(app).get(`${api}/iot/devices`).set(bearer(farm.token)).expect(200);
    expect(list.body.data[0].keyHash).toBeUndefined();
  });
});

describe('Parcel Intelligence', () => {
  const JPEG = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46]);

  it('gathers all panel sections', async () => {
    const farm = await registerFarm();
    const parcel = await createParcel(farm.token, 'South-12', square(25.3, 45.8));
    const year = new Date().getFullYear();
    await request(app).patch(`${api}/parcels/${parcel.id}`).set(bearer(farm.token)).send({ irrigatedAreaHa: parcel.areaHa / 2 }).expect(200);
    await request(app)
      .post(`${api}/parcels/${parcel.id}/seasons`)
      .set(bearer(farm.token))
      .send({ crop: 'CORN', year, sowingDate: new Date(Date.now() - 60 * 86_400_000), expectedYieldTHa: 8, expectedPricePerT: 900 })
      .expect(201);
    await request(app)
      .post(`${api}/costs`)
      .set(bearer(farm.token))
      .send({ parcelId: parcel.id, year, category: 'LABOR', amount: 2000 })
      .expect(201);
    await request(app)
      .post(`${api}/tasks`)
      .set(bearer(farm.token))
      .send({ type: 'LEAF_ANALYSIS', dueDate: new Date(), parcelId: parcel.id })
      .expect(201);

    const res = await request(app).get(`${api}/parcels/${parcel.id}/intelligence`).set(bearer(farm.token)).expect(200);
    const data = res.body.data;
    expect(data.header.name).toBe('South-12');
    expect(data.crop).toMatchObject({ code: 'CORN', status: 'GROWING', vegetationDay: 61, totalDays: 193 });
    expect(data.irrigation.coveragePct).toBe(50);
    expect(data.rotation.mode).toBe('CURRENT');
    expect(data.tasks.summary.todo).toBe(5); // The manual task and the 4 remaining steps of the crop plan.
    expect(data.economics.laborCost).toBe(2000);
    expect(data.economics.roiPct).toBeGreaterThan(0);
    expect(data.weather.alerts[0].message).toBe('Wind 22 km/h. Do not spray.');
    expect(data.weather.next14).toHaveLength(14);
    expect(data.nextAction.confidencePct).toBeGreaterThan(50);
  });

  it('hides economics from operators and other farms', async () => {
    const farm = await registerFarm();
    const operator = await addMember(farm.token, 'OPERATOR');
    const other = await registerFarm();
    const parcel = await createParcel(farm.token, 'Private', square(25.4, 45.9));
    const res = await request(app).get(`${api}/parcels/${parcel.id}/intelligence`).set(bearer(operator.token)).expect(200);
    expect(res.body.data.economics).toBeNull();
    await request(app).get(`${api}/parcels/${parcel.id}/economics`).set(bearer(operator.token)).expect(403);
    await request(app).get(`${api}/parcels/${parcel.id}/intelligence`).set(bearer(other.token)).expect(404);
  });

  it('saves valid photos and rejects other files', async () => {
    const farm = await registerFarm();
    const other = await registerFarm();
    const parcel = await createParcel(farm.token, 'Photo', square(25.5, 45.1));
    const url = `${api}/parcels/${parcel.id}/photos`;
    await request(app).post(url).set(bearer(farm.token)).attach('file', Buffer.from('<script>'), 'x.jpg').expect(400);
    const created = await request(app)
      .post(url)
      .set(bearer(farm.token))
      .field('notes', 'Spots on leaves')
      .attach('file', JPEG, 'frunza.jpg')
      .expect(201);
    expect(created.body.data.notes).toBe('Spots on leaves');
    const file = await request(app).get(`${url}/${created.body.data.id}/file`).set(bearer(farm.token)).expect(200);
    expect(file.headers['content-type']).toContain('image/jpeg');
    await request(app).get(`${url}/${created.body.data.id}/file`).set(bearer(other.token)).expect(404);
    await request(app).delete(`${url}/${created.body.data.id}`).set(bearer(farm.token)).expect(204);
  });

  it('publishes the machine position only for permitted operations', async () => {
    const farm = await registerFarm();
    const first = await addMember(farm.token, 'OPERATOR');
    const second = await addMember(farm.token, 'OPERATOR');
    const parcel = await createParcel(farm.token, 'GPS', square(25.6, 45.2));
    const work = await request(app)
      .post(`${api}/work`)
      .set(bearer(first.token))
      .send({ parcelId: parcel.id, type: 'FERTILIZING', workingWidthM: 24, speedKmh: 14, efficiencyPct: 80 })
      .expect(201);
    const url = `${api}/work/${work.body.data.id}/location`;
    await request(app).post(url).set(bearer(second.token)).send({ lat: 45.2, lng: 25.6 }).expect(403);
    await request(app).post(url).set(bearer(first.token)).send({ lat: 45.2, lng: 25.6, speedKmh: 13 }).expect(204);
    const latest = await request(app).get(url).set(bearer(farm.token)).expect(200);
    expect(latest.body.data).toMatchObject({ lat: 45.2, lng: 25.6, speedKmh: 13 });
  });

  it('compares vegetation at 7 and 30 days', async () => {
    const farm = await registerFarm();
    const parcel = await createParcel(farm.token, 'NDVI', square(25.7, 45.3));
    const day = (daysAgo: number) => new Date(new Date(Date.now() - daysAgo * 86_400_000).toISOString().slice(0, 10));
    await prisma.satelliteObservation.createMany({
      data: [
        { parcelId: parcel.id, date: day(1), ndvi: 0.72, ndre: 0.35, healthIndex: 80, healthClass: 'GOOD' },
        { parcelId: parcel.id, date: day(8), ndvi: 0.61, ndre: 0.3, healthIndex: 68, healthClass: 'GOOD' },
        { parcelId: parcel.id, date: day(29), ndvi: 0.33, ndre: 0.15, healthIndex: 30, healthClass: 'PROBLEM' },
      ],
    });
    const res = await request(app).get(`${api}/parcels/${parcel.id}/ndvi`).set(bearer(farm.token)).expect(200);
    expect(res.body.data.latest.ndvi).toBe(0.72);
    expect(res.body.data.sevenDaysAgo.ndvi).toBe(0.61);
    expect(res.body.data.thirtyDaysAgo.health.color).toBe('red');
  });

  it('generates the parcel PDF report', async () => {
    const farm = await registerFarm();
    const parcel = await createParcel(farm.token, 'Report', square(25.8, 45.4));
    const pdf = await request(app)
      .get(`${api}/parcels/${parcel.id}/report`)
      .set(bearer(farm.token))
      .buffer(true)
      .parse((res, done) => {
        const chunks: Buffer[] = [];
        res.on('data', (chunk: Buffer) => chunks.push(chunk));
        res.on('end', () => done(null, Buffer.concat(chunks)));
      })
      .expect(200);
    expect((pdf.body as Buffer).subarray(0, 4).toString()).toBe('%PDF');
  });
});

describe('map and technology plan', () => {
  const analyze = (token: string, body: object) =>
    request(app).post(`${api}/parcels/wizard/analyze`).set(bearer(token)).send(body);
  const complete = (token: string, body: object) =>
    request(app).post(`${api}/parcels/wizard/complete`).set(bearer(token)).send(body);

  it('analyses the drawn parcel without saving anything', async () => {
    const farm = await registerFarm();
    const res = await analyze(farm.token, { geometry: square(26.1, 45.1), name: 'South-12', lastCrop: 'WHEAT', plannedCrop: 'CORN' }).expect(200);
    expect(res.body.data.steps.length).toBe(11);
    expect(res.body.data.report.parcelName).toBe('South-12');
    expect(res.body.data.report.lastCropName).toBe('Wheat');
    expect(res.body.data.recommendations[0].reason).toBeTruthy();
    const parcels = await request(app).get(`${api}/parcels`).set(bearer(farm.token)).expect(200);
    expect(parcels.body.data).toHaveLength(0);
  });

  it('creates the parcel, crops, plan and tasks at the end', async () => {
    const farm = await registerFarm();
    const res = await complete(farm.token, {
      draft: { name: 'South-12', geometry: square(26.2, 45.2) },
      lastCrop: 'WHEAT',
      plannedCrop: 'CORN',
    }).expect(201);
    const parcelId = res.body.data.parcel.id;

    const seasons = await request(app).get(`${api}/parcels/${parcelId}/seasons`).set(bearer(farm.token)).expect(200);
    expect(seasons.body.data.map((season: { crop: string }) => season.crop).sort()).toEqual(['CORN', 'WHEAT']);

    const tasks = await request(app).get(`${api}/tasks`).query({ parcelId }).set(bearer(farm.token)).expect(200);
    expect(tasks.body.data).toHaveLength(11);

    const panel = await request(app).get(`${api}/parcels/${parcelId}/intelligence`).set(bearer(farm.token)).expect(200);
    expect(panel.body.data.plan.data.cropName).toBe('Corn');
    expect(panel.body.data.nextAction.code).toBe('PLAN_STEP');
    expect(panel.body.data.planHistory).toHaveLength(1);
  });

  it('accepts another crop without inventing history', async () => {
    const farm = await registerFarm();
    const res = await complete(farm.token, {
      draft: { name: 'West', geometry: square(26.3, 45.3) },
      lastCrop: 'OTHER',
      lastCropName: 'Mustard',
      plannedCrop: 'SOY',
    }).expect(201);
    expect(res.body.data.plan.report.lastCropName).toBe('Mustard');
    const seasons = await request(app).get(`${api}/parcels/${res.body.data.parcel.id}/seasons`).set(bearer(farm.token)).expect(200);
    expect(seasons.body.data).toHaveLength(1);
  });

  it('does not allow the agronomist to create new parcels', async () => {
    const farm = await registerFarm();
    const agronomist = await addMember(farm.token, 'AGRONOMIST');
    await complete(agronomist.token, { draft: { name: 'X', geometry: square(26.4, 45.4) }, lastCrop: 'WHEAT', plannedCrop: 'SOY' }).expect(403);
  });

  it('regenerates the plan when the crop changes and keeps work in progress', async () => {
    const farm = await registerFarm();
    const created = await complete(farm.token, {
      draft: { name: 'East', geometry: square(26.5, 45.5) },
      lastCrop: 'WHEAT',
      plannedCrop: 'CORN',
    }).expect(201);
    const parcelId = created.body.data.parcel.id;
    const list = async () => (await request(app).get(`${api}/tasks`).query({ parcelId }).set(bearer(farm.token)).expect(200)).body.data;

    const started = (await list())[0];
    await request(app).post(`${api}/tasks/${started.id}/status`).set(bearer(farm.token)).send({ status: 'IN_PROGRESS' }).expect(200);

    const seasons = await request(app).get(`${api}/parcels/${parcelId}/seasons`).set(bearer(farm.token)).expect(200);
    const planned = seasons.body.data.find((season: { crop: string }) => season.crop === 'CORN');
    await request(app).patch(`${api}/seasons/${planned.id}`).set(bearer(farm.token)).send({ crop: 'SOY' }).expect(200);

    const tasks = await list();
    expect(tasks.some((task: { id: string }) => task.id === started.id)).toBe(true);
    const open = tasks.filter((task: { status: string }) => task.status === 'TODO');
    expect(open.length).toBeGreaterThan(0);
    expect(open.every((task: { title: string }) => task.title.endsWith('Soybean'))).toBe(true);

    const plan = await request(app).get(`${api}/parcels/${parcelId}/plan`).set(bearer(farm.token)).expect(200);
    expect(plan.body.data.active.crop).toBe('SOY');
    expect(plan.body.data.history).toHaveLength(2);
    expect(plan.body.data.history[1].active).toBe(false);
  });

  it('hides the plan’s financial data from operators', async () => {
    const farm = await registerFarm();
    const created = await complete(farm.token, {
      draft: { name: 'Fin', geometry: square(26.6, 45.6) },
      lastCrop: 'WHEAT',
      plannedCrop: 'SOY',
    }).expect(201);
    const operator = await addMember(farm.token, 'OPERATOR');
    const plan = await request(app).get(`${api}/parcels/${created.body.data.parcel.id}/plan`).set(bearer(operator.token)).expect(200);
    expect(plan.body.data.active.data.report.expectedProfitEurHa).toBeUndefined();
    expect(plan.body.data.active.data.report.expectedYieldTHa).toBeGreaterThan(0);
  });

  it('shows live work, irrigations and tasks on the map', async () => {
    const farm = await registerFarm();
    const parcel = await createParcel(farm.token, 'Map', square(26.7, 45.7));
    await request(app).post(`${api}/work`).set(bearer(farm.token)).send({ parcelId: parcel.id, type: 'PLOWING', workingWidthM: 4, speedKmh: 8, efficiencyPct: 80 }).expect(201);
    await request(app).post(`${api}/irrigation`).set(bearer(farm.token)).send({ parcelId: parcel.id, systemType: 'PIVOT', routeLengthM: 400, speedMPerH: 40 }).expect(201);
    await request(app).post(`${api}/tasks`).set(bearer(farm.token)).send({ type: 'HARVEST', dueDate: new Date().toISOString(), parcelId: parcel.id }).expect(201);

    const res = await request(app).get(`${api}/map/overview`).set(bearer(farm.token)).expect(200);
    const item = res.body.data.find((row: { parcelId: string }) => row.parcelId === parcel.id);
    expect(item.work).toHaveLength(1);
    expect(item.irrigation[0].label).toBe('Pivot #1');
    expect(item.openTasks).toBe(1);
  });
});

describe('documentation', () => {
  it('publishes the OpenAPI specification', async () => {
    const res = await request(app).get('/api/docs.json').expect(200);
    expect(res.body.openapi).toBe('3.1.0');
    expect(res.body.paths['/parcels/{id}/soil']).toBeDefined();
    expect(res.body.paths['/work']).toBeDefined();
    expect(Object.keys(res.body.paths).length).toBeGreaterThan(50);
  });
});

