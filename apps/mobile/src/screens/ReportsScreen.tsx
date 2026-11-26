import { formatEuro, reportChartPoints } from '@fermapp/domain';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Alert, ScrollView, View } from 'react-native';
import { Button } from 'react-native-paper';
import { apiGet } from '../api/client';
import type { ReportData } from '../api/types';
import { Choice, Empty, IconButton, Panel, QueryState, Screen, Txt } from '../components/ui';
import { exportExcel, reportPdfFile, sharePdf } from '../features/reports/exportReport';
import { BarList } from '../features/tillage/charts';
import { errorMessage } from '../lib/hooks';
import { useAuth } from '../store/auth';
import { colors, fonts, space } from '../theme';

const REPORTS = [
  { value: 'costs', label: 'Economic', financial: true },
  { value: 'profitability', label: 'Profitability', financial: true },
  { value: 'farm', label: 'Farm', financial: true },
  { value: 'parcels', label: 'Parcels' },
  { value: 'work', label: 'Field work' },
  { value: 'machines', label: 'Machines' },
  { value: 'crops', label: 'Crops' },
  { value: 'yields', label: 'Yields' },
  { value: 'fuel', label: 'Diesel' },
  { value: 'rotation', label: 'Rotation' },
  { value: 'irrigation', label: 'Irrigation' },
];

type OrientationChoice = 'auto' | 'portrait' | 'landscape';
const COLUMN_WIDTH = 116;

export function ReportsScreen() {
  const role = useAuth((state) => state.role);
  const farmName = useAuth((state) => state.farm?.name ?? 'FermApp');
  // Reports with money in them are hidden from operators, as on the server.
  const options = REPORTS.filter((item) => role !== 'OPERATOR' || !item.financial).map(({ value, label }) => ({ value, label }));
  const [type, setType] = useState(options[0]!.value);
  const [year, setYear] = useState(new Date().getFullYear());
  const [orientation, setOrientation] = useState<OrientationChoice>('auto');
  const [exporting, setExporting] = useState<'pdf' | 'xlsx' | null>(null);
  const report = useQuery({
    queryKey: ['report', type, year],
    queryFn: () => apiGet<ReportData>(`/reports/${type}`, { year }),
  });

  async function exportPdf() {
    if (!report.data) return;
    setExporting('pdf');
    try {
      const uri = await reportPdfFile(report.data, {
        farmName,
        orientation: orientation === 'auto' ? undefined : orientation,
        filename: `report-${type}-${year}.pdf`,
      });
      await sharePdf(uri);
    } catch (error) {
      Alert.alert('The PDF was not created', errorMessage(error));
    } finally {
      setExporting(null);
    }
  }

  async function exportXlsx() {
    setExporting('xlsx');
    try {
      await exportExcel(`/reports/${type}?year=${year}&format=xlsx`, `report-${type}-${year}.xlsx`);
    } catch (error) {
      Alert.alert('The Excel file was not created', errorMessage(error));
    } finally {
      setExporting(null);
    }
  }

  return (
    <Screen title="Reports" onRefresh={() => void report.refetch()} refreshing={report.isRefetching}>
      <Choice options={options} value={type} onChange={setType} />
      <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: space.lg }}>
        <IconButton icon="chevron-left" label="Previous year" onPress={() => setYear(year - 1)} />
        <Txt variant="figure">{String(year)}</Txt>
        <IconButton icon="chevron-right" label="Next year" onPress={() => setYear(year + 1)} />
      </View>
      <Choice
        label="PDF page"
        options={[
          { value: 'auto', label: 'Automatic' },
          { value: 'portrait', label: 'A4 portrait' },
          { value: 'landscape', label: 'A4 landscape' },
        ]}
        value={orientation}
        onChange={setOrientation}
      />
      <View style={{ flexDirection: 'row', gap: space.sm }}>
        <Button style={{ flex: 1 }} mode="outlined" icon="file-excel-outline" loading={exporting === 'xlsx'} disabled={Boolean(exporting)} onPress={() => void exportXlsx()}>
          Export Excel
        </Button>
        <Button style={{ flex: 1 }} mode="contained" icon="file-pdf-box" loading={exporting === 'pdf'} disabled={Boolean(exporting) || !report.data} onPress={() => void exportPdf()}>
          Export PDF
        </Button>
      </View>
      <QueryState query={report}>
        {(data) => {
          const points = reportChartPoints(data);
          return data.rows.length === 0 ? (
            <Empty icon="file-search-outline" title="There is no data for this period" hint="Choose another year or report type." />
          ) : (
            <>
              <Panel>
                <Txt variant="title">{data.title}</Txt>
                <Txt variant="small">{data.period}</Txt>
                {data.summary?.length ? (
                  <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: space.sm }}>
                    {data.summary.map((item) => (
                      <View key={item.label} style={{ width: '48%', padding: space.sm, borderRadius: 8, backgroundColor: colors.ridge, borderLeftWidth: 3, borderLeftColor: colors.wheat }}>
                        <Txt variant="small">{item.label}</Txt>
                        <Txt color={colors.chaff} style={{ fontFamily: fonts.semibold }} numberOfLines={1}>{item.value}</Txt>
                      </View>
                    ))}
                  </View>
                ) : null}
              </Panel>
              {data.chart && points.length ? (
                <Panel>
                  <Txt variant="subtitle">{data.chart.title}</Txt>
                  <BarList
                    format={(value) => (data.chart!.money ? formatEuro(value, 0) : `${value.toLocaleString('en-GB', { maximumFractionDigits: 1 })}${data.chart!.unit ? ` ${data.chart!.unit}` : ''}`)}
                    data={points.map((point) => ({ key: point.label, label: point.label, color: point.value < 0 ? colors.rust : colors.wheat, value: Math.abs(point.value) }))}
                  />
                </Panel>
              ) : null}
              <Panel>
                <ScrollView horizontal>
                  <View>
                    <View style={{ flexDirection: 'row', borderBottomWidth: 1, borderBottomColor: colors.line, paddingBottom: space.xs }}>
                      {data.columns.map((column) => (
                        <Txt key={column.key} variant="small" style={{ width: COLUMN_WIDTH, paddingRight: space.sm, textAlign: column.numeric ? 'right' : 'left' }}>
                          {column.label}
                        </Txt>
                      ))}
                    </View>
                    {data.rows.slice(0, 100).map((row, index) => (
                      <View key={index} style={{ flexDirection: 'row', paddingVertical: space.xs }}>
                        {data.columns.map((column) => (
                          <Txt key={column.key} numberOfLines={2} style={{ width: COLUMN_WIDTH, paddingRight: space.sm, fontSize: 13, textAlign: column.numeric ? 'right' : 'left' }}>
                            {column.money && typeof row[column.key] === 'number' ? formatEuro(row[column.key] as number, 2) : (row[column.key] ?? '')}
                          </Txt>
                        ))}
                      </View>
                    ))}
                  </View>
                </ScrollView>
              </Panel>
            </>
          );
        }}
      </QueryState>
    </Screen>
  );
}
