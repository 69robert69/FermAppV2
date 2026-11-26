import { notFound } from './errors';
import { prisma } from './prisma';

/** Every reference received from the client is checked against the farm in the token. */
export async function requireParcel(farmId: string, parcelId: string) {
  const parcel = await prisma.parcel.findFirst({ where: { id: parcelId, farmId } });
  if (!parcel) throw notFound('Parcel');
  return parcel;
}

export async function requireActiveParcel(farmId: string, parcelId: string) {
  const parcel = await prisma.parcel.findFirst({ where: { id: parcelId, farmId, archivedAt: null } });
  if (!parcel) throw notFound('Parcel');
  return parcel;
}

export async function requireMachine(farmId: string, machineId: string) {
  const machine = await prisma.machine.findFirst({ where: { id: machineId, farmId, archivedAt: null } });
  if (!machine) throw notFound('Machine');
  return machine;
}

export async function requireMember(farmId: string, userId: string) {
  const member = await prisma.farmMembership.findUnique({
    where: { farmId_userId: { farmId, userId } },
    include: { user: { select: { id: true, fullName: true, email: true } } },
  });
  if (!member) throw notFound('Team member');
  return member;
}
