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
  type ReportParcel,
  type ReportRun,
  type ReportTable,
} from '@fermapp/domain';
import { File, Paths } from 'expo-file-system';
import { printToFileAsync } from 'expo-print';
import { IRRIGATION_TYPES, WORK_TYPES } from '../lib/format';
import { liveItems } from './liveStore';
import { runProgress } from './services';
import type { CropHistoryDoc, FarmDoc, IrrigationDoc, MachineDoc, MachineWorkDoc, ParcelDoc, TaskDoc, UserProfile } from './types';

export const REPORT_TYPES = ['costs', 'profitability', 'farm', 'parcels', 'work', 'machines', 'crops', 'fuel', 'yields', 'rotation', 'irrigation'] as const;
export type ReportType = (typeof REPORT_TYPES)[number];

type Cell = string | number | null;

interface Column {
  key: string;
  label: string;
  width: number;
  numeric?: boolean;
  /** An amount in euro. */
  money?: boolean;
}

export interface Report extends Omit<ReportTable, 'columns' | 'rows'> {
  columns: Column[];
  rows: Record<string, Cell>[];
}

const STATUS = { RUNNING: 'Running', PAUSED: 'Paused', COMPLETED: 'Completed', CANCELLED: 'Cancelled' } as const;

const round = (value: number, digits = 2) => Math.round(value * 10 ** digits) / 10 ** digits;
const sum = (rows: Report['rows'], key: string) => round(rows.reduce((total, row) => total + Number(row[key] ?? 0), 0));
const day = (value: string | null | undefined) => (value ? value.slice(0, 10) : '');
const inYear = (value: string, year: number) => value.startsWith(String(year));

const percentDone = (run: MachineWorkDoc | IrrigationDoc) =>
  runProgress(run, run.finishDate ? new Date(run.finishDate) : new Date()).percent;

async function sources() {
  return {
    parcels: await liveItems<ParcelDoc>('parcels'),
    machines: await liveItems<MachineDoc>('machines'),
    members: await liveItems<UserProfile & FarmDoc>('users'),
    work: await liveItems<MachineWorkDoc>('machineWork'),
    irrigation: await liveItems<IrrigationDoc>('irrigations'),
    seasons: await liveItems<CropHistoryDoc>('cropHistory'),
    tasks: await liveItems<TaskDoc>('tasks'),
  };
}

/** The farm data in the shape the shared reports read. */
function shared(data: Awaited<ReturnType<typeof sources>>, year: number) {
  const parcels: ReportParcel[] = data.parcels
    .filter((parcel) => !parcel.archivedAt)
    .sort((a, b) => a.name.localeCompare(b.name))
    .map((parcel) => ({
      id: parcel.id,
      name: parcel.name,
      code: parcel.code,
      areaHa: parcel.area,
      locality: parcel.locality,
      crop: data.seasons.find((season) => season.parcelId === parcel.id && season.year === year)?.crop ?? null,
      tillage: isTillageSystem(parcel.tillageSystem) ? parcel.tillageSystem : null,
    }));
  const runs: ReportRun[] = data.work.map((run) => {
    const progress = runProgress(run, run.finishDate ? new Date(run.finishDate) : new Date());
    const hours = progress.elapsedMs / 3_600_000;
    const perHour = run.fuelLPerH ?? data.machines.find((machine) => machine.id === run.machineId)?.fuelConsumption ?? null;
    return {
      parcelId: run.parcelId,
      machineId: run.machineId,
      type: run.type,
      status: run.status,
      areaHa: run.areaHa,
      percent: progress.percent,
      hours,
      fuelL: perHour ? perHour * hours : null,
      startedAt: run.startDate,
    };
  });
  const tasks = data.tasks.map((task) => ({ id: task.id, title: task.title, type: task.type, status: task.status, dueDate: task.dueDate, parcelId: task.parcelId }));
  return { parcels, runs, tasks };
}

