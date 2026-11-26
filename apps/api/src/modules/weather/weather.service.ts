import { evaluateWeatherAlerts, type WeatherAlertDraft } from '@fermapp/domain';
import { Prisma, type Parcel } from '@prisma/client';
import { getForecast, upcomingHours } from '../../integrations/openMeteo';
import { getOwmHours, isOpenWeatherConfigured } from '../../integrations/openWeather';
import { logger } from '../../lib/logger';
import { prisma } from '../../lib/prisma';
import { emitToFarm } from '../../realtime/socket';
import { notify } from '../notifications/notifications.service';

type SourcedAlert = WeatherAlertDraft & { source: string };

/** Open-Meteo provides hail codes, and OpenWeatherMap takes priority for the other types. */
async function collectAlerts(parcel: Pick<Parcel, 'name' | 'centerLat' | 'centerLng'>): Promise<SourcedAlert[]> {
  const byType = new Map<string, SourcedAlert>();

  const forecast = await getForecast(parcel.centerLat, parcel.centerLng).catch((error) => {
    logger.warn('Open-Meteo unavailable for alerts', (error as Error).message);
    return null;
  });
  if (forecast) {
    for (const alert of evaluateWeatherAlerts(parcel.name, upcomingHours(forecast))) {
      byType.set(alert.type, { ...alert, source: 'open-meteo' });
    }
  }

  if (isOpenWeatherConfigured()) {
    const hours = await getOwmHours(parcel.centerLat, parcel.centerLng).catch((error) => {
      logger.warn('OpenWeatherMap unavailable for alerts', (error as Error).message);
      return null;
    });
    if (hours) {
      for (const alert of evaluateWeatherAlerts(parcel.name, hours)) {
        byType.set(alert.type, { ...alert, source: 'openweathermap' });
      }
    }
  }
  return [...byType.values()];
}

/** Saves new alerts; the same alert is not repeated on the same day for the same parcel. */
export async function checkParcelAlerts(parcel: Parcel) {
  const alerts = await collectAlerts(parcel);
  const created = [];
  for (const alert of alerts) {
    try {
      const row = await prisma.weatherAlert.create({
        data: {
          farmId: parcel.farmId,
          parcelId: parcel.id,
          type: alert.type,
          severity: alert.severity,
          title: alert.title,
          message: alert.message,
          startsAt: alert.startsAt,
          dedupeKey: `${alert.type}:${alert.startsAt.slice(0, 10)}`,
          source: alert.source,
        },
      });
      created.push(row);
      emitToFarm(parcel.farmId, 'alert:new', row);
      await notify({
        farmId: parcel.farmId,
        title: alert.title,
        body: alert.message,
        data: { type: 'alert', id: row.id, parcelId: parcel.id },
      });
    } catch (error) {
      if (error instanceof Prisma.PrismaClientKnownRequestError && error.code === 'P2002') continue;
      throw error;
    }
  }
  return created;
}
