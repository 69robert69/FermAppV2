import { MaterialCommunityIcons } from '@expo/vector-icons';
import { ECONOMIC_CATEGORIES, ECONOMIC_LABELS, formatEuro, type EconomicCategory, type EconomicInsight } from '@fermapp/domain';
import { useState } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';
import { Txt, type IconName } from '../../components/ui';
import { colors, fonts, space } from '../../theme';

/**
 * Cost categories in a fixed order with a categorical palette validated for
 * colour-blind separation on the app's dark surfaces. Every bar also carries a
 * legend with names and amounts, so colour is never the only cue.
 */
export const CATEGORY_COLORS: Record<EconomicCategory, string> = {
  SEEDS: '#3987e5',
  FERTILIZERS: '#d95926',
  CHEMICALS: '#199e70',
  FUEL: '#c98500',
  MACHINERY: '#d55181',
  LABOR: '#0ca30c',
  OTHER: '#9085e9',
};

const pct = (value: number, total: number) => (total > 0 ? Math.round((value / total) * 100) : 0);

/** The structure of a cost: one bar split by category, then each category with amount, per hectare and share. */
export function CostBreakdown({ byCategory, total, areaHa }: { byCategory: Record<EconomicCategory, number>; total: number; areaHa: number }) {
  const [focus, setFocus] = useState<EconomicCategory | null>(null);
  const present = ECONOMIC_CATEGORIES.filter((category) => byCategory[category] > 0);
  return (
    <View style={{ gap: space.md }}>
      <View style={styles.stack} accessibilityLabel={`Cost structure: ${present.map((category) => `${ECONOMIC_LABELS[category]} ${pct(byCategory[category], total)}%`).join(', ')}`}>
        {present.map((category) => (
          <Pressable
            key={category}
            onPress={() => setFocus((current) => (current === category ? null : category))}
            style={{ flex: byCategory[category], backgroundColor: CATEGORY_COLORS[category], opacity: focus && focus !== category ? 0.4 : 1 }}
          />
        ))}
      </View>
      {ECONOMIC_CATEGORIES.map((category) => {
        const value = byCategory[category];
        return (
          <Pressable
            key={category}
            accessibilityRole="button"
            onPress={() => setFocus((current) => (current === category ? null : category))}
            style={[styles.row, focus === category && styles.rowFocus]}
          >
            <View style={[styles.swatch, { backgroundColor: CATEGORY_COLORS[category] }]} />
            <Txt color={colors.chaff} style={{ flex: 1 }}>{ECONOMIC_LABELS[category]}</Txt>
            <View style={styles.amounts}>
              <Txt color={colors.chaff} style={styles.amount}>{formatEuro(value, 0)}</Txt>
              <Txt variant="small">{value > 0 ? `${formatEuro(areaHa > 0 ? value / areaHa : 0, 2)}/ha, ${pct(value, total)}%` : '-'}</Txt>
            </View>
          </Pressable>
        );
      })}
    </View>
  );
}

/** Profit can be negative: bars grow right for profit and left for loss, with the sign in the label. */
export function SignedBars({ data, format }: { data: { key: string; label: string; value: number | null }[]; format: (value: number) => string }) {
  const known = data.filter((item): item is { key: string; label: string; value: number } => item.value !== null);
  const max = Math.max(1e-9, ...known.map((item) => Math.abs(item.value)));
  if (known.length === 0) return <Txt variant="small">Enter yields and prices to see the profit.</Txt>;
  return (
    <View style={{ gap: space.sm }}>
      {known.map((item) => {
        const positive = item.value >= 0;
        const width = `${(Math.abs(item.value) / max) * 50}%` as const;
        return (
          <View key={item.key} style={{ gap: 3 }} accessibilityLabel={`${item.label}: ${positive ? 'profit' : 'loss'} ${format(Math.abs(item.value))}`}>
            <View style={styles.barHead}>
              <Txt variant="small" color={colors.chaff} numberOfLines={1} style={{ flex: 1 }}>{item.label}</Txt>
              <View style={styles.inline}>
                <MaterialCommunityIcons name={positive ? 'trending-up' : 'trending-down'} size={14} color={positive ? colors.leaf : colors.rust} />
                <Txt variant="small" color={colors.chaff}>{`${positive ? '+' : '−'}${format(Math.abs(item.value))}`}</Txt>
              </View>
            </View>
            <View style={styles.signedTrack}>
              <View style={styles.zero} />
              <View
                style={[
                  styles.signedFill,
                  positive ? { left: '50%', borderTopRightRadius: 4, borderBottomRightRadius: 4 } : { right: '50%', borderTopLeftRadius: 4, borderBottomLeftRadius: 4 },
                  { width, backgroundColor: positive ? colors.leaf : colors.rust },
                ]}
              />
            </View>
          </View>
        );
      })}
    </View>
  );
}

