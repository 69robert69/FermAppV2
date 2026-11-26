import { z } from 'zod';
import { badRequest, forbidden } from '../../lib/errors';
import { prisma } from '../../lib/prisma';
import { ApiRouter, FileResult, ROLES } from '../../lib/route';
import { buildReport, FINANCIAL_REPORTS, REPORT_TYPES, reportToExcel, reportToPdf } from './reports.service';

export const reportRoutes = new ApiRouter('/reports', 'Reports');


reportRoutes.get(
  '/:type',
  {
    summary: 'Generate a report in JSON, PDF or Excel',
    roles: ROLES.all,
    produces: 'application/pdf',
    params: z.object({ type: z.enum(REPORT_TYPES) }),
    query: z.object({
      format: z.enum(['json', 'pdf', 'xlsx']).default('json'),
      orientation: z.enum(['portrait', 'landscape']).optional(),
      year: z.coerce.number().int().min(2000).max(2100).optional(),
      from: z.coerce.date().optional(),
      to: z.coerce.date().optional(),
    }),
  },
  async ({ auth, params, query }) => {
    if (auth.role === 'OPERATOR' && FINANCIAL_REPORTS.has(params.type)) throw forbidden();
    const year = query.year ?? new Date().getFullYear();
    const from = query.from ?? new Date(Date.UTC(year, 0, 1));
    const to = query.to ?? new Date(Date.UTC(year, 11, 31, 23, 59, 59));
    if (from > to) throw badRequest('The start date must be before the end date.');

    const report = await buildReport(params.type, auth.farmId, { from, to, year });
    if (query.format === 'json') return report;

    const farm = await prisma.farm.findUniqueOrThrow({ where: { id: auth.farmId } });
    const filename = `report-${params.type}-${year}`;
    if (query.format === 'pdf') {
      return new FileResult(await reportToPdf(report, farm.name, query.orientation), 'application/pdf', `${filename}.pdf`);
    }
    return new FileResult(
      await reportToExcel(report, farm.name),
      'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      `${filename}.xlsx`,
    );
  },
);
