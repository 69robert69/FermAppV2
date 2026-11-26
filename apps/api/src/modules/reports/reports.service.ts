import { createRequire } from 'node:module';
import {
  cropsReport,
  CROPS,
  ECONOMIC_CATEGORIES,
  ECONOMIC_LABELS,
  evaluateRotation,
  farmReport,
  formatEuro,
  isTillageSystem,
  machinesReport,
  parcelsReport,
  profitabilityReport,
  reportChartPoints,
  type ReportParcel,
  type ReportRun,
  type ReportTable,
} from '@fermapp/domain';
import ExcelJS from 'exceljs';
import PDFDocument from 'pdfkit';
import { prisma } from '../../lib/prisma';
import { farmEconomics } from '../economics/economics.service';
import { liveProgress, WORK_LABELS } from '../work/work.service';

export const REPORT_TYPES = ['costs', 'profitability', 'farm', 'parcels', 'work', 'machines', 'crops', 'fuel', 'yields', 'rotation', 'irrigation'] as const;
export type ReportType = (typeof REPORT_TYPES)[number];
/** Reports with money in them are hidden from operators. */
export const FINANCIAL_REPORTS = new Set<ReportType>(['costs', 'profitability', 'farm']);

interface Column {
  key: string;
  label: string;
  /** Relative width in the PDF. */
  width: number;
  numeric?: boolean;
  /** An amount in euro, shown as €1.250,50 in PDF and Excel. */
  money?: boolean;
}

export interface Report extends Omit<ReportTable, 'columns'> {
  columns: Column[];
}

interface Range {
  from: Date;
  to: Date;
  year: number;
}

const day = (date: Date | null) => (date ? date.toISOString().slice(0, 10) : '');
const round = (value: number, digits = 2) => Math.round(value * 10 ** digits) / 10 ** digits;
const sum = (rows: Report['rows'], key: string) => round(rows.reduce((total, row) => total + Number(row[key] ?? 0), 0));

const IRRIGATION_LABELS = { REEL: 'Hose reel', PIVOT: 'Pivot', LINEAR: 'Linear' } as const;
const STATUS_LABELS = { RUNNING: 'Running', PAUSED: 'Paused', COMPLETED: 'Completed', CANCELLED: 'Cancelled' } as const;

async function workReport(farmId: string, range: Range): Promise<Report> {
  const items = await prisma.workSession.findMany({
    where: { farmId, startedAt: { gte: range.from, lte: range.to } },
    include: { parcel: true, machine: true, operator: true },
    orderBy: { startedAt: 'asc' },
  });
  const rows = items.map((item) => ({
    date: day(item.startedAt),
    parcel: item.parcel.name,
    work: WORK_LABELS[item.type],
    machine: item.machine?.name ?? '',
    operator: item.operator.fullName,
    area: round((item.areaHa * item.progressPct) / 100),
    status: STATUS_LABELS[item.status],
  }));
  return {
    title: 'Completed work',
    period: `${day(range.from)} - ${day(range.to)}`,
    columns: [
      { key: 'date', label: 'Date', width: 1.1 },
      { key: 'parcel', label: 'Parcel', width: 1.5 },
      { key: 'work', label: 'Operation', width: 1.1 },
      { key: 'machine', label: 'Machine', width: 1.4 },
      { key: 'operator', label: 'Operator', width: 1.4 },
      { key: 'area', label: 'Done (ha)', width: 1, numeric: true },
      { key: 'status', label: 'Status', width: 1.1 },
    ],
    rows,
    totals: { area: sum(rows, 'area') },
  };
}

