import { classifySoil } from '@fermapp/domain';
import { fetchSoilGrids } from '../../integrations/soilGrids';
import { logger } from '../../lib/logger';
import { prisma } from '../../lib/prisma';

/** Queries SoilGrids and saves the profile; an error remains visible in the status. */
export async function refreshSoilProfile(parcelId: string, lat: number, lng: number) {
  await prisma.soilProfile.upsert({
    where: { parcelId },
    create: { parcelId, status: 'PENDING' },
    update: { status: 'PENDING', errorMessage: null },
  });

  try {
    const values = await fetchSoilGrids(lat, lng);
    if (!values) {
      return await prisma.soilProfile.update({
        where: { parcelId },
        data: { status: 'FAILED', errorMessage: 'SoilGrids has no data for this location.' },
      });
    }
    const soil = classifySoil(values);
    return await prisma.soilProfile.update({
      where: { parcelId },
      data: {
        status: 'READY',
        ph: values.ph,
        clayPct: values.clayPct,
        sandPct: values.sandPct,
        siltPct: soil.siltPct,
        organicCarbonGKg: values.organicCarbonGKg,
        organicMatterPct: soil.organicMatterPct,
        waterHoldingMmPerM: soil.waterHoldingMmPerM,
        texture: soil.texture,
        soilType: soil.soilType,
        label: soil.label,
        errorMessage: null,
        fetchedAt: new Date(),
      },
    });
  } catch (error) {
    logger.warn('Soil profile unavailable', { parcelId, reason: (error as Error).message });
    return prisma.soilProfile.update({
      where: { parcelId },
      data: { status: 'FAILED', errorMessage: 'SoilGrids is not responding right now. It will be retried automatically.' },
    });
  }
}
