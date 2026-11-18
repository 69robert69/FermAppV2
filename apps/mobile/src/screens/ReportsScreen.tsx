import { useQuery } from '@tanstack/react-query';
import * as Sharing from 'expo-sharing';
import { useState } from 'react';
import { Alert, ScrollView, View } from 'react-native';
import { Button } from 'react-native-paper';
import { apiDownload, apiGet } from '../api/client';
import type { ReportData } from '../api/types';
import { Choice, Empty, IconButton, Panel, QueryState, Screen, Txt } from '../components/ui';
import { errorMessage } from '../lib/hooks';
import { useAuth } from '../store/auth';
import { colors, space } from '../theme';

const REPORTS = [
  { value: 'work', label: 'Lucrări' },
  { value: 'fuel', label: 'Motorină' },
  { value: 'costs', label: 'Costuri' },
  { value: 'yields', label: 'Producții' },
  { value: 'rotation', label: 'Rotație' },
  { value: 'irrigation', label: 'Irigații' },
];

const COLUMN_WIDTH = 116;

export function ReportsScreen() {
  const role = useAuth((state) => state.role);
  const options = role === 'OPERATOR' ? REPORTS.filter((item) => item.value !== 'costs') : REPORTS;
  const [type, setType] = useState('work');
  const [year, setYear] = useState(new Date().getFullYear());
  const [exporting, setExporting] = useState<string | null>(null);
  const report = useQuery({
    queryKey: ['report', type, year],
    queryFn: () => apiGet<ReportData>(`/reports/${type}`, { year }),
  });

  async function exportReport(format: 'pdf' | 'xlsx') {
    setExporting(format);
    try {
      const uri = await apiDownload(`/reports/${type}?year=${year}&format=${format}`, `raport-${type}-${year}.${format}`);
      await Sharing.shareAsync(uri);
    } catch (error) {
      Alert.alert('Exportul nu a reușit', errorMessage(error));
    } finally {
      setExporting(null);
    }
  }

  return (
    <Screen title="Rapoarte" onRefresh={() => void report.refetch()} refreshing={report.isRefetching}>
      <Choice options={options} value={type} onChange={setType} />
      <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: space.lg }}>
        <IconButton icon="chevron-left" label="Anul anterior" onPress={() => setYear(year - 1)} />
        <Txt variant="figure">{String(year)}</Txt>
        <IconButton icon="chevron-right" label="Anul următor" onPress={() => setYear(year + 1)} />
      </View>
      <View style={{ flexDirection: 'row', gap: space.sm }}>
        <Button style={{ flex: 1 }} mode="contained" icon="file-pdf-box" loading={exporting === 'pdf'} disabled={Boolean(exporting)} onPress={() => void exportReport('pdf')}>
          Export PDF
        </Button>
        <Button style={{ flex: 1 }} mode="outlined" icon="file-excel-outline" loading={exporting === 'xlsx'} disabled={Boolean(exporting)} onPress={() => void exportReport('xlsx')}>
          Export Excel
        </Button>
      </View>
      <QueryState query={report}>
        {(data) =>
          data.rows.length === 0 ? (
            <Empty icon="file-search-outline" title="Nu există date pentru această perioadă" hint="Alege alt an sau alt tip de raport." />
          ) : (
            <Panel>
              <Txt variant="title">{data.title}</Txt>
              <Txt variant="small">{data.period}</Txt>
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
                          {row[column.key] ?? ''}
                        </Txt>
                      ))}
                    </View>
                  ))}
                </View>
              </ScrollView>
            </Panel>
          )
        }
      </QueryState>
    </Screen>
  );
}