async function fuelReport(farmId: string, range: Range): Promise<Report> {
  const items = await prisma.workSession.findMany({
    where: { farmId, status: 'COMPLETED', completedAt: { gte: range.from, lte: range.to } },
    include: { machine: true },
  });
  const byMachine = new Map<string, { machine: string; sessions: number; area: number; fuel: number }>();
  for (const item of items) {
    const name = item.machine?.name ?? 'No machine';
    const row = byMachine.get(name) ?? { machine: name, sessions: 0, area: 0, fuel: 0 };
    row.sessions += 1;
    row.area += (item.areaHa * item.progressPct) / 100;
    row.fuel += item.fuelLiters ?? 0;
    byMachine.set(name, row);
  }
  const rows = [...byMachine.values()].map((row) => ({
    machine: row.machine,
    sessions: row.sessions,
    area: round(row.area),
    fuel: round(row.fuel),
    perHa: row.area > 0 ? round(row.fuel / row.area) : 0,
  }));
  return {
    title: 'Diesel consumption',
    period: `${day(range.from)} - ${day(range.to)}`,
    columns: [
      { key: 'machine', label: 'Machine', width: 2 },
      { key: 'sessions', label: 'Operations', width: 1, numeric: true },
      { key: 'area', label: 'Area (ha)', width: 1, numeric: true },
      { key: 'fuel', label: 'Diesel (l)', width: 1, numeric: true },
      { key: 'perHa', label: 'l/ha', width: 1, numeric: true },
    ],
    rows,
    totals: { sessions: sum(rows, 'sessions'), area: sum(rows, 'area'), fuel: sum(rows, 'fuel') },
  };
}

/** Real cost per hectare and profit of every parcel: inputs, operations, machinery, labour and recorded expenses. */
async function costReport(farmId: string, range: Range): Promise<Report> {
  const { parcels, summary } = await farmEconomics(farmId, range.year);
  const rows = parcels
    .filter((item) => item.result.season.total > 0)
    .map((item) => ({
      parcel: item.name,
      crop: item.cropName ?? '',
      area: Math.round(item.result.areaHa * 100) / 100,
      ...Object.fromEntries(ECONOMIC_CATEGORIES.map((category) => [category, item.result.season.byCategory[category]])),
      total: item.result.season.total,
      perHa: item.result.season.perHa,
      estimated: item.result.expected?.profit ?? null,
      actual: item.result.actual?.profit ?? null,
    }));
  return {
    title: 'Costs and profit',
    period: `Year ${range.year}, in euro`,
    columns: [
      { key: 'parcel', label: 'Parcel', width: 1.4 },
      { key: 'crop', label: 'Crop', width: 0.9 },
      { key: 'area', label: 'ha', width: 0.6, numeric: true },
      ...ECONOMIC_CATEGORIES.map((category) => ({ key: category, label: ECONOMIC_LABELS[category], width: 0.9, numeric: true, money: true })),
      { key: 'total', label: 'Total', width: 1, numeric: true, money: true },
      { key: 'perHa', label: 'Cost/ha', width: 0.9, numeric: true, money: true },
      { key: 'estimated', label: 'Estimated profit', width: 1, numeric: true, money: true },
      { key: 'actual', label: 'Actual profit', width: 1, numeric: true, money: true },
    ],
    rows,
    totals: {
      area: summary.areaHa,
      ...Object.fromEntries(ECONOMIC_CATEGORIES.map((category) => [category, summary.byCategory[category]])),
      total: summary.cost,
      perHa: summary.costPerHa,
      ...(summary.expectedProfit != null ? { estimated: summary.expectedProfit } : {}),
      ...(summary.actualProfit != null ? { actual: summary.actualProfit } : {}),
    },
  };
}

