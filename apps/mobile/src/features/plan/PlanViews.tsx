import type { ParcelPlanData, ParcelReport, PlanStep } from '@fermapp/domain';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { StyleSheet, View } from 'react-native';
import { Panel, Row, Txt } from '../../components/ui';
import { number, shortDate } from '../../lib/format';
import { colors, space } from '../../theme';

export interface CalendarEntry {
  id: string;
  date: string;
  title: string;
  subtitle?: string;
  done?: boolean;
}

const monthLabel = (date: string) => new Date(date).toLocaleDateString('en-GB', { month: 'long', year: 'numeric' });

/** Activities grouped by month, in chronological order. */
export function CalendarList({ entries }: { entries: CalendarEntry[] }) {
  const sorted = [...entries].sort((a, b) => a.date.localeCompare(b.date));
  const months = new Map<string, CalendarEntry[]>();
  for (const entry of sorted) {
    const key = monthLabel(entry.date);
    months.set(key, [...(months.get(key) ?? []), entry]);
  }
  return (
    <View style={{ gap: space.lg }}>
      {[...months.entries()].map(([month, items]) => (
        <View key={month} style={{ gap: space.sm }}>
          <Txt variant="subtitle" color={colors.wheat}>{month}</Txt>
          {items.map((entry) => (
            <View key={entry.id} style={styles.entry}>
              <Txt variant="figure" style={styles.day} color={entry.done ? colors.straw : colors.chaff}>
                {new Date(entry.date).getDate()}
              </Txt>
              <View style={{ flex: 1 }}>
                <Txt color={entry.done ? colors.straw : colors.chaff}>{entry.title}</Txt>
                {entry.subtitle ? <Txt variant="small">{entry.subtitle}</Txt> : null}
              </View>
              {entry.done ? <MaterialCommunityIcons name="check-circle" size={20} color={colors.leaf} accessibilityLabel="Done" /> : null}
            </View>
          ))}
        </View>
      ))}
    </View>
  );
}

export function PlanChecklist({ steps, doneKeys }: { steps: PlanStep[]; doneKeys?: ReadonlySet<string> }) {
  return (
    <View style={{ gap: space.sm }}>
      {steps.map((step) => {
        const done = doneKeys?.has(step.key) ?? false;
        return (
          <View key={step.key} style={styles.step}>
            <MaterialCommunityIcons
              name={done ? 'check-circle' : 'check-circle-outline'}
              size={22}
              color={done ? colors.leaf : step.past ? colors.straw : colors.wheat}
            />
            <Txt style={{ flex: 1 }} color={step.past ? colors.straw : colors.chaff}>{step.title}</Txt>
            <Txt variant="small">{shortDate(step.date)}</Txt>
          </View>
        );
      })}
    </View>
  );
}

export const stepsToCalendar = (steps: PlanStep[], doneKeys?: ReadonlySet<string>): CalendarEntry[] =>
  steps.map((step) => ({ id: step.key, date: step.date, title: step.title, done: doneKeys?.has(step.key) }));

/** Water requirement, fertilization scheme and monitoring plan. */
export function PlanResources({ plan }: { plan: ParcelPlanData }) {
  const { irrigation, fertilization, monitoring } = plan;
  return (
    <View style={{ gap: space.md }}>
      <Panel>
        <Txt variant="subtitle">Irrigation requirement</Txt>
        <Row label="Seasonal requirement" value={`${number(irrigation.requiredMm, 0)} mm`} />
        <Row label="Estimated effective rain" value={irrigation.effectiveRainMm === null ? 'Data unavailable' : `${number(irrigation.effectiveRainMm, 0)} mm`} />
        <Row label="Deficit" value={irrigation.deficitMm === null ? 'Data unavailable' : `${number(irrigation.deficitMm, 0)} mm`} />
        <Row label="Recommended cycles" value={irrigation.deficitMm === null ? '-' : `${irrigation.passes} x ${irrigation.doseMm} mm`} />
        <Row label="Critical period" value={irrigation.criticalPeriod} />
      </Panel>
      <Panel>
        <Txt variant="subtitle">Fertilization scheme</Txt>
        {fertilization.map((stage) => (
          <View key={stage.stage} style={{ gap: 2 }}>
            <Txt>{`${stage.stage}${stage.date ? `, ${shortDate(stage.date)}` : ''}`}</Txt>
            <Txt variant="small">{`N ${stage.nKgHa}, P₂O₅ ${stage.pKgHa}, K₂O ${stage.kKgHa} kg/ha`}</Txt>
          </View>
        ))}
        <Txt variant="small">Indicative doses. Adjust them according to the soil analysis.</Txt>
      </Panel>
      <Panel>
        <Txt variant="subtitle">Monitoring plan</Txt>
        {monitoring.map((item) => (
          <Row key={item.title} label={item.title} value={shortDate(item.date)} />
        ))}
      </Panel>
    </View>
  );
}

export function PlanReport({ report }: { report: ParcelReport }) {
  return (
    <View style={{ gap: space.md }}>
      <Panel>
        <Row label="Parcel" value={report.parcelName} />
        <Row label="Area" value={`${number(report.areaHa, 2)} ha`} />
        <Row label="Soil type" value={report.soilType ?? 'Data unavailable'} />
        <Row label="Last crop" value={report.lastCropName ?? 'Unknown'} />
        <Row label="Planned crop" value={report.plannedCropName} />
        <Row label="Compatibility score" value={`${report.compatibilityScore}%`} />
        <Row label="Recommended crop" value={`${report.recommendedCropName}, ${report.recommendedScore}%`} />
        <Row label="Estimated yield" value={`${number(report.expectedYieldTHa)} t/ha`} />
        {report.expectedProfitEurHa !== undefined ? (
          <Row label="Estimated profit" value={`${number(report.expectedProfitEurHa, 0)} EUR/ha`} />
        ) : null}
      </Panel>
      <Panel>
        <Txt variant="subtitle">Main risks</Txt>
        {report.risks.map((risk) => (
          <View key={risk} style={styles.risk}>
            <MaterialCommunityIcons name="alert-outline" size={18} color={colors.amber} />
            <Txt style={{ flex: 1 }}>{risk}</Txt>
          </View>
        ))}
      </Panel>
      <Panel>
        <Txt variant="subtitle">Recommendation</Txt>
        <Txt>{report.recommendation}</Txt>
      </Panel>
      <Txt variant="small">
        {report.priceEurPerT !== undefined
          ? `Indicative estimates for a non-irrigated crop, calculated with ${report.priceEurPerT} EUR/t and costs of ${report.costEurHa} EUR/ha.`
          : 'Indicative estimates for a non-irrigated crop.'}
        {report.missingData.length ? ` Missing data about: ${report.missingData.join(', ')}.` : ''}
      </Txt>
    </View>
  );
}

const styles = StyleSheet.create({
  entry: { flexDirection: 'row', alignItems: 'center', gap: space.md, minHeight: 44 },
  day: { width: 38, fontSize: 24, lineHeight: 28, textAlign: 'right' },
  step: { flexDirection: 'row', alignItems: 'center', gap: space.sm, minHeight: 36 },
  risk: { flexDirection: 'row', gap: space.sm, alignItems: 'flex-start' },
});
