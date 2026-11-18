import { CROPS, evaluateRotation, summarizeCosts, type CostCategory } from '@fermapp/domain';
import { File, Paths } from 'expo-file-system';
import { printToFileAsync } from 'expo-print';
import { IRRIGATION_TYPES, WORK_TYPES } from '../lib/format';
import { liveItems } from './liveStore';
import { runProgress } from './services';
import type { CropHistoryDoc, ExpenseDoc, FarmDoc, IrrigationDoc, MachineDoc, MachineWorkDoc, ParcelDoc, UserProfile } from './types';

export const REPORT_TYPES = ['work', 'fuel', 'costs', 'yields', 'rotation', 'irrigation'] as const;
export type ReportType = (typeof REPORT_TYPES)[number];

type Cell = string | number | null;

interface Column {
  key: string;
  label: string;
  width: number;
  numeric?: boolean;
}

export interface Report {
  title: string;
  period: string;
  columns: Column[];
  rows: Record<string, Cell>[];
  totals?: Record<string, string | number>;
}

const STATUS = { RUNNING: 'În desfășurare', PAUSED: 'Pauză', COMPLETED: 'Finalizată', CANCELLED: 'Anulată' } as const;
const CATEGORY_COLUMNS: [CostCategory, string][] = [
  ['SEED', 'Semințe'],
  ['FERTILIZER', 'Îngrășăminte'],
  ['PESTICIDE', 'Pesticide'],
  ['FUEL', 'Combustibil'],
  ['IRRIGATION', 'Irigații'],
];

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
  };
}

export async function buildReport(type: ReportType, year: number): Promise<Report> {
  const data = await sources();
  const period = `${year}-01-01 - ${year}-12-31`;
  const parcelName = (id: string) => data.parcels.find((parcel) => parcel.id === id)?.name ?? '';

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
      title: 'Lucrări efectuate',
      period,
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

  if (type === 'fuel') {
    const byMachine = new Map<string, { machine: string; sessions: number; area: number; fuel: number }>();
    for (const item of data.work.filter((run) => run.status === 'COMPLETED' && inYear(run.finishDate ?? run.startDate, year))) {
      const machine = data.machines.find((candidate) => candidate.id === item.machineId);
      const name = machine?.name ?? 'Fără utilaj';
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
      title: 'Consum motorină',
      period,
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

  if (type === 'costs') {
    const expenses = (await liveItems<ExpenseDoc>('expenses')).filter((item) => item.year === year);
    const rows = data.parcels.flatMap((parcel) => {
      const costs = expenses.filter((item) => item.parcelId === parcel.id);
      if (costs.length === 0) return [];
      const season = data.seasons.find((item) => item.parcelId === parcel.id && item.year === year);
      const summary = summarizeCosts({
        areaHa: parcel.area,
        costs: costs.map(({ category, amount }) => ({ category, amount })),
        expectedYieldTHa: season?.expectedYieldTHa,
        expectedPricePerT: season?.expectedPricePerT,
        actualYieldT: season?.yieldT,
        actualPricePerT: season?.actualPricePerT,
      });
      return [{
        parcel: parcel.name,
        ...Object.fromEntries(CATEGORY_COLUMNS.map(([code]) => [code, summary.byCategory[code]])),
        total: summary.totalCost,
        perHa: summary.costPerHa,
        estimated: summary.estimatedProfit,
        actual: summary.actualProfit,
      }];
    });
    return {
      title: 'Costuri și profit',
      period: `Anul ${year}`,
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
      title: 'Producții',
      period: `Anul ${year}`,
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
      title: 'Rotația culturilor',
      period: `Anul ${year}`,
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
    title: 'Irigații',
    period,
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

/** Transformă un document HTML în PDF și îi dă numele cerut. */
export async function htmlToPdf(html: string, filename: string) {
  const { uri } = await printToFileAsync({ html: `<html><head><meta charset="utf-8"><style>${PAGE_STYLE}</style></head><body>${html}</body></html>` });
  const target = new File(Paths.cache, filename);
  if (target.exists) target.delete();
  new File(uri).move(target);
  return target.uri;
}

export function reportHtml(report: Report, farmName: string) {
  const cell = (column: Column, value: unknown) => `<td${column.numeric ? ' class="n"' : ''}>${escapeHtml(value)}</td>`;
  const head = report.columns.map((column) => `<th${column.numeric ? ' class="n"' : ''}>${escapeHtml(column.label)}</th>`).join('');
  const body = report.rows.map((row) => `<tr>${report.columns.map((column) => cell(column, row[column.key])).join('')}</tr>`).join('');
  const totals = report.totals && report.rows.length
    ? `<tfoot><tr>${report.columns.map((column, index) => cell(column, index === 0 ? 'Total' : report.totals![column.key])).join('')}</tr></tfoot>`
    : '';
  const empty = report.rows.length ? '' : '<p>Nu există date pentru perioada aleasă.</p>';
  return `<h1>${escapeHtml(report.title)}</h1><p>${escapeHtml(farmName)}, ${escapeHtml(report.period)}</p><table><thead><tr>${head}</tr></thead><tbody>${body}</tbody>${totals}</table>${empty}`;
}

const columnName = (index: number) => {
  let name = '';
  for (let n = index + 1; n > 0; n = Math.floor((n - 1) / 26)) name = String.fromCharCode(65 + ((n - 1) % 26)) + name;
  return name;
};

const xmlText = (value: unknown) => escapeHtml(value).replaceAll("'", '&apos;');

/** Generează un fișier Excel real (.xlsx). */
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
          return typeof value === 'number'
            ? `<c r="${ref}"><v>${value}</v></c>`
            : `<c r="${ref}" t="inlineStr"><is><t>${xmlText(value)}</t></is></c>`;
        })
        .join('');
      return `<row r="${rowIndex + 1}">${cells}</row>`;
    })
    .join('');
  const widths = report.columns.map((column, index) => `<col min="${index + 1}" max="${index + 1}" width="${Math.max(12, column.width * 16)}" customWidth="1"/>`).join('');
  const header = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>';
  const zip = new JSZip();
  zip.file('[Content_Types].xml', `${header}<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>`);
  zip.file('_rels/.rels', `${header}<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>`);
  zip.file('xl/workbook.xml', `${header}<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="${xmlText(report.title.slice(0, 31))}" sheetId="1" r:id="rId1"/></sheets></workbook>`);
  zip.file('xl/_rels/workbook.xml.rels', `${header}<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>`);
  zip.file('xl/worksheets/sheet1.xml', `${header}<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><cols>${widths}</cols><sheetData>${rows}</sheetData></worksheet>`);
  return zip.generateAsync({ type: 'uint8array', compression: 'DEFLATE' });
}