async function yieldReport(farmId: string, range: Range): Promise<Report> {
  const seasons = await prisma.cropSeason.findMany({
    where: { year: range.year, parcel: { farmId } },
    include: { parcel: true },
    orderBy: { parcel: { name: 'asc' } },
  });
  const rows = seasons.map((season) => ({
    parcel: season.parcel.name,
    crop: CROPS[season.crop].name,
    area: season.parcel.areaHa,
    harvest: day(season.harvestDate),
    yield: season.yieldT,
    perHa: season.yieldT != null ? round(season.yieldT / season.parcel.areaHa) : null,
    moisture: season.harvestMoisture,
  }));
  return {
    title: 'Yields',
    period: `Year ${range.year}`,
    columns: [
      { key: 'parcel', label: 'Parcel', width: 1.6 },
      { key: 'crop', label: 'Crop', width: 1.3 },
      { key: 'area', label: 'Area (ha)', width: 1, numeric: true },
      { key: 'harvest', label: 'Harvest', width: 1.1 },
      { key: 'yield', label: 'Yield (t)', width: 1, numeric: true },
      { key: 'perHa', label: 't/ha', width: 0.8, numeric: true },
      { key: 'moisture', label: 'Moisture (%)', width: 1, numeric: true },
    ],
    rows,
    totals: { area: sum(rows, 'area'), yield: sum(rows, 'yield') },
  };
}

async function rotationReport(farmId: string, range: Range): Promise<Report> {
  const parcels = await prisma.parcel.findMany({
    where: { farmId, archivedAt: null },
    include: { seasons: { orderBy: { year: 'desc' }, take: 10 } },
    orderBy: { name: 'asc' },
  });
  const rows = parcels.flatMap((parcel) => {
    const current = parcel.seasons.find((season) => season.year === range.year);
    if (!current) return [];
    const history = parcel.seasons.filter((season) => season.year < range.year);
    const result = evaluateRotation(current.crop, range.year, history);
    return [
      {
        parcel: parcel.name,
        crop: CROPS[current.crop].name,
        previous: history[0] ? CROPS[history[0].crop].name : '',
        score: result.score,
        verdict: result.label.replace(/^\S+\s/, ''),
        explanation: result.explanation,
      },
    ];
  });
  return {
    title: 'Crop rotation',
    period: `Year ${range.year}`,
    columns: [
      { key: 'parcel', label: 'Parcel', width: 1.2 },
      { key: 'crop', label: 'Crop', width: 1 },
      { key: 'previous', label: 'Previous crop', width: 1 },
      { key: 'score', label: 'Score', width: 0.5, numeric: true },
      { key: 'verdict', label: 'Verdict', width: 0.9 },
      { key: 'explanation', label: 'Explanation', width: 3 },
    ],
    rows,
  };
}

async function irrigationReport(farmId: string, range: Range): Promise<Report> {
  const runs = await prisma.irrigationRun.findMany({
    where: { farmId, startedAt: { gte: range.from, lte: range.to } },
    include: { parcel: true },
    orderBy: { startedAt: 'asc' },
  });
  const rows = runs.map((run) => ({
    date: day(run.startedAt),
    parcel: run.parcel.name,
    system: IRRIGATION_LABELS[run.systemType],
    route: run.routeLengthM,
    hours: round((run.durationHours * run.progressPct) / 100),
    dose: run.doseMm,
    percent: run.progressPct,
    status: STATUS_LABELS[run.status],
  }));
  return {
    title: 'Irrigation',
    period: `${day(range.from)} - ${day(range.to)}`,
    columns: [
      { key: 'date', label: 'Date', width: 1.1 },
      { key: 'parcel', label: 'Parcel', width: 1.5 },
      { key: 'system', label: 'System', width: 0.9 },
      { key: 'route', label: 'Route (m)', width: 0.9, numeric: true },
      { key: 'hours', label: 'Hours', width: 0.7, numeric: true },
      { key: 'dose', label: 'Dose (mm)', width: 0.8, numeric: true },
      { key: 'percent', label: 'Done (%)', width: 0.9, numeric: true },
      { key: 'status', label: 'Status', width: 1.1 },
    ],
    rows,
    totals: { hours: sum(rows, 'hours') },
  };
}

