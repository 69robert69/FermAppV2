import { createRequire } from 'node:module';
import { CROPS, evaluateRotation, summarizeCosts } from '@fermapp/domain';
import ExcelJS from 'exceljs';
import PDFDocument from 'pdfkit';
import { prisma } from '../../lib/prisma';
import { WORK_LABELS } from '../work/work.service';

export const REPORT_TYPES = ['work', 'fuel', 'costs', 'yields', 'rotation', 'irrigation'] as const;
export type ReportType = (typeof REPORT_TYPES)[number];

interface Column {
  key: string;
  label: string;
  /** Lățime relativă în PDF. */
  width: number;
  numeric?: boolean;
}

export interface Report {
  title: string;
  period: string;
  columns: Column[];
  rows: Record<string, string | number | null>[];
  totals?: Record<string, string | number>;
}

interface Range {
  from: Date;
  to: Date;
  year: number;
}

const day = (date: Date | null) => (date ? date.toISOString().slice(0, 10) : '');
const round = (value: number, digits = 2) => Math.round(value * 10 ** digits) / 10 ** digits;
const sum = (rows: Report['rows'], key: string) => round(rows.reduce((total, row) => total + Number(row[key] ?? 0), 0));

const IRRIGATION_LABELS = { REEL: 'Tambur', PIVOT: 'Pivot', LINEAR: 'Liniar' } as const;
const STATUS_LABELS = { RUNNING: 'În desfășurare', PAUSED: 'Pauză', COMPLETED: 'Finalizată', CANCELLED: 'Anulată' } as const;
const CATEGORY_COLUMNS = [
  ['SEED', 'Semințe'],
  ['FERTILIZER', 'Îngrășăminte'],
  ['PESTICIDE', 'Pesticide'],
  ['FUEL', 'Combustibil'],
  ['IRRIGATION', 'Irigații'],
] as const;

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
    title: 'Lucrări efectuate',
    period: `${day(range.from)} - ${day(range.to)}`,
    columns: [
      { key: 'date', label: 'Data', width: 1.1 },
      { key: 'parcel', label: 'Parcelă', width: 1.5 },
      { key: 'work', label: 'Lucrare', width: 1.1 },
      { key: 'machine', label: 'Utilaj', width: 1.4 },
      { key: 'operator', label: 'Operator', width: 1.4 },
      { key: 'area', label: 'Realizat (ha)', width: 1, numeric: true },
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
    const name = item.machine?.name ?? 'Fără utilaj';
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
    title: 'Consum motorină',
    period: `${day(range.from)} - ${day(range.to)}`,
    columns: [
      { key: 'machine', label: 'Utilaj', width: 2 },
      { key: 'sessions', label: 'Lucrări', width: 1, numeric: true },
      { key: 'area', label: 'Suprafață (ha)', width: 1, numeric: true },
      { key: 'fuel', label: 'Motorină (l)', width: 1, numeric: true },
      { key: 'perHa', label: 'l/ha', width: 1, numeric: true },
    ],
    rows,
    totals: { sessions: sum(rows, 'sessions'), area: sum(rows, 'area'), fuel: sum(rows, 'fuel') },
  };
}

