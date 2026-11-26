import { economicInsights, formatEuro } from '@fermapp/domain';
import { View } from 'react-native';
import { Button } from 'react-native-paper';
import { Panel, Section, Stat, Txt } from '../../components/ui';
import { colors, space } from '../../theme';
import { useTillageSettings } from '../tillage/prefs';
import { useFarmEconomics } from './api';
import { CostBreakdown, InsightList } from './components';

/** The parcel's real cost per hectare and profit, in the parcel panel on the map. */
export function EconomicSummarySection({ parcelId, onOpen }: { parcelId: string; onOpen: () => void }) {
  const year = new Date().getFullYear();
  const economics = useFarmEconomics(year);
  const tillageSettings = useTillageSettings();
  const item = economics.data?.parcels.find((entry) => entry.parcelId === parcelId);
  if (!item) return null;
  const { season } = item.result;
  const outlook = item.result.actual ?? item.result.expected;
  const top = economicInsights(item.result, { tillage: item.tillage, tillageSettings }).slice(0, 2);
  return (
    <Section title={`Economics ${year}`}>
      <Panel>
        <View style={{ flexDirection: 'row', flexWrap: 'wrap', rowGap: space.md }}>
          <Stat value={formatEuro(season.perHa, 2)} unit="/ha" label="Real cost" color={colors.wheat} />
          <Stat value={formatEuro(season.total, 0)} label="Season total" />
          <Stat
            value={outlook ? formatEuro(outlook.profitPerHa, 0) : '-'}
            unit={outlook ? '/ha' : undefined}
            label={item.result.actual ? 'Actual profit' : 'Expected profit'}
            color={outlook ? (outlook.profit >= 0 ? colors.leaf : colors.rust) : undefined}
          />
        </View>
        {season.total > 0 ? <CostBreakdown byCategory={season.byCategory} total={season.total} areaHa={item.result.areaHa} /> : <Txt variant="small">No costs yet for this season.</Txt>}
        <InsightList insights={top} />
        <Button mode="outlined" icon="calculator-variant-outline" onPress={onOpen}>
          Open the economic analysis
        </Button>
      </Panel>
    </Section>
  );
}
