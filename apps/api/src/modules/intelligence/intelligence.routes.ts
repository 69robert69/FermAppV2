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
  { summary: 'All data for the Parcel Intelligence panel', roles: ROLES.all, params: idParams },
  async ({ auth, params }) => buildIntelligence(auth, params.id),
);

intelligenceRoutes.get(
  '/weather',
  { summary: 'Today’s weather, 7- and 14-day forecast and spraying alerts', roles: ROLES.all, params: idParams },
  async ({ auth, params }) => parcelWeather(await requireParcel(auth.farmId, params.id)),
);

intelligenceRoutes.get(
  '/rotation-score',
  { summary: 'Rotation score and crop history', roles: ROLES.all, params: idParams },
  async ({ auth, params }) => {
    await requireParcel(auth.farmId, params.id);
    return rotationScore(params.id);
  },
);

intelligenceRoutes.get(
  '/economics',
  { summary: 'Costs, estimated revenue, profit, margin and ROI', roles: ROLES.planners, params: idParams, query: yearQuery },
  async ({ auth, params, query }) => economicsFor(await requireParcel(auth.farmId, params.id), query.year),
);

intelligenceRoutes.post(
  '/incomes',
  {
    summary: 'Record realized revenue',
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
  { summary: 'NDVI, NDRE and vegetation status: now, 7 and 30 days ago', roles: ROLES.all, params: idParams },
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
  { summary: 'Parcel tasks and status summary', roles: ROLES.all, params: idParams },
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
  { summary: 'Field observations', roles: ROLES.all, params: idParams },
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
    summary: 'Add a field observation',
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
  { summary: 'Parcel photo gallery', roles: ROLES.all, params: idParams },
  async ({ auth, params }) => {
    await requireParcel(auth.farmId, params.id);
    return prisma.photo.findMany({ where: { parcelId: params.id }, orderBy: { createdAt: 'desc' }, take: 200 });
  },
);

intelligenceRoutes.post(
  '/photos',
  {
    summary: 'Upload a photo with notes',
    roles: ROLES.all,
    file: true,
    params: idParams,
    body: z.object({ notes: z.string().trim().max(1000).optional() }),
  },
  async ({ auth, params, body, file }) => {
    await requireParcel(auth.farmId, params.id);
    if (!file) throw badRequest('Attach the photo in the "file" field.');
    if (file.size > MAX_PHOTO_BYTES) throw badRequest('The photo exceeds the 10 MB limit.');
    const type = detectImageType(file.buffer);
    if (!type) throw badRequest('The file must be a JPEG, PNG, WEBP or HEIC image.');
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
  if (!photo) throw notFound('Photo');
  return photo;
}

intelligenceRoutes.get(
  '/photos/:photoId/file',
  { summary: 'Photo content', roles: ROLES.all, produces: 'image/jpeg', params: photoParams },
  async ({ auth, params }) => {
    const photo = await requirePhoto(auth.farmId, params.id, params.photoId);
    const buffer = await loadFile(photo.storageKey).catch(() => {
      throw notFound('Photo file');
    });
    return new FileResult(buffer, photo.contentType, `photo-${photo.id}`, true);
  },
);

intelligenceRoutes.delete(
  '/photos/:photoId',
  { summary: 'Delete a photo', roles: ROLES.all, params: photoParams },
  async ({ auth, params }) => {
    const photo = await requirePhoto(auth.farmId, params.id, params.photoId);
    const mayDelete = auth.role === 'ADMIN' || auth.role === 'FARM_MANAGER' || photo.authorId === auth.userId;
    if (!mayDelete) throw forbidden('You can only delete your own photos.');
    await prisma.photo.delete({ where: { id: photo.id } });
    await removeFile(photo.storageKey);
    emitToFarm(auth.farmId, 'parcel:updated', { parcelId: params.id });
  },
);

intelligenceRoutes.get(
  '/report',
  { summary: 'PDF report for the parcel', roles: ROLES.all, produces: 'application/pdf', params: idParams },
  async ({ auth, params }) => {
    const data = await buildIntelligence(auth, params.id);
    const farm = await prisma.farm.findUniqueOrThrow({ where: { id: auth.farmId } });
    const rows: [string, string, string | number | null | undefined][] = [
      ['Parcel', 'Code', data.header.code],
      ['Parcel', 'Area (ha)', data.header.areaHa],
      ['Parcel', 'Locality', [data.header.locality, data.header.county].filter(Boolean).join(', ')],
      ['Crop', 'Current crop', data.crop?.name ?? 'Not recorded'],
      ['Crop', 'Progress (%)', data.crop?.percent],
      ['Rotation', 'Score', data.rotation.score],
      ['Rotation', 'Verdict', data.rotation.label.replace(/^\S+\s/, '')],
      ['Rotation', 'Explanation', data.rotation.explanation],
      ...data.rotation.history.map((item): [string, string, string] => ['History', String(item.year), CROPS[item.crop].name]),
      ['Recommendation', 'Next action', data.nextAction.action],
      ['Recommendation', 'Reason', data.nextAction.reason],
      ['Soil', 'Classification', 'label' in data.soil ? data.soil.label : null],
      ['Tasks', 'Open', data.tasks.summary.todo + data.tasks.summary.inProgress],
      ...(data.economics
        ? ([
            ['Economics', 'Total cost (EUR)', data.economics.totalCost],
            ['Economics', 'Cost/ha (EUR)', data.economics.costPerHa],
            ['Economics', 'Estimated profit (EUR)', data.economics.expectedProfit],
          ] as [string, string, number | null][])
        : []),
    ];
    const report: Report = {
      title: `Parcel ${data.header.name}`,
      period: `Generated on ${data.generatedAt.slice(0, 10)}`,
      columns: [
        { key: 'section', label: 'Section', width: 1 },
        { key: 'indicator', label: 'Indicator', width: 1.4 },
        { key: 'value', label: 'Value', width: 3.6 },
      ],
      rows: rows.map(([section, indicator, value]) => ({ section, indicator, value: value ?? 'Data unavailable' })),
    };
    return new FileResult(await reportToPdf(report, farm.name), 'application/pdf', `parcel-${data.header.code}.pdf`);
  },
);