/** Parcels, tasks, runs and machines of the farm in the shape the shared reports read. */
async function reportSources(farmId: string, range: Range) {
  const [parcels, tasks, works, machines, seasons] = await Promise.all([
    prisma.parcel.findMany({ where: { farmId, archivedAt: null }, include: { seasons: { where: { year: range.year }, take: 1 } }, orderBy: { name: 'asc' } }),
    prisma.task.findMany({ where: { farmId }, select: { id: true, title: true, type: true, status: true, dueDate: true, parcelId: true } }),
    prisma.workSession.findMany({ where: { farmId }, include: { machine: { select: { avgConsumptionLh: true } } } }),
    prisma.machine.findMany({ where: { farmId, archivedAt: null }, include: { operator: { select: { fullName: true } } }, orderBy: { name: 'asc' } }),
    prisma.cropSeason.findMany({ where: { year: range.year, parcel: { farmId, archivedAt: null } } }),
  ]);
  const reportParcels: ReportParcel[] = parcels.map((parcel) => ({
    id: parcel.id,
    name: parcel.name,
    code: parcel.code,
    areaHa: parcel.areaHa,
    locality: parcel.locality,
    crop: parcel.seasons[0]?.crop ?? null,
    tillage: isTillageSystem(parcel.tillageSystem) ? parcel.tillageSystem : null,
  }));
  const runs: ReportRun[] = works.map((work) => {
    const percent = liveProgress(work).percent;
    const hours = (work.durationHours * percent) / 100;
    return {
      parcelId: work.parcelId,
      machineId: work.machineId,
      type: work.type,
      status: work.status,
      areaHa: work.areaHa,
      percent,
      hours,
      fuelL: work.fuelLiters ?? (work.machine?.avgConsumptionLh ? work.machine.avgConsumptionLh * hours : null),
      startedAt: work.startedAt.toISOString(),
    };
  });
  return {
    parcels: reportParcels,
    tasks: tasks.map((task) => ({ ...task, dueDate: task.dueDate.toISOString() })),
    runs,
    machines: machines.map((machine) => ({ id: machine.id, name: machine.name, type: machine.type, operatorName: machine.operator?.fullName ?? null })),
    seasons: seasons.map((season) => ({ parcelId: season.parcelId, crop: season.crop, yieldT: season.yieldT })),
  };
}

const BUILDERS: Record<ReportType, (farmId: string, range: Range) => Promise<Report>> = {
  parcels: async (farmId, range) => {
    const data = await reportSources(farmId, range);
    return parcelsReport({ year: range.year, parcels: data.parcels, tasks: data.tasks, runs: data.runs });
  },
  machines: async (farmId, range) => {
    const data = await reportSources(farmId, range);
    return machinesReport({ year: range.year, machines: data.machines, runs: data.runs, parcelName: (id) => data.parcels.find((parcel) => parcel.id === id)?.name ?? '' });
  },
  crops: async (farmId, range) => {
    const data = await reportSources(farmId, range);
    return cropsReport({ year: range.year, parcels: data.parcels, seasons: data.seasons });
  },
  profitability: async (farmId, range) => {
    const economics = await farmEconomics(farmId, range.year);
    return profitabilityReport({ year: range.year, parcels: economics.parcels, summary: economics.summary });
  },
  farm: async (farmId, range) => {
    const [data, economics, farm] = await Promise.all([reportSources(farmId, range), farmEconomics(farmId, range.year), prisma.farm.findUniqueOrThrow({ where: { id: farmId } })]);
    return farmReport({ year: range.year, farmName: farm.name, parcels: data.parcels, runs: data.runs, tasks: data.tasks, economics: economics.summary });
  },
  work: workReport,
  fuel: fuelReport,
  costs: costReport,
  yields: yieldReport,
  rotation: rotationReport,
  irrigation: irrigationReport,
};

export const buildReport = (type: ReportType, farmId: string, range: Range) => BUILDERS[type](farmId, range);

