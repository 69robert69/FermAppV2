import { CROPS, type CropCode } from './crops';
import { ECONOMIC_CATEGORIES, ECONOMIC_LABELS, WORK_OPERATION_LABELS, type FarmEconomics, type FarmParcelEconomics } from './economics';
import { fieldStatus, FIELD_STATE_LABELS, type FieldTask } from './fieldStatus';
import { formatEuro } from './money';
import { TILLAGE_SYSTEMS, type TillageSystem } from './tillage';

/**
 * Report tables shared by the server and the phone, so a report has the same
 * columns, totals, chart and summary whichever backend produced it.
 */

export interface ReportColumn {
  key: string;
  label: string;
  /** Relative width in PDF tables. */
  width: number;
  numeric?: boolean;
  /** An amount in euro. */
  money?: boolean;
}

export interface ReportChart {
  title: string;
  /** Columns the bars read from each row... */
  labelKey?: string;
  valueKey?: string;
  /** ...or points given directly, when the bars are not rows of the table. */
  points?: { label: string; value: number }[];
  money?: boolean;
  unit?: string;
}

export interface ReportTable {
  title: string;
  period: string;
  columns: ReportColumn[];
  rows: Record<string, string | number | null>[];
  totals?: Record<string, string | number>;
  /** Wide tables print in landscape; the user may still choose. */
  orientation?: 'portrait' | 'landscape';
  chart?: ReportChart;
  /** Headline figures printed above the table. */
  summary?: { label: string; value: string }[];
}

export interface ReportParcel {
  id: string;
  name: string;
  code: string;
  areaHa: number;
  locality: string | null;
  crop: CropCode | null;
  tillage: TillageSystem | null;
}

export interface ReportRun {
  parcelId: string;
  machineId: string | null;
  type: string;
  status: 'RUNNING' | 'PAUSED' | 'COMPLETED' | 'CANCELLED';
  areaHa: number;
  percent: number;
  hours: number;
  fuelL: number | null;
  startedAt: string;
}

export interface ReportMachine {
  id: string;
  name: string;
  type: string;
  operatorName: string | null;
}

export interface ReportSeason {
  parcelId: string;
  crop: CropCode;
  yieldT: number | null;
}

const round = (value: number, digits = 2) => Math.round(value * 10 ** digits) / 10 ** digits;
const sum = <T>(items: readonly T[], pick: (item: T) => number | null | undefined) => items.reduce((total, item) => total + (pick(item) ?? 0), 0);

const MACHINE_TYPES: Record<string, string> = {
  TRACTOR: 'Tractor',
  COMBINE: 'Combine',
  SPRAYER: 'Sprayer',
  SEEDER: 'Seed drill',
  DISC: 'Disc',
  CULTIVATOR: 'Cultivator',
  SUBSOILER: 'Subsoiler',
  TRAILER: 'Trailer',
};

const isActive = (run: ReportRun) => (run.status === 'RUNNING' || run.status === 'PAUSED') && run.percent < 100;

export function parcelsReport(input: { year: number; parcels: ReportParcel[]; tasks: (FieldTask & { parcelId: string | null })[]; runs: ReportRun[]; now?: Date }): ReportTable {
  const rows = input.parcels.map((parcel) => {
    const status = fieldStatus({
      tasks: input.tasks.filter((task) => task.parcelId === parcel.id),
      activeWork: input.runs.filter((run) => run.parcelId === parcel.id && isActive(run)).length,
      now: input.now,
    });
    return {
      parcel: parcel.name,
      code: parcel.code,
      area: round(parcel.areaHa),
      crop: parcel.crop ? CROPS[parcel.crop].name : '',
      tillage: parcel.tillage ? TILLAGE_SYSTEMS[parcel.tillage].badge : '',
      status: FIELD_STATE_LABELS[status.state],
      open: status.pending + status.later,
      overdue: status.overdue,
      completion: status.completionScore,
    };
  });
  const scored = rows.filter((row) => row.completion !== null);
  return {
    title: 'Parcels',
    period: `Year ${input.year}`,
    orientation: 'landscape',
    columns: [
      { key: 'parcel', label: 'Parcel', width: 1.4 },
      { key: 'code', label: 'Code', width: 0.7 },
      { key: 'area', label: 'ha', width: 0.6, numeric: true },
      { key: 'crop', label: 'Crop', width: 0.9 },
      { key: 'tillage', label: 'Tillage', width: 0.7 },
      { key: 'status', label: 'Status', width: 1.1 },
      { key: 'open', label: 'Open tasks', width: 0.7, numeric: true },
      { key: 'overdue', label: 'Overdue', width: 0.7, numeric: true },
      { key: 'completion', label: 'Completion %', width: 0.8, numeric: true },
    ],
    rows,
    totals: { area: round(sum(rows, (row) => row.area)), open: sum(rows, (row) => row.open), overdue: sum(rows, (row) => row.overdue) },
    chart: { title: 'Area by parcel', labelKey: 'parcel', valueKey: 'area', unit: 'ha' },
    summary: [
      { label: 'Parcels', value: String(rows.length) },
      { label: 'Total area', value: `${round(sum(rows, (row) => row.area), 1)} ha` },
      { label: 'Average completion', value: scored.length ? `${Math.round(sum(scored, (row) => row.completion) / scored.length)}%` : '-' },
      { label: 'With overdue work', value: String(rows.filter((row) => row.overdue > 0).length) },
    ],
  };
}

