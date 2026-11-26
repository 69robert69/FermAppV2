import { useQuery } from '@tanstack/react-query';
import { View } from 'react-native';
import { Button } from 'react-native-paper';
import { Panel, Section, Txt } from '../../components/ui';
import { shortDate } from '../../lib/format';
import { colors, space } from '../../theme';
import { CircularProgress } from '../intelligence/CircularProgress';
import { readSatelliteSummary } from './cache';
import { useSatelliteSettings } from './prefs';

const SEVERITY = { INFO: colors.water, WARNING: colors.amber, CRITICAL: colors.rust } as const;

/** The field's latest satellite reading, from the phone's cache: opening a parcel costs no request. */
export function SatelliteSummarySection({ parcelId, onOpen }: { parcelId: string; onOpen: () => void }) {
  const settings = useSatelliteSettings();
  const summary = useQuery({ queryKey: ['satellite-summary', parcelId], queryFn: () => readSatelliteSummary(parcelId), enabled: settings.active });
  if (!settings.active) return null;
  const stored = summary.data?.summary;
  const top = stored?.findings.find((finding) => finding.severity !== 'INFO') ?? stored?.findings[0];
  return (
    <Section title="Satellite Monitoring">
      <Panel>
        {stored ? (
          <View style={{ flexDirection: 'row', gap: space.lg, alignItems: 'center' }}>
            {stored.healthScore != null ? <CircularProgress percent={stored.healthScore} size={64} color={stored.healthScore >= 70 ? colors.leaf : stored.healthScore >= 40 ? colors.wheat : colors.rust} icon="satellite-variant" /> : null}
            <View style={{ flex: 1, gap: 2 }}>
              <Txt variant="subtitle">{stored.healthScore != null ? `Field health ${stored.healthScore}/100 · ${stored.healthLabel}` : stored.healthLabel}</Txt>
              <Txt variant="small">{`NDVI ${stored.ndvi.toFixed(2)} on ${shortDate(stored.date)}${stored.change7d != null ? ` · ${stored.change7d >= 0 ? '+' : ''}${stored.change7d.toFixed(2)} in a week` : ''}`}</Txt>
              {top ? <Txt variant="small" color={SEVERITY[top.severity]}>{top.message}</Txt> : null}
            </View>
          </View>
        ) : (
          <Txt variant="small">See crop health, NDVI, moisture and problem zones from free Sentinel-2 images.</Txt>
        )}
        <Button mode="outlined" icon="satellite-variant" onPress={onOpen}>{stored ? 'Open satellite monitoring' : 'Analyse with satellite'}</Button>
      </Panel>
    </Section>
  );
}
