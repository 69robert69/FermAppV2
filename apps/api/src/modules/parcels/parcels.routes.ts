import { validatePolygon, type PolygonGeometry } from '@fermapp/domain';
import type { Prisma } from '@prisma/client';
import { z } from 'zod';
import { requireParcel } from '../../lib/access';
import { badRequest } from '../../lib/errors';
import { logger } from '../../lib/logger';
import { prisma } from '../../lib/prisma';
import { ApiRouter, FileResult, idParams, ROLES } from '../../lib/route';
import { refreshSoilProfile } from '../soil/soil.service';
import { exportParcels, IMPORT_FORMATS, metricsFor, parseGeoFile, type ExportParcel } from './geo-io';

export const parcelRoutes = new ApiRouter('/parcels', 'Parcele');

const position = z.tuple([z.number().min(-180).max(180), z.number().min(-90).max(90)]);
export const polygonSchema = z
  .object({
    type: z.literal('Polygon'),
    coordinates: z.array(z.array(position).min(3).max(5000)).min(1).max(50),
  })
  .superRefine((geometry, ctx) => {
    const problem = validatePolygon(geometry as PolygonGeometry);
    if (problem) ctx.addIssue({ code: 'custom', message: problem, path: ['coordinates'] });
  });

const text = (max: number) => z.string().trim().max(max);

export const parcelFields = {
  name: text(120).min(1),
  code: text(40).min(1).optional(),
  geometry: polygonSchema,
  locality: text(120).optional(),
  county: text(60).optional(),
  notes: text(2000).optional(),
  irrigatedAreaHa: z.number().min(0).max(100_000).nullable().optional(),
};

async function nextCode(farmId: string, offset = 0) {
  const count = await prisma.parcel.count({ where: { farmId } });
  return `P-${String(count + 1 + offset).padStart(4, '0')}`;
}

const geometryData = (geometry: PolygonGeometry) => {
  const metrics = metricsFor(geometry);
  return {
    geometry: geometry as unknown as Prisma.InputJsonValue,
    areaHa: metrics.areaHa,
    perimeterM: metrics.perimeterM,
    centerLat: metrics.center.lat,
    centerLng: metrics.center.lng,
  };
};

export const loadSoilInBackground = (parcel: { id: string; centerLat: number; centerLng: number }) => {
  void refreshSoilProfile(parcel.id, parcel.centerLat, parcel.centerLng).catch((error) =>
    logger.error('Profil de sol eșuat', error),
  );
};

/** Datele necesare pentru crearea unei parcele. */
export async function createParcelData(farmId: string, body: z.output<z.ZodObject<typeof parcelFields>>) {
  return {
    farmId,
    name: body.name,
    code: body.code ?? (await nextCode(farmId)),
    locality: body.locality,
    county: body.county,
    notes: body.notes,
    irrigatedAreaHa: body.irrigatedAreaHa,
    ...geometryData(body.geometry as PolygonGeometry),
  };
}

parcelRoutes.get(
  '/',
  {
    summary: 'Listează parcelele fermei',
    roles: ROLES.all,
    query: z.object({
      archived: z.enum(['true', 'false']).default('false'),
      search: z.string().trim().max(120).optional(),
    }),
  },
  async ({ auth, query }) => {
    const year = new Date().getFullYear();
    return prisma.parcel.findMany({
      where: {
        farmId: auth.farmId,
        archivedAt: query.archived === 'true' ? { not: null } : null,
        ...(query.search
          ? {
              OR: [
                { name: { contains: query.search, mode: 'insensitive' } },
                { code: { contains: query.search, mode: 'insensitive' } },
              ],
            }
          : {}),
      },
      include: {
        seasons: { where: { year }, take: 1, select: { crop: true, year: true } },
        soilProfile: { select: { status: true, label: true } },
        satellite: { orderBy: { date: 'desc' }, take: 1, select: { date: true, ndvi: true, healthIndex: true, healthClass: true } },
      },
      orderBy: { name: 'asc' },
    });
  },
);

parcelRoutes.post(
  '/',
  { summary: 'Creează o parcelă și pornește analiza solului', roles: ROLES.managers, body: z.object(parcelFields) },
  async ({ auth, body }) => {
    const parcel = await prisma.parcel.create({ data: await createParcelData(auth.farmId, body) });
    loadSoilInBackground(parcel);
    return parcel;
  },
);

