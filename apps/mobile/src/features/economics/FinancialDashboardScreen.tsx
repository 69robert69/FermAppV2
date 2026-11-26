import {
  economicInsights,
  formatEuro,
  TILLAGE_LIST,
  TILLAGE_SYSTEMS,
  type EconomicGroup,
  type EconomicInsight,
} from '@fermapp/domain';
import { useNavigation, type NavigationProp } from '@react-navigation/native';
import { useMemo, useState } from 'react';
import { StyleSheet, View } from 'react-native';
import { Button } from 'react-native-paper';
import { Choice, Empty, Panel, QueryState, Screen, Section, Txt } from '../../components/ui';
import { number } from '../../lib/format';
import type { RootStackParams } from '../../navigation/RootNavigator';
import { useAuth } from '../../store/auth';
import { colors, space } from '../../theme';
import { BarList, ChartTableToggle, DataTable } from '../tillage/charts';
import { useTillageSettings } from '../tillage/prefs';
import { useFarmEconomics } from './api';
import { CostBreakdown, InsightList, Kpi, SignedBars } from './components';

/** Magnitude bars use a single hue; colour only carries identity for technologies. */
const MAGNITUDE = colors.wheat;
const bestProfit = (group: EconomicGroup) => group.actualProfitPerHa ?? group.expectedProfitPerHa;
const profitLabel = (group: EconomicGroup | null) => {
  if (!group) return '-';
  const value = bestProfit(group);
  return value == null ? group.label : `${group.label}, ${formatEuro(value, 0)}/ha`;
};