export function Kpi({ icon, label, value, detail, tone = colors.chaff }: { icon: IconName; label: string; value: string; detail?: string; tone?: string }) {
  return (
    <View style={styles.kpi}>
      <MaterialCommunityIcons name={icon} size={18} color={colors.straw} />
      <Txt variant="figure" color={tone} style={styles.kpiValue} numberOfLines={1}>{value}</Txt>
      <Txt variant="small" numberOfLines={2}>{label}</Txt>
      {detail ? <Txt variant="small" color={colors.chaff} numberOfLines={1}>{detail}</Txt> : null}
    </View>
  );
}

const TONE: Record<EconomicInsight['tone'], { icon: IconName; color: string }> = {
  info: { icon: 'lightbulb-on-outline', color: colors.water },
  warning: { icon: 'alert-outline', color: colors.amber },
  success: { icon: 'check-circle-outline', color: colors.leaf },
};

export function InsightList({ insights }: { insights: EconomicInsight[] }) {
  return (
    <View style={{ gap: space.sm }}>
      {insights.map((insight) => (
        <View key={insight.message} style={styles.insight}>
          <MaterialCommunityIcons name={TONE[insight.tone].icon} size={18} color={TONE[insight.tone].color} />
          <View style={{ flex: 1, gap: 2 }}>
            <Txt variant="small" color={colors.chaff}>{insight.message}</Txt>
            {insight.savings ? <Txt variant="small" color={colors.leaf}>{`Possible saving: ${formatEuro(insight.savings, 0)} this season`}</Txt> : null}
          </View>
        </View>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  stack: { flexDirection: 'row', height: 16, gap: 2, borderRadius: 5, overflow: 'hidden', backgroundColor: colors.ridge },
  row: { flexDirection: 'row', alignItems: 'center', gap: space.sm, minHeight: 44, paddingHorizontal: space.xs, borderRadius: 6 },
  rowFocus: { backgroundColor: colors.ridge },
  swatch: { width: 12, height: 12, borderRadius: 3 },
  amounts: { alignItems: 'flex-end' },
  amount: { fontFamily: fonts.semibold },
  barHead: { flexDirection: 'row', alignItems: 'center', gap: space.sm },
  inline: { flexDirection: 'row', alignItems: 'center', gap: 4 },
  signedTrack: { height: 10, borderRadius: 4, backgroundColor: colors.ridge, overflow: 'hidden' },
  zero: { position: 'absolute', left: '50%', top: 0, bottom: 0, width: 1, backgroundColor: colors.straw },
  signedFill: { position: 'absolute', top: 0, bottom: 0 },
  kpi: { width: '48.5%', padding: space.md, gap: 2, borderRadius: 12, backgroundColor: colors.furrow, borderWidth: 1, borderColor: `${colors.chaff}14` },
  kpiValue: { fontSize: 22, lineHeight: 27 },
  insight: { flexDirection: 'row', gap: space.sm, alignItems: 'flex-start', padding: space.md, borderRadius: 8, backgroundColor: colors.furrow },
});