export function machinesReport(input: { year: number; machines: ReportMachine[]; runs: ReportRun[]; parcelName: (id: string) => string }): ReportTable {
  const rows = input.machines.map((machine) => {
    const runs = input.runs.filter((run) => run.machineId === machine.id && run.startedAt.startsWith(String(input.year)) && run.status !== 'CANCELLED');
    const active = runs.find(isActive);
    return {
      machine: machine.name,
      type: MACHINE_TYPES[machine.type] ?? machine.type,
      operator: machine.operatorName ?? '',
      operations: runs.length,
      hours: round(sum(runs, (run) => run.hours), 1),
      area: round(sum(runs, (run) => (run.areaHa * Math.min(100, run.percent)) / 100), 1),
      fuel: Math.round(sum(runs, (run) => run.fuelL)),
      status: active ? `Working, ${input.parcelName(active.parcelId)}` : 'Available',
    };
  });
  return {
    title: 'Machines',
    period: `Year ${input.year}`,
    orientation: 'landscape',
    columns: [
      { key: 'machine', label: 'Machine', width: 1.4 },
      { key: 'type', label: 'Type', width: 0.9 },
      { key: 'operator', label: 'Operator', width: 1.1 },
      { key: 'operations', label: 'Operations', width: 0.8, numeric: true },
      { key: 'hours', label: 'Hours', width: 0.7, numeric: true },
      { key: 'area', label: 'Area (ha)', width: 0.8, numeric: true },
      { key: 'fuel', label: 'Diesel (l)', width: 0.8, numeric: true },
      { key: 'status', label: 'Status', width: 1.3 },
    ],
    rows,
    totals: { operations: sum(rows, (row) => row.operations), hours: round(sum(rows, (row) => row.hours), 1), area: round(sum(rows, (row) => row.area), 1), fuel: sum(rows, (row) => row.fuel) },
    chart: { title: 'Working hours by machine', labelKey: 'machine', valueKey: 'hours', unit: 'h' },
    summary: [
      { label: 'Machines', value: String(rows.length) },
      { label: 'Working now', value: String(rows.filter((row) => row.status !== 'Available').length) },
      { label: 'Hours worked', value: `${round(sum(rows, (row) => row.hours), 0)} h` },
      { label: 'Diesel', value: `${sum(rows, (row) => row.fuel)} l` },
    ],
  };
}