export async function buildReport(type: ReportType, year: number): Promise<Report> {
  const data = await sources();
  const period = `${year}-01-01 - ${year}-12-31`;
  const parcelName = (id: string) => data.parcels.find((parcel) => parcel.id === id)?.name ?? '';

  if (type === 'parcels' || type === 'machines' || type === 'crops' || type === 'farm') {
    const common = shared(data, year);
    if (type === 'parcels') return parcelsReport({ year, ...common });
    if (type === 'machines') {
      const machines = data.machines.map((machine) => ({ id: machine.id, name: machine.name, type: machine.type, operatorName: data.members.find((member) => member.uid === machine.operator)?.name ?? null }));
      return machinesReport({ year, machines, runs: common.runs, parcelName });
    }
    if (type === 'crops') {
      const seasons = data.seasons.filter((season) => season.year === year).map((season) => ({ parcelId: season.parcelId, crop: season.crop, yieldT: season.yieldT ?? null }));
      return cropsReport({ year, parcels: common.parcels, seasons });
    }
    const { farmEconomics } = await import('./economics');
    const economics = await farmEconomics(year);
    const { farmName } = (await import('./services')).session();
    return farmReport({ year, farmName, ...common, economics: economics.summary });
  }
  if (type === 'profitability') {
    const { farmEconomics } = await import('./economics');
    const economics = await farmEconomics(year);
    return profitabilityReport({ year, parcels: economics.parcels, summary: economics.summary });
  }

  if (type === 'work') {
    const rows = data.work
      .filter((item) => inYear(item.startDate, year))
      .sort((a, b) => a.startDate.localeCompare(b.startDate))
      .map((item) => ({
        date: day(item.startDate),
        parcel: parcelName(item.parcelId),
        work: WORK_TYPES[item.type] ?? item.type,
        machine: data.machines.find((machine) => machine.id === item.machineId)?.name ?? '',
        operator: data.members.find((member) => member.uid === item.operatorId)?.name ?? '',
        area: round((item.areaHa * percentDone(item)) / 100),
        status: STATUS[item.status],
      }));
    return {
      title: 'Completed work',
      period,
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

  if (type === 'fuel') {
    const byMachine = new Map<string, { machine: string; sessions: number; area: number; fuel: number }>();
    for (const item of data.work.filter((run) => run.status === 'COMPLETED' && inYear(run.finishDate ?? run.startDate, year))) {
      const machine = data.machines.find((candidate) => candidate.id === item.machineId);
      const name = machine?.name ?? 'No machine';
      const row = byMachine.get(name) ?? { machine: name, sessions: 0, area: 0, fuel: 0 };
      const progress = runProgress(item, new Date(item.finishDate ?? item.startDate));
      row.sessions += 1;
      row.area += (item.areaHa * progress.percent) / 100;
      row.fuel += ((machine?.fuelConsumption ?? 0) * progress.elapsedMs) / 3_600_000;
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
      period,
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

  if (type === 'costs') {
    // The same real cost per hectare as the parcel's economic analysis.
    const { farmEconomics } = await import('./economics');
    const { parcels, summary } = await farmEconomics(year);
    const rows = parcels
      .filter((item) => item.result.season.total > 0)
      .map((item) => ({
        parcel: item.name,
        crop: item.cropName ?? '',
        area: round(item.result.areaHa),
        ...Object.fromEntries(ECONOMIC_CATEGORIES.map((category) => [category, item.result.season.byCategory[category]])),
        total: item.result.season.total,
        perHa: item.result.season.perHa,
        estimated: item.result.expected?.profit ?? null,
        actual: item.result.actual?.profit ?? null,
      }));
    return {
      title: 'Costs and profit',
      period: `Year ${year}, in euro`,
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

  if (type === 'yields') {
    const rows = data.seasons
      .filter((season) => season.year === year)
      .map((season) => {
        const parcel = data.parcels.find((item) => item.id === season.parcelId);
        const area = parcel?.area ?? 0;
        return {
          parcel: parcel?.name ?? '',
          crop: CROPS[season.crop].name,
          area,
          harvest: day(season.harvestDate),
          yield: season.yieldT ?? null,
          perHa: season.yieldT != null && area > 0 ? round(season.yieldT / area) : null,
          moisture: season.harvestMoisture ?? null,
        };
      });
    return {
      title: 'Yields',
      period: `Year ${year}`,
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

  if (type === 'rotation') {
    const rows = data.parcels
      .filter((parcel) => !parcel.archivedAt)
      .flatMap((parcel) => {
        const seasons = data.seasons.filter((item) => item.parcelId === parcel.id).sort((a, b) => b.year - a.year);
        const current = seasons.find((item) => item.year === year);
        if (!current) return [];
        const history = seasons.filter((item) => item.year < year);
        const result = evaluateRotation(current.crop, year, history);
        return [{
          parcel: parcel.name,
          crop: CROPS[current.crop].name,
          previous: history[0] ? CROPS[history[0].crop].name : '',
          score: result.score,
          verdict: result.label.replace(/^\S+\s/, ''),
          explanation: result.explanation,
        }];
      });
    return {
      title: 'Crop rotation',
      period: `Year ${year}`,
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

  const rows = data.irrigation
    .filter((run) => inYear(run.startDate, year))
    .sort((a, b) => a.startDate.localeCompare(b.startDate))
    .map((run) => {
      const percent = percentDone(run);
      return {
        date: day(run.startDate),
        parcel: parcelName(run.parcelId),
        system: IRRIGATION_TYPES[run.type] ?? run.type,
        route: run.length,
        hours: round((run.durationHours * percent) / 100),
        dose: run.doseMm,
        percent,
        status: STATUS[run.status],
      };
    });
  return {
    title: 'Irrigation',
    period,
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

export const escapeHtml = (value: unknown) =>
  String(value ?? '').replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;');

const PAGE_STYLE = `
  @page { size: A4 landscape; margin: 14mm; }
  body { font-family: sans-serif; color: #22261d; font-size: 11px; }
  h1 { font-size: 20px; margin: 0 0 4px; }
  h2 { font-size: 14px; margin: 18px 0 6px; }
  p { color: #5b6150; margin: 0 0 14px; }
  table { width: 100%; border-collapse: collapse; }
  th { background: #e7e2cc; text-align: left; }
  th, td { padding: 5px; border-bottom: 1px solid #c9c4ad; vertical-align: top; }
  .n { text-align: right; }
  tfoot td { background: #f2eedd; font-weight: bold; }
`;

/** Converts an HTML document to PDF and gives it the requested name. */
/** Renders HTML to an A4 PDF in the cache and returns its address. */
export async function htmlToPdf(html: string, filename: string, orientation: 'portrait' | 'landscape' = 'landscape') {
  const page = orientation === 'portrait' ? { width: 595, height: 842 } : { width: 842, height: 595 };
  const document = html.trimStart().startsWith('<html')
    ? html
    : `<html><head><meta charset="utf-8"><style>${PAGE_STYLE}</style></head><body>${html}</body></html>`;
  // The PDF comes back as base64 and is written like the Excel export. Moving the
  // printer's temporary file did not work on every phone.
  const { base64 } = await printToFileAsync({ html: document, base64: true, ...page });
  if (!base64) throw new Error('The PDF could not be generated.');
  const target = new File(Paths.cache, filename);
  if (target.exists) target.delete();
  target.create();
  target.write(base64, { encoding: 'base64' });
  return target.uri;
}

const columnName = (index: number) => {
  let name = '';
  for (let n = index + 1; n > 0; n = Math.floor((n - 1) / 26)) name = String.fromCharCode(65 + ((n - 1) % 26)) + name;
  return name;
};

const xmlText = (value: unknown) => escapeHtml(value).replaceAll("'", '&apos;');

/** Generates a real Excel file (.xlsx). */
export async function reportToXlsx(report: Report, farmName: string) {
  const { default: JSZip } = await import('jszip');
  const lines: Cell[][] = [
    [`${report.title} - ${farmName}`],
    [report.period],
    [],
    report.columns.map((column) => column.label),
    ...report.rows.map((row) => report.columns.map((column) => row[column.key] ?? null)),
    ...(report.totals ? [report.columns.map((column, index) => (index === 0 ? 'Total' : (report.totals![column.key] ?? null)))] : []),
  ];
  const rows = lines
    .map((line, rowIndex) => {
      const cells = line
        .map((value, columnIndex) => {
          if (value === null || value === '') return '';
          const ref = `${columnName(columnIndex)}${rowIndex + 1}`;
          // Euro amounts use the "€#,##0.00" number format (style 1).
          const money = rowIndex >= 4 && report.columns[columnIndex]?.money;
          return typeof value === 'number'
            ? `<c r="${ref}"${money ? ' s="1"' : ''}><v>${value}</v></c>`
            : `<c r="${ref}" t="inlineStr"><is><t>${xmlText(value)}</t></is></c>`;
        })
        .join('');
      return `<row r="${rowIndex + 1}">${cells}</row>`;
    })
    .join('');
  const widths = report.columns.map((column, index) => `<col min="${index + 1}" max="${index + 1}" width="${Math.max(12, column.width * 16)}" customWidth="1"/>`).join('');
  const header = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>';
  const zip = new JSZip();
  zip.file('[Content_Types].xml', `${header}<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>`);
  zip.file('_rels/.rels', `${header}<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>`);
  zip.file('xl/workbook.xml', `${header}<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="${xmlText(report.title.slice(0, 31))}" sheetId="1" r:id="rId1"/></sheets></workbook>`);
  zip.file('xl/_rels/workbook.xml.rels', `${header}<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>`);
  zip.file('xl/styles.xml', `${header}<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="1"><numFmt numFmtId="164" formatCode="&quot;€&quot;#,##0.00"/></numFmts><fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/></cellXfs></styleSheet>`);
  zip.file('xl/worksheets/sheet1.xml', `${header}<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><cols>${widths}</cols><sheetData>${rows}</sheetData></worksheet>`);
  return zip.generateAsync({ type: 'uint8array', compression: 'DEFLATE' });
}
