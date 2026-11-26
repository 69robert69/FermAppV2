import { formatEuro, reportChartPoints, type ReportTable } from '@fermapp/domain';

/**
 * A report as a print-ready A4 document: header with the FermApp mark, farm and
 * generation date, headline figures, a bar chart, the table split into pages
 * with its header repeated, totals, and "Page X of Y" on every page.
 *
 * Pages are laid out here rather than by the print engine, because Android and
 * iOS print views do not number pages; cells stay on one line so every row has
 * the same height and the split is exact.
 */

export type Orientation = 'portrait' | 'landscape';

const PAGE = { portrait: { width: 595, height: 842 }, landscape: { width: 842, height: 595 } } as const;
const MARGIN = 28;
const HEADER = 54;
const FOOTER = 26;
const ROW = 17;
const TITLE_BLOCK = 46;
const SUMMARY_BLOCK = 58;
const CHART_ROW = 15;
const CHART_HEAD = 26;

/** App palette, light enough for paper. */
const INK = '#22261D';
const MUTED = '#5B6150';
const BAND = '#181B13';
const WHEAT = '#E2B23D';
const RULE = '#D9D4BE';
const HEAD_FILL = '#E7E2CC';
const TOTAL_FILL = '#F2EEDD';
const BAR = '#C99A2E';
const BAR_NEGATIVE = '#D93C74';

export const escapeHtml = (value: unknown) =>
  String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;');

const cellText = (column: ReportTable['columns'][number], value: unknown) =>
  value == null || value === '' ? '' : column.money && typeof value === 'number' ? formatEuro(value, 2) : typeof value === 'number' ? value.toLocaleString('en-GB', { maximumFractionDigits: 2 }) : String(value);

/** The FermApp mark: a wheat-coloured disc with a field and a sprout. */
const LOGO = `<svg width="30" height="30" viewBox="0 0 30 30" xmlns="http://www.w3.org/2000/svg">
  <circle cx="15" cy="15" r="14" fill="${WHEAT}"/>
  <path d="M5 21 Q15 16 25 21" stroke="${BAND}" stroke-width="1.6" fill="none"/>
  <path d="M5 24 Q15 19.5 25 24" stroke="${BAND}" stroke-width="1.6" fill="none"/>
  <path d="M15 18 V9" stroke="${BAND}" stroke-width="1.8" stroke-linecap="round"/>
  <path d="M15 12 C11 11 10 8 10 7 C13 7 15 9 15 12 Z" fill="${BAND}"/>
  <path d="M15 11 C19 10 20 7 20 6 C17 6 15 8 15 11 Z" fill="${BAND}"/>
</svg>`;

function chartSvg(report: ReportTable, width: number) {
  const points = reportChartPoints(report);
  if (!report.chart || points.length === 0) return { html: '', height: 0 };
  const labelWidth = Math.min(170, width * 0.3);
  const valueWidth = 84;
  const barSpace = width - labelWidth - valueWidth - 10;
  const max = Math.max(...points.map((point) => Math.abs(point.value)));
  const format = (value: number) =>
    report.chart!.money ? formatEuro(value, 0) : `${value.toLocaleString('en-GB', { maximumFractionDigits: 1 })}${report.chart!.unit ? ` ${report.chart!.unit}` : ''}`;
  const bars = points
    .map((point, index) => {
      const y = index * CHART_ROW;
      const barWidth = max > 0 ? Math.max(2, (Math.abs(point.value) / max) * barSpace) : 2;
      return `<text x="0" y="${y + 10}" font-size="8.5" fill="${INK}">${escapeHtml(point.label.length > 32 ? `${point.label.slice(0, 31)}…` : point.label)}</text>
        <rect x="${labelWidth}" y="${y + 2}" width="${barWidth.toFixed(1)}" height="9" rx="2" fill="${point.value < 0 ? BAR_NEGATIVE : BAR}"/>
        <text x="${width}" y="${y + 10}" font-size="8.5" fill="${INK}" text-anchor="end">${escapeHtml(format(point.value))}</text>`;
    })
    .join('');
  const height = points.length * CHART_ROW;
  return {
    html: `<div class="chart-title">${escapeHtml(report.chart.title)}</div><svg width="${width}" height="${height}" xmlns="http://www.w3.org/2000/svg" font-family="Helvetica, Arial, sans-serif">${bars}</svg>`,
    height: CHART_HEAD + height,
  };
}