async function costReport(farmId: string, range: Range): Promise<Report> {
  const parcels = await prisma.parcel.findMany({
    where: { farmId },
    include: { costs: { where: { year: range.year } }, seasons: { where: { year: range.year } } },
    orderBy: { name: 'asc' },
  });
  const rows = parcels
    .filter((parcel) => parcel.costs.length > 0)
    .map((parcel) => {
      const season = parcel.seasons[0];
      const summary = summarizeCosts({
        areaHa: parcel.areaHa,
        costs: parcel.costs.map((cost) => ({ category: cost.category, amount: Number(cost.amount) })),
        expectedYieldTHa: season?.expectedYieldTHa,
        expectedPricePerT: season?.expectedPricePerT == null ? null : Number(season.expectedPricePerT),
        actualYieldT: season?.yieldT,
        actualPricePerT: season?.actualPricePerT == null ? null : Number(season.actualPricePerT),
      });
      return {
        parcel: parcel.name,
        ...Object.fromEntries(CATEGORY_COLUMNS.map(([code]) => [code, summary.byCategory[code]])),
        total: summary.totalCost,
        perHa: summary.costPerHa,
        estimated: summary.estimatedProfit,
        actual: summary.actualProfit,
      };
    });
  return {
    title: 'Costuri și profit',
    period: `Anul ${range.year}`,
    columns: [
      { key: 'parcel', label: 'Parcelă', width: 1.6 },
      ...CATEGORY_COLUMNS.map(([key, label]) => ({ key, label, width: 1, numeric: true })),
      { key: 'total', label: 'Total', width: 1, numeric: true },
      { key: 'perHa', label: 'Cost/ha', width: 0.9, numeric: true },
      { key: 'estimated', label: 'Profit estimat', width: 1, numeric: true },
      { key: 'actual', label: 'Profit real', width: 1, numeric: true },
    ],
    rows,
    totals: { total: sum(rows, 'total') },
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
    title: 'Producții',
    period: `Anul ${range.year}`,
    columns: [
      { key: 'parcel', label: 'Parcelă', width: 1.6 },
      { key: 'crop', label: 'Cultură', width: 1.3 },
      { key: 'area', label: 'Suprafață (ha)', width: 1, numeric: true },
      { key: 'harvest', label: 'Recoltare', width: 1.1 },
      { key: 'yield', label: 'Producție (t)', width: 1, numeric: true },
      { key: 'perHa', label: 't/ha', width: 0.8, numeric: true },
      { key: 'moisture', label: 'Umiditate (%)', width: 1, numeric: true },
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
    title: 'Rotația culturilor',
    period: `Anul ${range.year}`,
    columns: [
      { key: 'parcel', label: 'Parcelă', width: 1.2 },
      { key: 'crop', label: 'Cultură', width: 1 },
      { key: 'previous', label: 'Premergătoare', width: 1 },
      { key: 'score', label: 'Scor', width: 0.5, numeric: true },
      { key: 'verdict', label: 'Verdict', width: 0.9 },
      { key: 'explanation', label: 'Explicație', width: 3 },
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
    title: 'Irigații',
    period: `${day(range.from)} - ${day(range.to)}`,
    columns: [
      { key: 'date', label: 'Data', width: 1.1 },
      { key: 'parcel', label: 'Parcelă', width: 1.5 },
      { key: 'system', label: 'Sistem', width: 0.9 },
      { key: 'route', label: 'Traseu (m)', width: 0.9, numeric: true },
      { key: 'hours', label: 'Ore', width: 0.7, numeric: true },
      { key: 'dose', label: 'Doză (mm)', width: 0.8, numeric: true },
      { key: 'percent', label: 'Realizat (%)', width: 0.9, numeric: true },
      { key: 'status', label: 'Status', width: 1.1 },
    ],
    rows,
    totals: { hours: sum(rows, 'hours') },
  };
}

const BUILDERS: Record<ReportType, (farmId: string, range: Range) => Promise<Report>> = {
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
    sheet.getColumn(index + 1).width = Math.max(12, column.width * 16);
  });
  sheet.views = [{ state: 'frozen', ySplit: 4 }];
  return Buffer.from(await workbook.xlsx.writeBuffer());
}

const require = createRequire(import.meta.url);
/** DejaVu conține diacriticele românești, pe care fonturile standard PDF nu le au. */
const FONT_REGULAR = require.resolve('dejavu-fonts-ttf/ttf/DejaVuSans.ttf');
const FONT_BOLD = require.resolve('dejavu-fonts-ttf/ttf/DejaVuSans-Bold.ttf');

export function reportToPdf(report: Report, farmName: string): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    const doc = new PDFDocument({ size: 'A4', layout: 'landscape', margin: 36, bufferPages: true });
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

    doc.font('bold').fontSize(16).fillColor('#22261d').text(report.title);
    doc.font('regular').fontSize(9).fillColor('#5b6150').text(`${farmName}, ${report.period}`);
    doc.moveDown(0.8);

    drawRow(report.columns.map((column) => column.label), 'bold', '#e7e2cc');
    if (report.rows.length === 0) {
      doc.moveDown().font('regular').fontSize(10).fillColor('#5b6150').text('Nu există date pentru perioada aleasă.', left);
    }
    for (const row of report.rows) {
      drawRow(report.columns.map((column) => String(row[column.key] ?? '')), 'regular');
    }
    if (report.totals && report.rows.length > 0) {
      drawRow(
        report.columns.map((column, index) => (index === 0 ? 'Total' : String(report.totals![column.key] ?? ''))),
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
        .text(`Pagina ${i + 1} din ${range.count}`, left, doc.page.height - 30, {
          width: tableWidth,
          align: 'right',
          lineBreak: false,
        });
    }
    doc.end();
  });
}