export function cropsReport(input: { year: number; parcels: ReportParcel[]; seasons: ReportSeason[] }): ReportTable {
  const groups = new Map<CropCode, { parcels: number; area: number; yieldT: number; harvestedArea: number }>();
  for (const season of input.seasons) {
    const parcel = input.parcels.find((item) => item.id === season.parcelId);
    if (!parcel) continue;
    const group = groups.get(season.crop) ?? { parcels: 0, area: 0, yieldT: 0, harvestedArea: 0 };
    group.parcels += 1;
    group.area += parcel.areaHa;
    if (season.yieldT != null) {
      group.yieldT += season.yieldT;
      group.harvestedArea += parcel.areaHa;
    }
    groups.set(season.crop, group);
  }
  const total = sum([...groups.values()], (group) => group.area);
  const rows = [...groups.entries()]
    .map(([crop, group]) => ({
      crop: CROPS[crop].name,
      parcels: group.parcels,
      area: round(group.area),
      share: total > 0 ? round((group.area / total) * 100, 1) : 0,
      yield: group.yieldT ? round(group.yieldT, 1) : null,
      perHa: group.harvestedArea ? round(group.yieldT / group.harvestedArea) : null,
    }))
    .sort((a, b) => b.area - a.area);
  return {
    title: 'Crops',
    period: `Year ${input.year}`,
    orientation: 'portrait',
    columns: [
      { key: 'crop', label: 'Crop', width: 1.3 },
      { key: 'parcels', label: 'Parcels', width: 0.7, numeric: true },
      { key: 'area', label: 'Area (ha)', width: 0.9, numeric: true },
      { key: 'share', label: 'Share (%)', width: 0.8, numeric: true },
      { key: 'yield', label: 'Yield (t)', width: 0.9, numeric: true },
      { key: 'perHa', label: 't/ha', width: 0.7, numeric: true },
    ],
    rows,
    totals: { parcels: sum(rows, (row) => row.parcels), area: round(total), yield: round(sum(rows, (row) => row.yield), 1) },
    chart: { title: 'Area by crop', labelKey: 'crop', valueKey: 'area', unit: 'ha' },
    summary: [
      { label: 'Crops', value: String(rows.length) },
      { label: 'Cultivated area', value: `${round(total, 1)} ha` },
      { label: 'Harvested', value: `${round(sum(rows, (row) => row.yield), 0)} t` },
    ],
  };
}

const best = (result: FarmParcelEconomics['result']) => result.actual ?? result.expected;

export function profitabilityReport(input: { year: number; parcels: FarmParcelEconomics[]; summary: FarmEconomics }): ReportTable {
  const rows = input.parcels
    .filter((item) => item.result.season.total > 0)
    .map((item) => {
      const outlook = best(item.result);
      return {
        parcel: item.name,
        crop: item.cropName ?? '',
        area: round(item.result.areaHa),
        costPerHa: item.result.season.perHa,
        revenuePerHa: outlook?.revenuePerHa ?? null,
        profitPerHa: outlook?.profitPerHa ?? null,
        margin: outlook?.marginPct ?? null,
        profit: outlook?.profit ?? null,
        basis: item.result.actual ? 'Actual' : item.result.expected ? 'Expected' : '',
      };
    });
  return {
    title: 'Profitability',
    period: `Year ${input.year}, in euro`,
    orientation: 'landscape',
    columns: [
      { key: 'parcel', label: 'Parcel', width: 1.3 },
      { key: 'crop', label: 'Crop', width: 0.9 },
      { key: 'area', label: 'ha', width: 0.6, numeric: true },
      { key: 'costPerHa', label: 'Cost/ha', width: 0.9, numeric: true, money: true },
      { key: 'revenuePerHa', label: 'Revenue/ha', width: 0.9, numeric: true, money: true },
      { key: 'profitPerHa', label: 'Profit/ha', width: 0.9, numeric: true, money: true },
      { key: 'margin', label: 'Margin %', width: 0.7, numeric: true },
      { key: 'profit', label: 'Profit', width: 1, numeric: true, money: true },
      { key: 'basis', label: 'Basis', width: 0.7 },
    ],
    rows,
    totals: { area: round(sum(rows, (row) => row.area)), profit: round(sum(rows, (row) => row.profit)) },
    chart: { title: 'Profit by parcel', labelKey: 'parcel', valueKey: 'profit', money: true },
    summary: [
      { label: 'Average cost', value: `${formatEuro(input.summary.costPerHa, 0)}/ha` },
      { label: 'Average profit', value: input.summary.profitPerHa == null ? '-' : `${formatEuro(input.summary.profitPerHa, 0)}/ha` },
      { label: 'Best field', value: input.summary.mostProfitableParcel?.label ?? '-' },
      { label: 'Best crop', value: input.summary.mostProfitableCrop?.label ?? '-' },
    ],
  };
}

