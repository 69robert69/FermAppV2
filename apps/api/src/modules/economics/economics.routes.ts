import { CHEMICAL_KINDS, parseSettings, parseSheet } from '@fermapp/domain';
import { z } from 'zod';
import { requireParcel } from '../../lib/access';
import { ApiRouter, ROLES } from '../../lib/route';
import { farmEconomics, loadSettings, loadSheets, saveSettings, saveSheet, sheetKey } from './economics.service';
import { prisma } from '../../lib/prisma';

export const economicsRoutes = new ApiRouter('/economics', 'Economics');

const money = z.number().min(0).max(100_000_000);
const text = (max = 120) => z.string().trim().max(max);
const lineId = z.string().trim().min(1).max(64);
const year = z.coerce.number().int().min(2000).max(2100);

const settingsSchema = z.object({
  fuelPricePerL: z.number().min(0).max(20),
  defaultLaborRate: z.number().min(0).max(500),
  labor: z.record(z.string().max(64), z.object({ monthlySalary: money.nullable().optional(), hourlyRate: z.number().min(0).max(500).nullable().optional() })),
  machines: z.record(
    z.string().max(64),
    z.object({
      purchasePrice: money,
      residualPct: z.number().min(0).max(100).optional(),
      depreciationYears: z.number().min(0.5).max(50),
      annualHours: z.number().min(1).max(8760),
      serviceCostPerYear: money.optional(),
      maintenanceCostPerYear: money.optional(),
    }),
  ),
});

const sheetSchema = z.object({
  seeds: z
    .array(
      z.object({
        id: lineId,
        supplier: text(),
        hybrid: text(),
        category: text(40).nullable().optional(),
        unit: z.enum(['SEEDS', 'KG']),
        bagSize: z.number().positive().max(10_000_000),
        ratePerHa: z.number().min(0).max(10_000_000),
        pricePerBag: money,
        bagsUsed: z.number().min(0).max(1_000_000).nullable().optional(),
      }),
    )
    .max(50),
  fertilizers: z
    .array(
      z.object({
        id: lineId,
        product: text(),
        ratePerHaKg: z.number().min(0).max(10_000),
        pricePerT: money,
        applications: z.number().int().min(1).max(20).optional(),
        n: z.number().min(0).max(100).nullable().optional(),
        p: z.number().min(0).max(100).nullable().optional(),
        k: z.number().min(0).max(100).nullable().optional(),
      }),
    )
    .max(50),
  chemicals: z
    .array(
      z.object({
        id: lineId,
        product: text(),
        kind: z.enum(CHEMICAL_KINDS),
        dosePerHa: z.number().min(0).max(1000),
        unit: z.enum(['L', 'KG']),
        pricePerUnit: money,
        applications: z.number().int().min(1).max(20).optional(),
      }),
    )
    .max(50),
  misc: z.array(z.object({ id: lineId, label: text(), amount: money, basis: z.enum(['PER_HA', 'TOTAL']) })).max(50),
  revenue: z.object({
    expectedYieldTHa: z.number().min(0).max(200).nullable().optional(),
    actualYieldTHa: z.number().min(0).max(200).nullable().optional(),
    pricePerT: money.nullable().optional(),
    actualPricePerT: money.nullable().optional(),
  }),
});

const sheetParams = z.object({ parcelId: z.uuid(), year });

economicsRoutes.get('/settings', { summary: 'Fuel price, labour rates and machine ownership costs', roles: ROLES.planners }, async ({ auth }) =>
  loadSettings(auth.farmId),
);

economicsRoutes.post(
  '/settings',
  { summary: 'Save the economic settings of the farm', roles: ROLES.managers, status: 200, body: settingsSchema },
  async ({ auth, body }) => saveSettings(auth.farmId, parseSettings(body)),
);

economicsRoutes.get(
  '/sheets',
  { summary: 'The economic sheets of every parcel in a season', roles: ROLES.planners, query: z.object({ year }) },
  async ({ auth, query }) => {
    const sheets = await loadSheets(auth.farmId, query.year);
    return [...sheets.entries()].map(([parcelId, sheet]) => ({ parcelId, year: query.year, sheet }));
  },
);

economicsRoutes.get(
  '/sheets/:parcelId/:year',
  { summary: 'The economic sheet of a parcel in a season', roles: ROLES.planners, params: sheetParams },
  async ({ auth, params }) => {
    await requireParcel(auth.farmId, params.parcelId);
    const doc = await prisma.economicDocument.findUnique({ where: { farmId_key: { farmId: auth.farmId, key: sheetKey(params.parcelId, params.year) } } });
    return { parcelId: params.parcelId, year: params.year, sheet: parseSheet(doc?.data) };
  },
);

economicsRoutes.post(
  '/sheets/:parcelId/:year',
  { summary: 'Save the seed, fertiliser, crop protection, other costs and revenue of a parcel', roles: ROLES.planners, status: 200, params: sheetParams, body: sheetSchema },
  async ({ auth, params, body }) => {
    await requireParcel(auth.farmId, params.parcelId);
    const sheet = await saveSheet(auth.farmId, params.parcelId, params.year, parseSheet(body));
    return { parcelId: params.parcelId, year: params.year, sheet };
  },
);

economicsRoutes.get(
  '/farm',
  { summary: 'Real cost per hectare and profit of every parcel, by crop and by technology', roles: ROLES.planners, query: z.object({ year }) },
  async ({ auth, query }) => farmEconomics(auth.farmId, query.year),
);
