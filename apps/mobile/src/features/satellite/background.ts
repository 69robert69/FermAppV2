import { liveItems } from '../../firebase/liveStore';
import { queueWrites } from '../../firebase/outbox';
import { notificationWrites, session } from '../../firebase/services';
import type { FarmDoc, ParcelDoc, UserProfile } from '../../firebase/types';
import { monitorField, ringOf } from './analysis';
import { useSatellitePrefs } from './prefs';

/**
 * With auto refresh on, open phones analyse each field's newest clear image
 * (at most every 12 hours, thanks to the cache) and, with field alerts on,
 * notify managers and agronomists of problems.
 */
export async function refreshSatelliteInBackground() {
  const prefs = useSatellitePrefs.getState();
  if (!useSatellitePrefs.persist.hasHydrated() || !prefs.enabled || !prefs.autoRefresh) return;
  const profile = session();
  const parcels = (await liveItems<ParcelDoc>('parcels')).filter((parcel) => !parcel.archivedAt);
  const recipients = prefs.autoAlerts && profile.role !== 'OPERATOR' ? (await liveItems<UserProfile & FarmDoc>('users')).filter((member) => member.role !== 'OPERATOR') : [];
  for (const parcel of parcels) {
    const ring = ringOf(JSON.parse(parcel.geometry) as { coordinates: number[][][] });
    const result = await monitorField({ id: parcel.id, name: parcel.name, areaHa: parcel.area, ring }).catch(() => null);
    if (!result || !recipients.length) continue;
    for (const finding of result.findings) {
      if (finding.severity === 'INFO') continue;
      const writes = notificationWrites(recipients, `sat_${parcel.id}_${finding.kind}_${finding.zoneId ?? 'field'}_${result.current.date}`, {
        kind: 'RECOMMENDATION',
        title: 'Satellite alert',
        body: finding.message,
        parcelId: parcel.id,
        severity: finding.severity === 'CRITICAL' ? 'SEVERE' : 'WARNING',
      });
      for (const write of writes) await queueWrites([write]);
    }
  }
}
