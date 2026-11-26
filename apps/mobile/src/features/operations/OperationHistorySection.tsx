import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useQuery } from '@tanstack/react-query';
import { StyleSheet, View } from 'react-native';
import { apiGet } from '../../api/client';
import type { WorkSession } from '../../api/types';
import { Panel, Section, Txt } from '../../components/ui';
import { fullDate, hoursMinutes, number, WORK_TYPES } from '../../lib/format';
import { colors, space } from '../../theme';
import { IMPLEMENT_COLOR } from './TractorGlyph';

const HISTORY_LIMIT = 8;

/** Operations finished on the parcel, newest first, with the date they were carried out. */
export function OperationHistorySection({ parcelId }: { parcelId: string }) {
  const work = useQuery({ queryKey: ['work'], queryFn: () => apiGet<WorkSession[]>('/work') });
  const done = (work.data ?? [])
    .filter((item) => item.parcel.id === parcelId && (item.status === 'COMPLETED' || item.status === 'CANCELLED'))
    .sort((a, b) => b.startedAt.localeCompare(a.startedAt))
    .slice(0, HISTORY_LIMIT);

  return (
    <Section title="Operation history">
      <Panel>
        {done.length === 0 ? <Txt variant="small">No finished operations on this parcel yet.</Txt> : null}
        {done.map((item) => {
          const completed = item.status === 'COMPLETED';
          const finishedAt = new Date(new Date(item.startedAt).getTime() + (item.progress.elapsedSeconds + item.pausedMs / 1000) * 1000);
          return (
            <View key={item.id} style={styles.row}>
              <View style={[styles.mark, { backgroundColor: IMPLEMENT_COLOR[item.type] ?? colors.leaf }]} />
              <View style={{ flex: 1 }}>
                <Txt>{`${WORK_TYPES[item.type] ?? item.type}${completed ? '' : ', cancelled'}`}</Txt>
                <Txt variant="small">
                  {[fullDate(completed ? finishedAt : item.startedAt), item.machine?.name, `${number((item.areaHa * item.progressPct) / 100)} ha`, hoursMinutes(item.progress.elapsedSeconds)]
                    .filter(Boolean)
                    .join(', ')}
                </Txt>
              </View>
              <MaterialCommunityIcons
                name={completed ? 'check-circle' : 'close-circle-outline'}
                size={20}
                color={completed ? colors.leaf : colors.straw}
                accessibilityLabel={completed ? 'Completed' : 'Cancelled'}
              />
            </View>
          );
        })}
      </Panel>
    </Section>
  );
}

const styles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', gap: space.md, minHeight: 44 },
  mark: { width: 4, alignSelf: 'stretch', borderRadius: 2 },
});