parcelRoutes.post(
  '/import',
  {
    summary: 'Importă parcele din GeoJSON, KML, KMZ, SHP sau APIA LPIS',
    roles: ROLES.managers,
    file: true,
    query: z.object({
      format: z.enum(IMPORT_FORMATS),
      dryRun: z.enum(['true', 'false']).default('false'),
    }),
  },
  async ({ auth, query, file }) => {
    if (!file) throw badRequest('Atașează fișierul în câmpul „file”.');
    const { parcels, problems } = await parseGeoFile(query.format, file.buffer);
    const preview = parcels.map((parcel) => ({
      name: parcel.name,
      code: parcel.code,
      areaHa: metricsFor(parcel.geometry).areaHa,
    }));
    if (query.dryRun === 'true') return { dryRun: true, parcels: preview, problems, created: 0 };

    const existing = await prisma.parcel.findMany({ where: { farmId: auth.farmId }, select: { code: true } });
    const usedCodes = new Set(existing.map((parcel) => parcel.code));
    let sequence = existing.length;
    const assignCode = (preferred?: string) => {
      if (preferred && !usedCodes.has(preferred)) {
        usedCodes.add(preferred);
        return preferred;
      }
      let code: string;
      do {
        sequence += 1;
        code = `P-${String(sequence).padStart(4, '0')}`;
      } while (usedCodes.has(code));
      usedCodes.add(code);
      return code;
    };

    const created = await prisma.parcel.createManyAndReturn({
      data: parcels.map((parcel) => ({
        farmId: auth.farmId,
        name: parcel.name.slice(0, 120),
        code: assignCode(parcel.code),
        locality: parcel.locality,
        county: parcel.county,
        notes: parcel.notes,
        ...geometryData(parcel.geometry),
      })),
    });
    created.forEach(loadSoilInBackground);
    return { dryRun: false, parcels: preview, problems, created: created.length };
  },
);

parcelRoutes.get(
  '/export',
  {
    summary: 'Exportă parcelele active',
    roles: ROLES.all,
    produces: 'application/octet-stream',
    query: z.object({ format: z.enum(IMPORT_FORMATS) }),
  },
  async ({ auth, query }) => {
    const parcels = await prisma.parcel.findMany({
      where: { farmId: auth.farmId, archivedAt: null },
      orderBy: { name: 'asc' },
    });
    const file = await exportParcels(query.format, parcels as unknown as ExportParcel[]);
    return new FileResult(file.buffer, file.contentType, `parcele.${file.extension}`);
  },
);

parcelRoutes.get(
  '/:id',
  { summary: 'Detaliile unei parcele', roles: ROLES.all, params: idParams },
  async ({ auth, params }) => {
    await requireParcel(auth.farmId, params.id);
    return prisma.parcel.findUniqueOrThrow({
      where: { id: params.id },
      include: {
        seasons: { orderBy: { year: 'desc' }, take: 10 },
        soilProfile: true,
        satellite: { orderBy: { date: 'desc' }, take: 1 },
      },
    });
  },
);

parcelRoutes.patch(
  '/:id',
  {
    summary: 'Modifică datele sau conturul parcelei',
    roles: ROLES.managers,
    params: idParams,
    body: z.object({
      name: parcelFields.name.optional(),
      code: parcelFields.code,
      geometry: polygonSchema.optional(),
      locality: text(120).nullable().optional(),
      county: text(60).nullable().optional(),
      notes: text(2000).nullable().optional(),
      irrigatedAreaHa: parcelFields.irrigatedAreaHa,
    }),
  },
  async ({ auth, params, body }) => {
    await requireParcel(auth.farmId, params.id);
    const { geometry, ...fields } = body;
    const parcel = await prisma.parcel.update({
      where: { id: params.id },
      data: { ...fields, ...(geometry ? geometryData(geometry as PolygonGeometry) : {}) },
    });
    if (geometry) loadSoilInBackground(parcel);
    return parcel;
  },
);

parcelRoutes.post(
  '/:id/archive',
  { summary: 'Arhivează parcela', roles: ROLES.managers, status: 200, params: idParams },
  async ({ auth, params }) => {
    await requireParcel(auth.farmId, params.id);
    return prisma.parcel.update({ where: { id: params.id }, data: { archivedAt: new Date() } });
  },
);

parcelRoutes.post(
  '/:id/restore',
  { summary: 'Restaurează parcela din arhivă', roles: ROLES.managers, status: 200, params: idParams },
  async ({ auth, params }) => {
    await requireParcel(auth.farmId, params.id);
    return prisma.parcel.update({ where: { id: params.id }, data: { archivedAt: null } });
  },
);