export async function reportToExcel(report: Report, farmName: string): Promise<Buffer> {
  const workbook = new ExcelJS.Workbook();
  workbook.creator = 'FermApp';
  const sheet = workbook.addWorksheet(report.title.slice(0, 31));
  sheet.addRow([`${report.title} - ${farmName}`]).font = { bold: true, size: 14 };
  sheet.addRow([report.period]);
  for (const item of report.summary ?? []) sheet.addRow([item.label, item.value]);
  sheet.addRow([]);
  const header = sheet.addRow(report.columns.map((column) => column.label));
  header.font = { bold: true };
  header.eachCell((cell) => {
    cell.fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FFE7E2CC' } };
  });
  for (const row of report.rows) sheet.addRow(report.columns.map((column) => row[column.key] ?? ''));
  if (report.totals) {
    const total = sheet.addRow(
      report.columns.map((column, index) => (index === 0 ? 'Total' : (report.totals![column.key] ?? ''))),
    );
    total.font = { bold: true };
  }
  report.columns.forEach((column, index) => {
    const excelColumn = sheet.getColumn(index + 1);
    excelColumn.width = Math.max(12, column.width * 16);
    if (column.money) excelColumn.numFmt = '"€"#,##0.00';
  });
  sheet.views = [{ state: 'frozen', ySplit: 4 + (report.summary?.length ?? 0) }];
  return Buffer.from(await workbook.xlsx.writeBuffer());
}

const require = createRequire(import.meta.url);
/** DejaVu contains Romanian diacritics, which the standard PDF fonts lack. */
const FONT_REGULAR = require.resolve('dejavu-fonts-ttf/ttf/DejaVuSans.ttf');
const FONT_BOLD = require.resolve('dejavu-fonts-ttf/ttf/DejaVuSans-Bold.ttf');

