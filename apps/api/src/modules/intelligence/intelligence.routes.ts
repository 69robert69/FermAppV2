import { CROPS } from '@fermapp/domain';
import { z } from 'zod';
import { requireParcel } from '../../lib/access';
import { badRequest, forbidden, notFound } from '../../lib/errors';
import { prisma } from '../../lib/prisma';
import { ApiRouter, FileResult, idParams, ROLES } from '../../lib/route';
import { detectImageType, loadFile, removeFile, saveFile } from '../../lib/storage';
import { emitToFarm } from '../../realtime/socket';
import { reportToPdf, type Report } from '../reports/reports.service';
import {
  buildIntelligence,
  economicsFor,
  ndviComparison,
  parcelWeather,
  rotationScore,
} from './intelligence.service';

export const intelligenceRoutes = new ApiRouter('/parcels/:id', 'Parcel Intelligence');

const MAX_PHOTO_BYTES = 10 * 1024 * 1024;
const photoParams = z.object({ id: z.uuid(), photoId: z.uuid() });
const yearQuery = z.object({ year: z.coerce.number().int().min(2000).max(2100).optional() });

intelligenceRoutes.get(
  '/intelligence',
  { summary: 'Toate datele panoului Parcel Intelligence', roles: ROLES.all, params: idParams },
  async ({ auth, params }) => buildIntelligence(auth, params.id),
);

intelligenceRoutes.get(
  '/weather',
  { summary: 'Vremea de azi, prognoza pe 7 și 14 zile și alertele de stropire', roles: ROLES.all, params: idParams },
  async ({ auth, params }) => parcelWeather(await requireParcel(auth.farmId, params.id)),
);

intelligenceRoutes.get(
  '/rotation-score',
  { summary: 'Scorul de rotație și istoricul culturilor', roles: ROLES.all, params: idParams },
  async ({ auth, params }) => {
    await requireParcel(auth.farmId, params.id);
    return rotationScore(params.id);
  },
);

intelligenceRoutes.get(
  '/economics',
  { summary: 'Costuri, venit estimat, profit, marjă și ROI', roles: ROLES.planners, params: idParams, query: yearQuery },
  async ({ auth, params, query }) => economicsFor(await requireParcel(auth.farmId, params.id), query.year),
);

intelligenceRoutes.post(
  '/incomes',
  {
    summary: 'Înregistrează un venit realizat',
    roles: ROLES.managers,
    params: idParams,
    body: z.object({
      year: z.number().int().min(2000).max(2100),
      amount: z.number().positive().max(100_000_000),
      source: z.string().trim().max(120).optional(),
      date: z.coerce.date().optional(),
    }),
  },
  async ({ auth, params, body }) => {
    await requireParcel(auth.farmId, params.id);
    const income = await prisma.income.create({ data: { ...body, parcelId: params.id } });
    emitToFarm(auth.farmId, 'parcel:updated', { parcelId: params.id });
    return { ...income, amount: Number(income.amount) };
  },
);

intelligenceRoutes.get(
  '/ndvi',
  { summary: 'NDVI, NDRE și starea vegetației: acum, acum 7 și 30 de zile', roles: ROLES.all, params: idParams },
  async ({ auth, params }) => {
    await requireParcel(auth.farmId, params.id);
    const observations = await prisma.satelliteObservation.findMany({
      where: { parcelId: params.id },
      orderBy: { date: 'desc' },
      take: 40,
    });
    return ndviComparison(observations);
  },
);

intelligenceRoutes.get(
  '/tasks',
  { summary: 'Task-urile parcelei și sumarul pe status', roles: ROLES.all, params: idParams },
  async ({ auth, params }) => {
    await requireParcel(auth.farmId, params.id);
    const items = await prisma.task.findMany({
      where: { parcelId: params.id },
      include: { assignee: { select: { id: true, fullName: true } } },
      orderBy: [{ status: 'asc' }, { dueDate: 'asc' }],
    });
    const count = (status: string) => items.filter((task) => task.status === status).length;
    return { summary: { todo: count('TODO'), inProgress: count('IN_PROGRESS'), done: count('DONE') }, items };
  },
);

intelligenceRoutes.get(
  '/observations',
  { summary: 'Observațiile din teren', roles: ROLES.all, params: idParams },
  async ({ auth, params }) => {
    await requireParcel(auth.farmId, params.id);
    return prisma.observation.findMany({
      where: { parcelId: params.id },
      include: { author: { select: { id: true, fullName: true } } },
      orderBy: { createdAt: 'desc' },
      take: 100,
    });
  },
);

intelligenceRoutes.post(
  '/observations',
  {
    summary: 'Adaugă o observație din teren',
    roles: ROLES.all,
    params: idParams,
    body: z.object({
      text: z.string().trim().min(1).max(2000),
      lat: z.number().min(-90).max(90).optional(),
      lng: z.number().min(-180).max(180).optional(),
    }),
  },
  async ({ auth, params, body }) => {
    await requireParcel(auth.farmId, params.id);
    const observation = await prisma.observation.create({
      data: { ...body, parcelId: params.id, authorId: auth.userId },
      include: { author: { select: { id: true, fullName: true } } },
    });
    emitToFarm(auth.farmId, 'parcel:updated', { parcelId: params.id });
    return observation;
  },
);

