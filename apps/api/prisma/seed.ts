import { computeGeoMetrics, type PolygonGeometry } from '@fermapp/domain';
import { PrismaClient, type CropCode, type Prisma } from '@prisma/client';
import bcrypt from 'bcryptjs';

/** Demo data for local development. It does not run in production. */
const prisma = new PrismaClient();

const DEMO_EMAIL = 'demo@fermapp.local';
const DEMO_PASSWORD = 'FermApp-demo-2026';

const rectangle = (lng: number, lat: number, width: number, height: number): PolygonGeometry => ({
  type: 'Polygon',
  coordinates: [[[lng, lat], [lng + width, lat], [lng + width, lat + height], [lng + width * 0.15, lat + height * 1.1], [lng, lat]]],
});

const PARCELS: { name: string; code: string; geometry: PolygonGeometry; history: [number, CropCode][] }[] = [
  { name: 'North 3', code: 'NV-003', geometry: rectangle(26.1, 44.8, 0.012, 0.008), history: [[2026, 'WHEAT'], [2025, 'CORN'], [2024, 'SUNFLOWER'], [2023, 'WHEAT'], [2022, 'SOY']] },
  { name: 'South-1', code: 'SD-001', geometry: rectangle(26.12, 44.78, 0.01, 0.006), history: [[2026, 'CORN'], [2025, 'SOY'], [2024, 'WHEAT'], [2023, 'RAPESEED']] },
  { name: 'Lunca Mare', code: 'LM-010', geometry: rectangle(26.08, 44.77, 0.016, 0.01), history: [[2025, 'ALFALFA'], [2024, 'ALFALFA'], [2023, 'ALFALFA']] },
];

async function main() {
  if (process.env.NODE_ENV === 'production') throw new Error('Demo data is not loaded in production.');
  if (await prisma.user.findUnique({ where: { email: DEMO_EMAIL } })) {
    console.log('Demo data already exists.');
    return;
  }

  const farm = await prisma.farm.create({ data: { name: 'Demo Farm', county: 'Ilfov' } });
  const admin = await prisma.user.create({
    data: {
      email: DEMO_EMAIL,
      fullName: 'Demo Administrator',
      passwordHash: await bcrypt.hash(DEMO_PASSWORD, 12),
      memberships: { create: { farmId: farm.id, role: 'ADMIN' } },
    },
  });

  for (const item of PARCELS) {
    const metrics = computeGeoMetrics(item.geometry);
    await prisma.parcel.create({
      data: {
        farmId: farm.id,
        name: item.name,
        code: item.code,
        county: 'Ilfov',
        geometry: item.geometry as unknown as Prisma.InputJsonValue,
        areaHa: metrics.areaHa,
        perimeterM: metrics.perimeterM,
        centerLat: metrics.center.lat,
        centerLng: metrics.center.lng,
        seasons: { create: item.history.map(([year, crop]) => ({ year, crop })) },
      },
    });
  }

  await prisma.machine.createMany({
    data: [
      { farmId: farm.id, name: 'Fendt 724', type: 'TRACTOR', brand: 'Fendt', model: '724 Vario', powerHp: 240, avgConsumptionLh: 22, operatorId: admin.id },
      { farmId: farm.id, name: 'Claas Lexion', type: 'COMBINE', brand: 'Claas', model: 'Lexion 760', powerHp: 490, avgConsumptionLh: 45 },
      { farmId: farm.id, name: 'John Deere R4040', type: 'SPRAYER', brand: 'John Deere', model: 'R4040i', powerHp: 310, avgConsumptionLh: 18 },
    ],
  });

  await prisma.task.create({
    data: {
      farmId: farm.id,
      type: 'PEST_CHECK',
      title: 'Pest check',
      priority: 'HIGH',
      dueDate: new Date(Date.now() + 2 * 86_400_000),
      assigneeId: admin.id,
    },
  });

  console.log(`Demo account: ${DEMO_EMAIL} / ${DEMO_PASSWORD}`);
}

main()
  .catch((error) => {
    console.error(error);
    process.exitCode = 1;
  })
  .finally(() => prisma.$disconnect());
