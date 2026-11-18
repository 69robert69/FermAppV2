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

const monthLabel = (date: string) => new Date(date).toLocaleDateString('ro-RO', { month: 'long', year: 'numeric' });

/** Activitățile grupate pe luni, în ordine cronologică. */
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
              {entry.done ? <MaterialCommunityIcons name="check-circle" size={20} color={colors.leaf} accessibilityLabel="Finalizat" /> : null}
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

/** Necesarul de apă, schema de fertilizare și planul de monitorizare. */
export function PlanResources({ plan }: { plan: ParcelPlanData }) {
  const { irrigation, fertilization, monitoring } = plan;
  return (
    <View style={{ gap: space.md }}>
      <Panel>
        <Txt variant="subtitle">Necesar de irigare</Txt>
        <Row label="Necesar pe sezon" value={`${number(irrigation.requiredMm, 0)} mm`} />
        <Row label="Ploaie utilă estimată" value={irrigation.effectiveRainMm === null ? 'Date indisponibile' : `${number(irrigation.effectiveRainMm, 0)} mm`} />
        <Row label="Deficit" value={irrigation.deficitMm === null ? 'Date indisponibile' : `${number(irrigation.deficitMm, 0)} mm`} />
        <Row label="Cicluri recomandate" value={irrigation.deficitMm === null ? '-' : `${irrigation.passes} x ${irrigation.doseMm} mm`} />
        <Row label="Perioadă critică" value={irrigation.criticalPeriod} />
      </Panel>
      <Panel>
        <Txt variant="subtitle">Schemă de fertilizare</Txt>
        {fertilization.map((stage) => (
          <View key={stage.stage} style={{ gap: 2 }}>
            <Txt>{`${stage.stage}${stage.date ? `, ${shortDate(stage.date)}` : ''}`}</Txt>
            <Txt variant="small">{`N ${stage.nKgHa}, P₂O₅ ${stage.pKgHa}, K₂O ${stage.kKgHa} kg/ha`}</Txt>
          </View>
        ))}
        <Txt variant="small">Doze orientative. Ajustează-le după analiza de sol.</Txt>
      </Panel>
      <Panel>
        <Txt variant="subtitle">Plan de monitorizare</Txt>
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
        <Row label="Parcelă" value={report.parcelName} />
        <Row label="Suprafață" value={`${number(report.areaHa, 2)} ha`} />
        <Row label="Tip sol" value={report.soilType ?? 'Date indisponibile'} />
        <Row label="Ultima cultură" value={report.lastCropName ?? 'Necunoscută'} />
        <Row label="Cultură planificată" value={report.plannedCropName} />
        <Row label="Scor compatibilitate" value={`${report.compatibilityScore}%`} />
        <Row label="Cultură recomandată" value={`${report.recommendedCropName}, ${report.recommendedScore}%`} />
        <Row label="Producție estimată" value={`${number(report.expectedYieldTHa)} t/ha`} />
        {report.expectedProfitEurHa !== undefined ? (
          <Row label="Profit estimat" value={`${number(report.expectedProfitEurHa, 0)} EUR/ha`} />
        ) : null}
      </Panel>
      <Panel>
        <Txt variant="subtitle">Riscuri principale</Txt>
        {report.risks.map((risk) => (
          <View key={risk} style={styles.risk}>
            <MaterialCommunityIcons name="alert-outline" size={18} color={colors.amber} />
            <Txt style={{ flex: 1 }}>{risk}</Txt>
          </View>
        ))}
      </Panel>
      <Panel>
        <Txt variant="subtitle">Recomandare</Txt>
        <Txt>{report.recommendation}</Txt>
      </Panel>
      <Txt variant="small">
        {report.priceEurPerT !== undefined
          ? `Estimări orientative pentru cultură neirigată, calculate cu ${report.priceEurPerT} EUR/t și costuri de ${report.costEurHa} EUR/ha.`
          : 'Estimări orientative pentru cultură neirigată.'}
        {report.missingData.length ? ` Lipsesc datele despre: ${report.missingData.join(', ')}.` : ''}
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
