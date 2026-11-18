import { bestWindowMessage, findWorkWindows, type FieldOperation } from '@fermapp/domain';
import { z } from 'zod';
import { getForecast, getHistory, localHour, upcomingHours } from '../../integrations/openMeteo';
import { requireParcel } from '../../lib/access';
import { prisma } from '../../lib/prisma';
import { ApiRouter, idParams, ROLES } from '../../lib/route';
import { checkParcelAlerts } from './weather.service';

export const weatherRoutes = new ApiRouter('', 'Meteo');

const OPERATIONS: FieldOperation[] = ['SPRAYING', 'FERTILIZING', 'SOWING', 'HARVESTING'];

weatherRoutes.get(
  '/parcels/:id/weather/forecast',
  {
    summary: 'Prognoză pentru 24 de ore, 7 sau 14 zile',
    roles: ROLES.all,
    params: idParams,
    query: z.object({ range: z.enum(['24h', '7d', '14d']).default('7d') }),
  },
  async ({ auth, params, query }) => {
    const parcel = await requireParcel(auth.farmId, params.id);
    const forecast = await getForecast(parcel.centerLat, parcel.centerLng);
    const hours = upcomingHours(forecast);
    const today = localHour(forecast).slice(0, 10);
    const days = forecast.days.filter((day) => day.date >= today);
    return {
      timezone: forecast.timezone,
      range: query.range,
      current: hours[0] ?? null,
      hours: query.range === '24h' ? hours.slice(0, 24) : [],
      days: query.range === '24h' ? [] : days.slice(0, query.range === '7d' ? 7 : 14),
    };
  },
);

const isoDate = (date: Date) => date.toISOString().slice(0, 10);

weatherRoutes.get(
  '/parcels/:id/weather/history',
  {
    summary: 'Istoric meteo pentru 30 de zile sau 12 luni',
    roles: ROLES.all,
    params: idParams,
    query: z.object({ range: z.enum(['30d', '12m']).default('30d') }),
  },
  async ({ auth, params, query }) => {
    const parcel = await requireParcel(auth.farmId, params.id);
    const end = new Date(Date.now() - 2 * 86_400_000);
    const start = new Date(end);
    if (query.range === '30d') start.setUTCDate(start.getUTCDate() - 29);
    else start.setUTCFullYear(start.getUTCFullYear() - 1);
    const days = await getHistory(parcel.centerLat, parcel.centerLng, isoDate(start), isoDate(end));
    return {
      range: query.range,
      totals: {
        precipitationMm: Math.round(days.reduce((sum, day) => sum + day.precipitationMm, 0) * 10) / 10,
        et0Mm: Math.round(days.reduce((sum, day) => sum + (day.et0Mm ?? 0), 0) * 10) / 10,
      },
      days,
    };
  },
);

weatherRoutes.get(
  '/parcels/:id/weather/windows',
  { summary: 'Ferestre optime pentru lucrări, pe următoarele 7 zile', roles: ROLES.all, params: idParams },
  async ({ auth, params }) => {
    const parcel = await requireParcel(auth.farmId, params.id);
    const forecast = await getForecast(parcel.centerLat, parcel.centerLng);
    const from = localHour(forecast);
    const horizon = upcomingHours(forecast)[168]?.time;
    const hours = forecast.hours.filter((hour) => !horizon || hour.time < horizon);
    return OPERATIONS.map((operation) => {
      const windows = findWorkWindows(operation, hours, 2, from);
      return { operation, message: bestWindowMessage(operation, windows), windows: windows.slice(0, 5) };
    });
  },
);

weatherRoutes.get(
  '/alerts',
  {
    summary: 'Alertele meteo ale fermei',
    roles: ROLES.all,
    query: z.object({
      parcelId: z.uuid().optional(),
      limit: z.coerce.number().int().min(1).max(200).default(50),
    }),
  },
  async ({ auth, query }) =>
    prisma.weatherAlert.findMany({
      where: { farmId: auth.farmId, parcelId: query.parcelId },
      include: { parcel: { select: { id: true, name: true } } },
      orderBy: { createdAt: 'desc' },
      take: query.limit,
    }),
);

weatherRoutes.post(
  '/parcels/:id/alerts/check',
  { summary: 'Verifică acum alertele meteo pentru parcelă', roles: ROLES.all, status: 200, params: idParams },
  async ({ auth, params }) => {
    const parcel = await requireParcel(auth.farmId, params.id);
    return checkParcelAlerts(parcel);
  },
);
