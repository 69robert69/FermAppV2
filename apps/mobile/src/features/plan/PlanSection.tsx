import { useMemo, useState } from 'react';
import { View } from 'react-native';
import { Button } from 'react-native-paper';
import { Choice, Panel, Row, Section, Txt } from '../../components/ui';
import { shortDate } from '../../lib/format';
import { space } from '../../theme';
import type { ParcelIntelligence } from '../intelligence/api';
import { CalendarList, PlanChecklist, PlanReport, PlanResources, stepsToCalendar } from './PlanViews';

type PlanView = 'steps' | 'calendar' | 'resources' | 'report';

const VIEWS: { value: PlanView; label: string }[] = [
  { value: 'steps', label: 'Plan tehnologic' },
  { value: 'calendar', label: 'Calendar' },
  { value: 'resources', label: 'Apă și nutrienți' },
  { value: 'report', label: 'Raport' },
];

/** Planul activ al parcelei, în panoul de informații. */
export function PlanSection({
  data,
  canEdit,
  onConfigure,
}: {
  data: ParcelIntelligence;
  canEdit: boolean;
  onConfigure: () => void;
}) {
  const [view, setView] = useState<PlanView>('steps');
  const doneKeys = useMemo(
    () => new Set(data.tasks.items.filter((task) => task.status === 'DONE' && task.planStepKey).map((task) => task.planStepKey!)),
    [data.tasks.items],
  );

  if (!data.plan) {
    return (
      <Section title="Plan tehnologic">
        <Panel>
          <Txt>Parcela nu are încă un plan pentru sezonul următor.</Txt>
          {canEdit ? <Button mode="contained" onPress={onConfigure}>Creează planul</Button> : null}
        </Panel>
      </Section>
    );
  }

  const plan = data.plan.data;
  return (
    <Section title={`Plan ${plan.cropName} ${plan.year}`}>
      <Choice options={VIEWS} value={view} onChange={setView} />
      {view === 'steps' ? (
        <Panel>
          <PlanChecklist steps={plan.steps} doneKeys={doneKeys} />
        </Panel>
      ) : null}
      {view === 'calendar' ? (
        <Panel>
          <CalendarList entries={stepsToCalendar(plan.steps, doneKeys)} />
        </Panel>
      ) : null}
      {view === 'resources' ? <PlanResources plan={plan} /> : null}
      {view === 'report' ? (
        <View style={{ gap: space.md }}>
          <PlanReport report={plan.report} />
          {data.planHistory.length > 1 ? (
            <Panel>
              <Txt variant="subtitle">Rapoarte salvate</Txt>
              {data.planHistory.map((item) => (
                <Row key={item.id} label={shortDate(item.createdAt)} value={`${item.cropName} ${item.year}${item.active ? ', activ' : ''}`} />
              ))}
            </Panel>
          ) : null}
        </View>
      ) : null}
      {canEdit ? <Button mode="outlined" onPress={onConfigure}>Schimbă cultura planificată</Button> : null}
    </Section>
  );
}