export function reportToPdf(report: Report, farmName: string, orientation: 'portrait' | 'landscape' = report.orientation ?? 'landscape'): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    const doc = new PDFDocument({ size: 'A4', layout: orientation, margin: 36, bufferPages: true });
    const generated = new Date().toLocaleString('en-GB', { day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });
    const chunks: Buffer[] = [];
    doc.on('data', (chunk: Buffer) => chunks.push(chunk));
    doc.on('end', () => resolve(Buffer.concat(chunks)));
    doc.on('error', reject);
    doc.registerFont('regular', FONT_REGULAR);
    doc.registerFont('bold', FONT_BOLD);

    const left = doc.page.margins.left;
    const tableWidth = doc.page.width - left - doc.page.margins.right;
    const bottom = doc.page.height - doc.page.margins.bottom - 18;
    const totalWeight = report.columns.reduce((sum, column) => sum + column.width, 0);
    const widths = report.columns.map((column) => (column.width / totalWeight) * tableWidth);
    const PADDING = 4;

    const cellText = (column: Column, value: string | number | null | undefined) =>
      column.money && typeof value === 'number' ? formatEuro(value, 2) : String(value ?? '');

    const drawRow = (cells: string[], font: 'regular' | 'bold', fill?: string) => {
      doc.font(font).fontSize(8);
      const height =
        Math.max(...cells.map((cell, i) => doc.heightOfString(cell || ' ', { width: widths[i]! - PADDING * 2 }))) +
        PADDING * 2;
      if (doc.y + height > bottom) {
        doc.addPage();
        doc.y = doc.page.margins.top;
      }
      const y = doc.y;
      if (fill) doc.rect(left, y, tableWidth, height).fill(fill);
      doc.fillColor('#22261d');
      let x = left;
      cells.forEach((cell, i) => {
        doc.text(cell, x + PADDING, y + PADDING, {
          width: widths[i]! - PADDING * 2,
          align: report.columns[i]!.numeric ? 'right' : 'left',
          lineBreak: true,
        });
        x += widths[i]!;
      });
      doc.moveTo(left, y + height).lineTo(left + tableWidth, y + height).lineWidth(0.4).stroke('#c9c4ad');
      doc.x = left;
      doc.y = y + height;
    };

    // Header band in the app colours, with the FermApp mark.
    doc.rect(0, 0, doc.page.width, 58).fill('#181B13');
    doc.circle(left + 14, 29, 13).fill('#E2B23D');
    doc.font('bold').fontSize(13).fillColor('#181B13').text('F', left + 9.5, 21.5, { lineBreak: false });
    doc.font('bold').fontSize(14).fillColor('#F0ECDB').text('FermApp', left + 36, 18, { lineBreak: false });
    doc.font('regular').fontSize(8).fillColor('#ACAE96').text(farmName, left + 36, 36, { lineBreak: false });
    doc.font('regular').fontSize(8).fillColor('#ACAE96').text(`Generated ${generated}`, left, 25, { width: tableWidth, align: 'right', lineBreak: false });
    doc.x = left;
    doc.y = 78;
    doc.font('bold').fontSize(16).fillColor('#22261d').text(report.title);
    doc.font('regular').fontSize(9).fillColor('#5b6150').text(report.period);
    doc.moveDown(0.6);

    if (report.summary?.length) {
      const boxWidth = (tableWidth - (report.summary.length - 1) * 8) / report.summary.length;
      const y = doc.y;
      report.summary.forEach((item, index) => {
        const x = left + index * (boxWidth + 8);
        doc.roundedRect(x, y, boxWidth, 40, 4).fill('#f2eedd');
        doc.font('regular').fontSize(7.5).fillColor('#5b6150').text(item.label, x + 8, y + 7, { width: boxWidth - 16, lineBreak: false });
        doc.font('bold').fontSize(12).fillColor('#22261d').text(item.value, x + 8, y + 20, { width: boxWidth - 16, lineBreak: false });
      });
      doc.x = left;
      doc.y = y + 52;
    }

    const points = reportChartPoints(report);
    if (report.chart && points.length) {
      doc.font('bold').fontSize(10).fillColor('#22261d').text(report.chart.title, left);
      doc.moveDown(0.3);
      const max = Math.max(...points.map((point) => Math.abs(point.value)));
      const labelWidth = Math.min(160, tableWidth * 0.3);
      const valueWidth = 80;
      const barSpace = tableWidth - labelWidth - valueWidth - 12;
      for (const point of points) {
        const y = doc.y;
        const width = max > 0 ? (Math.abs(point.value) / max) * barSpace : 0;
        doc.font('regular').fontSize(8).fillColor('#22261d').text(point.label, left, y + 1, { width: labelWidth - 6, lineBreak: false, ellipsis: true });
        doc.roundedRect(left + labelWidth, y + 1, Math.max(2, width), 9, 2).fill(point.value < 0 ? '#D93C74' : '#C99A2E');
        const unit = report.chart.unit ? ` ${report.chart.unit}` : '';
        const value = report.chart.money ? formatEuro(point.value, 0) : `${Math.round(point.value * 10) / 10}${unit}`;
        doc.font('regular').fontSize(8).fillColor('#22261d').text(value, left + labelWidth + barSpace + 12, y + 1, { width: valueWidth, align: 'right', lineBreak: false });
        doc.x = left;
        doc.y = y + 14;
      }
      doc.moveDown(0.8);
    }

    drawRow(report.columns.map((column) => column.label), 'bold', '#e7e2cc');
    if (report.rows.length === 0) {
      doc.moveDown().font('regular').fontSize(10).fillColor('#5b6150').text('There is no data for the selected period.', left);
    }
    for (const row of report.rows) {
      drawRow(report.columns.map((column) => cellText(column, row[column.key])), 'regular');
    }
    if (report.totals && report.rows.length > 0) {
      drawRow(
        report.columns.map((column, index) => (index === 0 ? 'Total' : cellText(column, report.totals![column.key]))),
        'bold',
        '#f2eedd',
      );
    }

    const range = doc.bufferedPageRange();
    for (let i = 0; i < range.count; i += 1) {
      doc.switchToPage(range.start + i);
      doc
        .font('regular')
        .fontSize(7)
        .fillColor('#5b6150')
        .text(`FermApp, ${farmName}, generated ${generated}    Page ${i + 1} of ${range.count}`, left, doc.page.height - 30, {
          width: tableWidth,
          align: 'right',
          lineBreak: false,
        });
    }
    doc.end();
  });
}