export function reportToHtml(report: ReportTable, options: { farmName: string; orientation?: Orientation; generatedAt?: Date }) {
  const orientation = options.orientation ?? report.orientation ?? 'landscape';
  const page = PAGE[orientation];
  const contentWidth = page.width - MARGIN * 2;
  const bodyHeight = page.height - MARGIN * 2 - HEADER - FOOTER;
  const generated = (options.generatedAt ?? new Date()).toLocaleString('en-GB', { day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });

  const chart = chartSvg(report, contentWidth);
  const summaryHeight = report.summary?.length ? SUMMARY_BLOCK : 0;
  const tableHead = ROW + 4;
  const hasTotals = Boolean(report.totals && report.rows.length);

  // Rows that fit: the first page also carries the title, figures and chart.
  const firstCapacity = Math.max(3, Math.floor((bodyHeight - TITLE_BLOCK - summaryHeight - chart.height - tableHead) / ROW));
  const otherCapacity = Math.floor((bodyHeight - tableHead) / ROW);
  const pages: (typeof report.rows)[] = [];
  let index = 0;
  pages.push(report.rows.slice(0, firstCapacity));
  index = firstCapacity;
  while (index < report.rows.length) {
    pages.push(report.rows.slice(index, index + otherCapacity));
    index += otherCapacity;
  }
  // Keep room for the totals row on the last page.
  const last = pages[pages.length - 1]!;
  const lastCapacity = pages.length === 1 ? firstCapacity : otherCapacity;
  if (hasTotals && last.length >= lastCapacity && last.length > 0) pages.push([last.pop()!]);

  const totalWeight = report.columns.reduce((sum, column) => sum + column.width, 0);
  const colgroup = `<colgroup>${report.columns.map((column) => `<col style="width:${((column.width / totalWeight) * 100).toFixed(2)}%">`).join('')}</colgroup>`;
  const head = `<thead><tr>${report.columns.map((column) => `<th class="${column.numeric ? 'n' : ''}">${escapeHtml(column.label)}</th>`).join('')}</tr></thead>`;
  const row = (values: Record<string, unknown>, className = '') =>
    `<tr class="${className}">${report.columns.map((column) => `<td class="${column.numeric ? 'n' : ''}">${escapeHtml(cellText(column, values[column.key]))}</td>`).join('')}</tr>`;

  const body = pages
    .map((rows, pageIndex) => {
      const first = pageIndex === 0;
      const isLast = pageIndex === pages.length - 1;
      const totals = isLast && hasTotals ? row({ ...report.totals, [report.columns[0]!.key]: 'Total' }, 'total') : '';
      const intro = first
        ? `<h1>${escapeHtml(report.title)}</h1><div class="period">${escapeHtml(report.period)}</div>
           ${report.summary?.length ? `<div class="summary">${report.summary.map((item) => `<div class="kpi"><span>${escapeHtml(item.label)}</span><b>${escapeHtml(item.value)}</b></div>`).join('')}</div>` : ''}
           ${chart.html}`
        : '';
      const table = rows.length || totals ? `<table>${colgroup}${head}<tbody>${rows.map((values) => row(values)).join('')}${totals}</tbody></table>` : '';
      const empty = first && report.rows.length === 0 ? '<p class="empty">There is no data for the selected period.</p>' : '';
      return `<section class="page">
        <header><div class="brand">${LOGO}<div><b>FermApp</b><span>${escapeHtml(options.farmName)}</span></div></div><div class="generated">Generated ${escapeHtml(generated)}</div></header>
        <main>${intro}${table}${empty}</main>
        <footer><span>${escapeHtml(report.title)}, ${escapeHtml(report.period)}</span><span>Page ${pageIndex + 1} of ${pages.length}</span></footer>
      </section>`;
    })
    .join('');

  const style = `
    @page { size: A4 ${orientation}; margin: 0; }
    * { box-sizing: border-box; }
    html, body { margin: 0; padding: 0; }
    body { font-family: Helvetica, Arial, sans-serif; color: ${INK}; font-size: 9px; -webkit-print-color-adjust: exact; print-color-adjust: exact; }
    .page { width: ${page.width}px; height: ${page.height}px; padding: ${MARGIN}px; position: relative; overflow: hidden; page-break-after: always; break-after: page; }
    .page:last-child { page-break-after: auto; break-after: auto; }
    header { height: ${HEADER - 8}px; margin: -${MARGIN}px -${MARGIN}px 8px; padding: 0 ${MARGIN}px; background: ${BAND}; display: flex; align-items: center; justify-content: space-between; }
    .brand { display: flex; align-items: center; gap: 9px; }
    .brand b { display: block; color: #F0ECDB; font-size: 14px; letter-spacing: 0.3px; }
    .brand span, .generated { color: #ACAE96; font-size: 8px; }
    main { height: ${bodyHeight}px; overflow: hidden; }
    h1 { font-size: 17px; margin: 4px 0 2px; }
    .period { color: ${MUTED}; font-size: 9px; margin-bottom: 10px; }
    .summary { display: flex; gap: 8px; margin-bottom: 12px; }
    .kpi { flex: 1; background: ${TOTAL_FILL}; border-radius: 4px; padding: 7px 9px; border-left: 3px solid ${WHEAT}; }
    .kpi span { display: block; color: ${MUTED}; font-size: 7.5px; }
    .kpi b { display: block; font-size: 12px; margin-top: 3px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .chart-title { font-weight: bold; font-size: 10px; margin: 2px 0 8px; }
    svg { display: block; margin-bottom: 10px; }
    table { width: 100%; border-collapse: collapse; table-layout: fixed; }
    th { background: ${HEAD_FILL}; text-align: left; font-size: 8px; height: ${ROW + 4}px; padding: 0 5px; }
    td { height: ${ROW}px; padding: 0 5px; border-bottom: 0.6px solid ${RULE}; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    tr:nth-child(even) td { background: #FAF8F0; }
    .n { text-align: right; font-variant-numeric: tabular-nums; }
    tr.total td { background: ${TOTAL_FILL}; font-weight: bold; border-bottom: none; }
    .empty { color: ${MUTED}; font-size: 11px; margin-top: 16px; }
    footer { position: absolute; left: ${MARGIN}px; right: ${MARGIN}px; bottom: ${MARGIN - 10}px; height: ${FOOTER - 8}px; display: flex; justify-content: space-between; align-items: flex-end; color: ${MUTED}; font-size: 7.5px; border-top: 0.6px solid ${RULE}; padding-top: 4px; }
  `;
  return { html: `<html><head><meta charset="utf-8"><style>${style}</style></head><body>${body}</body></html>`, orientation, pages: pages.length };
}
