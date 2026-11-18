import { requireParcel } from '../../lib/access';
import { prisma } from '../../lib/prisma';
import { ApiRouter, idParams, ROLES } from '../../lib/route';
import { soilSection } from '../intelligence/intelligence.service';
import { refreshSoilProfile } from './soil.service';

export const soilRoutes = new ApiRouter('/parcels/:id/soil', 'Sol');

soilRoutes.get(
  '/',
  { summary: 'Profilul de sol al parcelei', roles: ROLES.all, params: idParams },
  async ({ auth, params }) => {
    await requireParcel(auth.farmId, params.id);
    const profile = await prisma.soilProfile.findUnique({ where: { parcelId: params.id } });
    return { parcelId: params.id, ...soilSection(profile) };
  },
);

soilRoutes.post(
  '/refresh',
  { summary: 'Reinterogează SoilGrids', roles: ROLES.planners, status: 200, params: idParams },
  async ({ auth, params }) => {
    const parcel = await requireParcel(auth.farmId, params.id);
    return refreshSoilProfile(parcel.id, parcel.centerLat, parcel.centerLng);
  },
);