/** Farm-wide view of cost, profit and technology. */
export function FinancialDashboardScreen() {
  const navigation = useNavigation<NavigationProp<RootStackParams>>();
  const farmName = useAuth((state) => state.farm?.name ?? 'This farm');
  const thisYear = new Date().getFullYear();
  const [year, setYear] = useState(thisYear);
  const [table, setTable] = useState(false);
  const economics = useFarmEconomics(year);
  const tillageSettings = useTillageSettings();

  const farmInsights = useMemo<EconomicInsight[]>(() => {
    const data = economics.data;
    if (!data) return [];
    const all = data.parcels.flatMap((item) => economicInsights(item.result, { tillage: item.tillage, tillageSettings }).map((insight) => ({ insight, item })));
    const savings = all.filter(({ insight }) => insight.savings);
    const total = savings.reduce((sum, { insight }) => sum + (insight.savings ?? 0), 0);
    const result: EconomicInsight[] = [];
    if (total > 0) {
      result.push({
        tone: 'success',
        message: `Moving ${savings.length} parcel${savings.length > 1 ? 's' : ''} to a lighter tillage technology could save about ${formatEuro(total, 0)} this season.`,
        savings: total,
      });
    }
    const summary = data.summary;
    const top = Object.entries(summary.byCategory).sort((a, b) => b[1] - a[1])[0];
    if (top && summary.cost > 0) {
      const labels: Record<string, string> = { SEEDS: 'Seed', FERTILIZERS: 'Fertiliser', CHEMICALS: 'Crop protection', FUEL: 'Diesel', MACHINERY: 'Machinery', LABOR: 'Labour', OTHER: 'Other costs' };
      result.push({ tone: 'info', message: `${labels[top[0]]} is ${Math.round((top[1] / summary.cost) * 100)}% of the farm’s production cost.` });
    }
    const priciest = summary.byParcel.filter((item) => item.areaHa > 0).sort((a, b) => b.costPerHa - a.costPerHa)[0];
    if (priciest && summary.costPerHa > 0 && priciest.costPerHa > summary.costPerHa * 1.25) {
      result.push({ tone: 'warning', message: `${priciest.label} costs ${formatEuro(priciest.costPerHa, 0)}/ha, ${Math.round((priciest.costPerHa / summary.costPerHa - 1) * 100)}% above the farm average. Check its inputs and passes.` });
    }
    const losses = summary.byParcel.filter((item) => (bestProfit(item) ?? 0) < 0);
    if (losses.length) result.push({ tone: 'warning', message: `${losses.map((item) => item.label).join(', ')} ${losses.length > 1 ? 'run' : 'runs'} at a loss this season.` });
    return result;
  }, [economics.data, tillageSettings]);

  return (
    <Screen onRefresh={() => void economics.refetch()} refreshing={economics.isRefetching}>
      <Choice options={[thisYear - 1, thisYear, thisYear + 1].map((value) => ({ value: String(value), label: String(value) }))} value={String(year)} onChange={(value) => setYear(Number(value))} />
      <QueryState query={economics}>
        {({ summary }) =>
          summary.cost <= 0 ? (
            <Empty
              icon="cash-multiple"
              title={`No costs for ${year} yet`}
              hint="Open a parcel, Economic analysis, and add seed, fertiliser and crop protection."
              action={<Button mode="outlined" onPress={() => navigation.navigate('EconomicSettings')}>Diesel, labour and machine costs</Button>}
            />
          ) : (
            <>
              <Section title="Indicators">
                <View style={styles.kpis}>
                  <Kpi icon="calculator-variant-outline" label="Average cost per hectare" value={formatEuro(summary.costPerHa, 2)} detail={`${formatEuro(summary.cost, 0)} on ${number(summary.areaHa)} ha`} tone={colors.wheat} />
                  <Kpi
                    icon="trending-up"
                    label="Average profit per hectare"
                    value={summary.profitPerHa == null ? '-' : formatEuro(summary.profitPerHa, 0)}
                    tone={(summary.profitPerHa ?? 0) >= 0 ? colors.leaf : colors.rust}
                  />
                  <Kpi icon="trophy-outline" label="Most profitable field" value={summary.mostProfitableParcel?.label ?? '-'} detail={profitLabel(summary.mostProfitableParcel).split(', ')[1]} />
                  <Kpi icon="sprout" label="Most profitable crop" value={summary.mostProfitableCrop?.label ?? '-'} detail={profitLabel(summary.mostProfitableCrop).split(', ')[1]} />
                  <Kpi
                    icon="tractor-variant"
                    label="Most efficient technology"
                    value={summary.mostEfficientTillage?.label ?? '-'}
                    detail={summary.mostEfficientTillage ? `${formatEuro(summary.mostEfficientTillage.costPerHa, 0)}/ha` : 'Set a technology on parcels'}
                  />
                  <Kpi icon="gas-station-outline" label="Diesel cost" value={formatEuro(summary.byCategory.FUEL, 0)} detail={summary.areaHa > 0 ? `${formatEuro(summary.byCategory.FUEL / summary.areaHa, 0)}/ha` : undefined} />
                </View>
              </Section>

              <Section title="Recommendations">
                <InsightList insights={farmInsights} />
              </Section>

              <Section title="Cost structure">
                <Panel>
                  <CostBreakdown byCategory={summary.byCategory} total={summary.cost} areaHa={summary.areaHa} />
                </Panel>
              </Section>

              <Section title="Costs by crop">
                <Panel>
                  <BarList
                    format={(value) => `${formatEuro(value, 0)}/ha`}
                    data={summary.byCrop.map((item) => ({ key: item.key, label: item.label, color: MAGNITUDE, value: item.costPerHa, detail: `${formatEuro(item.cost, 0)} on ${number(item.areaHa)} ha` }))}
                  />
                </Panel>
              </Section>

              <Section title="Costs by parcel">
                <Panel>
                  <BarList
                    format={(value) => `${formatEuro(value, 0)}/ha`}
                    data={summary.byParcel.map((item) => ({ key: item.key, label: item.label, color: MAGNITUDE, value: item.costPerHa, detail: `${formatEuro(item.cost, 0)} on ${number(item.areaHa)} ha` }))}
                  />
                </Panel>
              </Section>

              <Section title="Costs by technology">
                <Panel>
                  {summary.byTillage.length === 0 ? (
                    <Txt variant="small">No parcel has a tillage technology yet.</Txt>
                  ) : (
                    <BarList
                      format={(value) => `${formatEuro(value, 0)}/ha`}
                      data={summary.byTillage.map((item) => ({
                        key: item.key,
                        label: item.label,
                        color: TILLAGE_SYSTEMS[item.key as keyof typeof TILLAGE_SYSTEMS]?.color ?? MAGNITUDE,
                        value: item.costPerHa,
                        detail: `${formatEuro(item.cost, 0)} on ${number(item.areaHa)} ha`,
                      }))}
                    />
                  )}
                </Panel>
              </Section>

              <Section title="Profit by crop">
                <Panel>
                  <SignedBars format={(value) => `${formatEuro(value, 0)}/ha`} data={summary.byCrop.map((item) => ({ key: item.key, label: item.label, value: bestProfit(item) }))} />
                </Panel>
              </Section>

              <Section title="Profit by parcel">
                <Panel>
                  <SignedBars format={(value) => `${formatEuro(value, 0)}/ha`} data={summary.byParcel.map((item) => ({ key: item.key, label: item.label, value: bestProfit(item) }))} />
                </Panel>
              </Section>

              <Section title="Profit by farm">
                <Panel>
                  <SignedBars
                    format={(value) => formatEuro(value, 0)}
                    data={[{ key: 'farm', label: farmName, value: summary.actualProfit ?? summary.expectedProfit }]}
                  />
                  <Txt variant="small">Each farm keeps its own data. Switch farms from Settings to compare their results.</Txt>
                </Panel>
              </Section>

              <Section title="Technology comparison" action={<ChartTableToggle table={table} onChange={setTable} />}>
                <Panel>
                  {(() => {
                    const rows = TILLAGE_LIST.map((profile) => {
                      const used = summary.byTillage.find((item) => item.key === profile.code);
                      return { profile, used };
                    });
                    return table ? (
                      <DataTable
                        columns={['Technology', '€/ha', 'l/ha', 'Passes', 'Exp. €/ha', 'Act. €/ha']}
                        rows={rows.map(({ profile, used }) => [
                          profile.badge,
                          used ? number(used.costPerHa, 0) : `${number(tillageSettings[profile.code]?.costPerHa ?? profile.costPerHa, 0)}*`,
                          used ? number(used.fuelLPerHa, 0) : `${number(tillageSettings[profile.code]?.fuelLPerHa ?? profile.fuelLPerHa, 0)}*`,
                          used ? number(used.passes, 1) : `${profile.passes}*`,
                          used?.expectedProfitPerHa != null ? number(used.expectedProfitPerHa, 0) : '-',
                          used?.actualProfitPerHa != null ? number(used.actualProfitPerHa, 0) : '-',
                        ])}
                      />
                    ) : (
                      <View style={{ gap: space.md }}>
                        {rows.map(({ profile, used }) => (
                          <View key={profile.code} style={styles.tech}>
                            <View style={[styles.techMark, { backgroundColor: profile.color }]} />
                            <View style={{ flex: 1, gap: 2 }}>
                              <Txt color={colors.chaff}>{`${profile.name}${used ? `, ${number(used.areaHa)} ha` : ' (model, not used)'}`}</Txt>
                              <Txt variant="small">
                                {used
                                  ? `${formatEuro(used.costPerHa, 0)}/ha, ${number(used.fuelLPerHa, 0)} l/ha diesel, ${number(used.passes, 1)} passes`
                                  : `${formatEuro(tillageSettings[profile.code]?.costPerHa ?? profile.costPerHa, 0)}/ha, ${number(tillageSettings[profile.code]?.fuelLPerHa ?? profile.fuelLPerHa, 0)} l/ha, ${profile.passes} passes`}
                              </Txt>
                              {used ? (
                                <Txt variant="small">
                                  {`Expected profit ${used.expectedProfitPerHa == null ? '-' : `${formatEuro(used.expectedProfitPerHa, 0)}/ha`}, actual ${used.actualProfitPerHa == null ? '-' : `${formatEuro(used.actualProfitPerHa, 0)}/ha`}`}
                                </Txt>
                              ) : null}
                            </View>
                          </View>
                        ))}
                      </View>
                    );
                  })()}
                  <Txt variant="small">* Model values from the tillage assumptions, for technologies not used on the farm.</Txt>
                </Panel>
              </Section>
            </>
          )
        }
      </QueryState>
      <Button mode="outlined" icon="cog-outline" onPress={() => navigation.navigate('EconomicSettings')}>
        Diesel, labour and machine costs
      </Button>
    </Screen>
  );
}

const styles = StyleSheet.create({
  kpis: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  tech: { flexDirection: 'row', gap: space.md },
  techMark: { width: 4, borderRadius: 2 },
});