intelligenceRoutes.get(
  '/photos',
  { summary: 'Galeria foto a parcelei', roles: ROLES.all, params: idParams },
  async ({ auth, params }) => {
    await requireParcel(auth.farmId, params.id);
    return prisma.photo.findMany({ where: { parcelId: params.id }, orderBy: { createdAt: 'desc' }, take: 200 });
  },
);

intelligenceRoutes.post(
  '/photos',
  {
    summary: 'Încarcă o fotografie cu note',
    roles: ROLES.all,
    file: true,
    params: idParams,
    body: z.object({ notes: z.string().trim().max(1000).optional() }),
  },
  async ({ auth, params, body, file }) => {
    await requireParcel(auth.farmId, params.id);
    if (!file) throw badRequest('Atașează fotografia în câmpul „file”.');
    if (file.size > MAX_PHOTO_BYTES) throw badRequest('Fotografia depășește limita de 10 MB.');
    const type = detectImageType(file.buffer);
    if (!type) throw badRequest('Fișierul trebuie să fie o imagine JPEG, PNG, WEBP sau HEIC.');
    const storageKey = await saveFile(`parcels/${params.id}`, type.extension, file.buffer);
    const photo = await prisma.photo.create({
      data: {
        parcelId: params.id,
        authorId: auth.userId,
        storageKey,
        contentType: type.contentType,
        sizeBytes: file.size,
        notes: body.notes || undefined,
      },
    });
    emitToFarm(auth.farmId, 'parcel:updated', { parcelId: params.id });
    return photo;
  },
);

async function requirePhoto(farmId: string, parcelId: string, photoId: string) {
  const photo = await prisma.photo.findFirst({ where: { id: photoId, parcelId, parcel: { farmId } } });
  if (!photo) throw notFound('Fotografia');
  return photo;
}

intelligenceRoutes.get(
  '/photos/:photoId/file',
  { summary: 'Conținutul fotografiei', roles: ROLES.all, produces: 'image/jpeg', params: photoParams },
  async ({ auth, params }) => {
    const photo = await requirePhoto(auth.farmId, params.id, params.photoId);
    const buffer = await loadFile(photo.storageKey).catch(() => {
      throw notFound('Fișierul fotografiei');
    });
    return new FileResult(buffer, photo.contentType, `foto-${photo.id}`, true);
  },
);

intelligenceRoutes.delete(
  '/photos/:photoId',
  { summary: 'Șterge o fotografie', roles: ROLES.all, params: photoParams },
  async ({ auth, params }) => {
    const photo = await requirePhoto(auth.farmId, params.id, params.photoId);
    const mayDelete = auth.role === 'ADMIN' || auth.role === 'FARM_MANAGER' || photo.authorId === auth.userId;
    if (!mayDelete) throw forbidden('Poți șterge doar fotografiile proprii.');
    await prisma.photo.delete({ where: { id: photo.id } });
    await removeFile(photo.storageKey);
    emitToFarm(auth.farmId, 'parcel:updated', { parcelId: params.id });
  },
);

intelligenceRoutes.get(
  '/report',
  { summary: 'Raport PDF pentru parcelă', roles: ROLES.all, produces: 'application/pdf', params: idParams },
  async ({ auth, params }) => {
    const data = await buildIntelligence(auth, params.id);
    const farm = await prisma.farm.findUniqueOrThrow({ where: { id: auth.farmId } });
    const rows: [string, string, string | number | null | undefined][] = [
      ['Parcelă', 'Cod', data.header.code],
      ['Parcelă', 'Suprafață (ha)', data.header.areaHa],
      ['Parcelă', 'Localitate', [data.header.locality, data.header.county].filter(Boolean).join(', ')],
      ['Cultură', 'Cultura curentă', data.crop?.name ?? 'Neînregistrată'],
      ['Cultură', 'Progres (%)', data.crop?.percent],
      ['Rotație', 'Scor', data.rotation.score],
      ['Rotație', 'Verdict', data.rotation.label.replace(/^\S+\s/, '')],
      ['Rotație', 'Explicație', data.rotation.explanation],
      ...data.rotation.history.map((item): [string, string, string] => ['Istoric', String(item.year), CROPS[item.crop].name]),
      ['Recomandare', 'Următoarea acțiune', data.nextAction.action],
      ['Recomandare', 'Motiv', data.nextAction.reason],
      ['Sol', 'Clasificare', 'label' in data.soil ? data.soil.label : null],
      ['Task-uri', 'Deschise', data.tasks.summary.todo + data.tasks.summary.inProgress],
      ...(data.economics
        ? ([
            ['Economie', 'Cost total (lei)', data.economics.totalCost],
            ['Economie', 'Cost/ha (lei)', data.economics.costPerHa],
            ['Economie', 'Profit estimat (lei)', data.economics.expectedProfit],
          ] as [string, string, number | null][])
        : []),
    ];
    const report: Report = {
      title: `Parcela ${data.header.name}`,
      period: `Generat la ${data.generatedAt.slice(0, 10)}`,
      columns: [
        { key: 'section', label: 'Secțiune', width: 1 },
        { key: 'indicator', label: 'Indicator', width: 1.4 },
        { key: 'value', label: 'Valoare', width: 3.6 },
      ],
      rows: rows.map(([section, indicator, value]) => ({ section, indicator, value: value ?? 'Date indisponibile' })),
    };
    return new FileResult(await reportToPdf(report, farm.name), 'application/pdf', `parcela-${data.header.code}.pdf`);
  },
);
