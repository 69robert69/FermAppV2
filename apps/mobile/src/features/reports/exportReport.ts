import type { ReportTable } from '@fermapp/domain';
import { File, Paths } from 'expo-file-system';
import { printToFileAsync } from 'expo-print';
import * as Sharing from 'expo-sharing';
import { apiDownload } from '../../api/client';
import { reportToHtml, type Orientation } from './pdfReport';

const PAGE = { portrait: { width: 595, height: 842 }, landscape: { width: 842, height: 595 } } as const;

/**
 * Prints a report to an A4 PDF on the phone, from the same data whichever
 * backend produced it. The PDF is returned as base64 and written to the cache,
 * the same way as the Excel export.
 */
export async function reportPdfFile(report: ReportTable, options: { farmName: string; orientation?: Orientation; filename: string }) {
  const { html, orientation } = reportToHtml(report, options);
  const { base64 } = await printToFileAsync({ html, base64: true, ...PAGE[orientation] });
  if (!base64) throw new Error('The PDF could not be generated.');
  const file = new File(Paths.cache, options.filename);
  if (file.exists) file.delete();
  file.create();
  file.write(base64, { encoding: 'base64' });
  return file.uri;
}

export async function sharePdf(uri: string) {
  await Sharing.shareAsync(uri, { mimeType: 'application/pdf', UTI: 'com.adobe.pdf', dialogTitle: 'Share the report' });
}

export async function exportExcel(path: string, filename: string) {
  const uri = await apiDownload(path, filename);
  await Sharing.shareAsync(uri, {
    mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    UTI: 'org.openxmlformats.spreadsheetml.sheet',
    dialogTitle: 'Share the report',
  });
}