export function farmReport(input: {
  year: number;
  farmName: string;
  parcels: ReportParcel[];
  runs: ReportRun[];
  tasks: (FieldTask & { parcelId: string | null })[];
  economics: FarmEconomics | null;
  now?: Date;
}): ReportTable {
  const yearRuns = input.runs.filter((run) => run.startedAt.startsWith(String(input.year)) && run.status !== 'CANCELLED');
  const area = sum(input.parcels, (parcel) => parcel.areaHa);
  const cultivated = sum(input.parcels.filter((parcel) => parcel.crop), (parcel) => parcel.areaHa);
  const statuses = input.parcels.map((parcel) =>
    fieldStatus({ tasks: input.tasks.filter((task) => task.parcelId === parcel.id), activeWork: input.runs.filter((run) => run.parcelId === parcel.id && isActive(run)).length, now: input.now }),
  );
  const scored = statuses.filter((status) => status.completionScore !== null);
  const byType = new Map<string, number>();
  for (const run of yearRuns) byType.set(run.type, (byType.get(run.type) ?? 0) + (run.areaHa * Math.min(100, run.percent)) / 100);
  const economics = input.economics;
  const rows: Record<string, string | number | null>[] = [
    { metric: 'Parcels', value: String(input.parcels.length) },
    { metric: 'Total area', value: `${round(area, 1)} ha` },
    { metric: 'Cultivated area', value: `${round(cultivated, 1)} ha` },
    { metric: 'Operations carried out', value: String(yearRuns.length) },
    { metric: 'Area worked', value: `${round(sum(yearRuns, (run) => (run.areaHa * Math.min(100, run.percent)) / 100), 1)} ha` },
    { metric: 'Diesel used', value: `${Math.round(sum(yearRuns, (run) => run.fuelL))} l` },
    { metric: 'Parcels fully completed', value: String(statuses.filter((status) => status.state === 'COMPLETE').length) },
    { metric: 'Parcels with overdue work', value: String(statuses.filter((status) => status.state === 'OVERDUE').length) },
    { metric: 'Average field completion', value: scored.length ? `${Math.round(sum(scored, (status) => status.completionScore) / scored.length)}%` : '-' },
    ...[...byType.entries()].map(([type, worked]) => ({ metric: `${WORK_OPERATION_LABELS[type] ?? type}`, value: `${round(worked, 1)} ha` })),
    ...(economics
      ? [
          { metric: 'Production cost', value: formatEuro(economics.cost, 0) },
          { metric: 'Cost per hectare', value: formatEuro(economics.costPerHa, 2) },
          { metric: 'Profit', value: formatEuro(economics.actualProfit ?? economics.expectedProfit, 0) },
          ...ECONOMIC_CATEGORIES.filter((category) => economics.byCategory[category] > 0).map((category) => ({ metric: `Cost: ${ECONOMIC_LABELS[category]}`, value: formatEuro(economics.byCategory[category], 0) })),
        ]
      : []),
  ];
  const categoryRows = economics ? ECONOMIC_CATEGORIES.filter((category) => economics.byCategory[category] > 0) : [];
  return {
    title: `Farm overview, ${input.farmName}`,
    period: `Year ${input.year}`,
    orientation: 'portrait',
    columns: [
      { key: 'metric', label: 'Indicator', width: 2 },
      { key: 'value', label: 'Value', width: 1.2, numeric: true },
    ],
    rows,
    ...(economics && categoryRows.length
      ? {
          chart: {
            title: 'Production cost by category',
            money: true,
            points: categoryRows.map((category) => ({ label: ECONOMIC_LABELS[category], value: economics.byCategory[category] })),
          },
        }
      : {}),
    summary: [
      { label: 'Area', value: `${round(area, 1)} ha` },
      { label: 'Operations', value: String(yearRuns.length) },
      ...(economics ? [{ label: 'Cost per hectare', value: formatEuro(economics.costPerHa, 0) }] : []),
      { label: 'Field completion', value: scored.length ? `${Math.round(sum(scored, (status) => status.completionScore) / scored.length)}%` : '-' },
    ],
  };
}

/** The chart points of a report, largest first, capped for legibility. */
export function reportChartPoints(report: ReportTable, max = 12): { label: string; value: number }[] {
  const chart = report.chart;
  if (!chart) return [];
  const points =
    chart.points ??
    (chart.labelKey && chart.valueKey ? report.rows.map((row) => ({ label: String(row[chart.labelKey!] ?? ''), value: Number(row[chart.valueKey!]) })) : []);
  return points
    .filter((point) => Number.isFinite(point.value) && point.value !== 0)
    .sort((a, b) => Math.abs(b.value) - Math.abs(a.value))
    .slice(0, max);
}
