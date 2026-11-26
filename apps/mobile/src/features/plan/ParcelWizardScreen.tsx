import type { TillageSystem } from '@fermapp/domain';
import {
  CROP_CODES,
  CROPS,
  OTHER_CROP,
  type CropCode,
  type LastCrop,
  type ParcelPlanData,
  type PolygonGeometry,
} from '@fermapp/domain';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useRef, useState, type ComponentRef } from 'react';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { Button } from 'react-native-paper';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { apiSend, newOperationId } from '../../api/client';
import { Field, Panel, ProgressTrack, QueryState, Txt } from '../../components/ui';
import { SeedPicker } from '../catalog/SeedPicker';
import { useAction } from '../../lib/hooks';
import type { RootStackParams } from '../../navigation/RootNavigator';
import { colors, space } from '../../theme';
import { CalendarList, PlanChecklist, PlanReport, stepsToCalendar } from './PlanViews';

export interface ParcelDraft {
  name: string;
  geometry: PolygonGeometry;
  code?: string;
  locality?: string;
  county?: string;
  notes?: string;
  irrigatedAreaHa?: number | null;
  tillageSystem?: TillageSystem | null;
}

type Props = NativeStackScreenProps<RootStackParams, 'ParcelWizard'>;

const TOTAL_STEPS = 6;
const SHOWN_RECOMMENDATIONS = 5;
const cropLabel = (code: CropCode) => (code === 'BEET' ? 'Sugar beet' : CROPS[code].name);

const LAST_CROPS: { value: LastCrop; label: string }[] = [
  ...(['WHEAT', 'CORN', 'SUNFLOWER', 'SOY', 'RAPESEED', 'BARLEY', 'ALFALFA', 'POTATO', 'BEET'] as const).map((value) => ({
    value,
    label: cropLabel(value),
  })),
  { value: OTHER_CROP, label: 'Other crop' },
];
const PLANNED_CROPS = CROP_CODES.map((value) => ({ value, label: cropLabel(value) }));

const TITLES = [
  'What was the last crop grown on this parcel?',
  'Which crop do you want to plant next season?',
  'Recommended crops',
  'Technology plan',
  'Farm calendar',
  'Parcel report',
];

function OptionList<T extends string>({
  options,
  value,
  onChange,
}: {
  options: { value: T; label: string }[];
  value: T | null;
  onChange: (value: T) => void;
}) {
  return (
    <View accessibilityRole="radiogroup" style={{ gap: space.sm }}>
      {options.map((option) => {
        const selected = option.value === value;
        return (
          <Pressable
            key={option.value}
            accessibilityRole="radio"
            accessibilityState={{ selected }}
            onPress={() => onChange(option.value)}
            style={[styles.option, selected && styles.optionOn]}
          >
            <MaterialCommunityIcons
              name={selected ? 'radiobox-marked' : 'radiobox-blank'}
              size={22}
              color={selected ? colors.wheat : colors.straw}
            />
            <Txt style={{ flex: 1 }}>{option.label}</Txt>
          </Pressable>
        );
      })}
    </View>
  );
}

const scoreColor = (score: number) => (score >= 70 ? colors.leaf : score >= 50 ? colors.amber : colors.rust);

function Recommendations({
  plan,
  selected,
  onSelect,
}: {
  plan: ParcelPlanData;
  selected: CropCode;
  onSelect: (crop: CropCode) => void;
}) {
  const top = plan.recommendations.slice(0, SHOWN_RECOMMENDATIONS);
  const chosen = plan.recommendations.find((item) => item.crop === selected);
  const shown = chosen && !top.includes(chosen) ? [...top, chosen] : top;
  return (
    <View style={{ gap: space.md }}>
      <Txt variant="small">The score combines soil, climate, rainfall and rotation. Tap a crop to choose it.</Txt>
      {shown.map((item) => {
        const active = item.crop === selected;
        return (
          <Pressable
            key={item.crop}
            accessibilityRole="radio"
            accessibilityState={{ selected: active }}
            onPress={() => onSelect(item.crop)}
            style={[styles.recommendation, active && styles.optionOn]}
          >
            <View style={styles.recommendationHead}>
              <Txt variant="subtitle" style={{ flex: 1 }}>{cropLabel(item.crop)}</Txt>
              <Txt variant="figure" color={scoreColor(item.score)}>{`${item.score}%`}</Txt>
            </View>
            <ProgressTrack percent={item.score} color={scoreColor(item.score)} />
            <Txt variant="small">{item.reason}</Txt>
          </Pressable>
        );
      })}
    </View>
  );
}

export function ParcelWizardScreen({ route, navigation }: Props) {
  const { draft, parcelId } = route.params;
  const insets = useSafeAreaInsets();
  const [step, setStep] = useState(1);
  const [lastCrop, setLastCrop] = useState<LastCrop | null>(null);
  const [lastCropName, setLastCropName] = useState('');
  const [plannedCrop, setPlannedCropValue] = useState<CropCode | null>(null);
  const [seed, setSeed] = useState<{ id: string; name: string } | null>(null);
  const setPlannedCrop = (crop: CropCode) => {
    setPlannedCropValue(crop);
    setSeed(null);
  };
  const operationId = useRef(newOperationId());
  const scroll = useRef<ComponentRef<typeof ScrollView>>(null);

  const choice = {
    lastCrop,
    lastCropName: lastCrop === OTHER_CROP && lastCropName.trim() ? lastCropName.trim() : undefined,
    plannedCrop,
  };

  const analysis = useQuery({
    queryKey: ['wizard-analysis', parcelId ?? JSON.stringify(draft?.geometry), choice],
    enabled: step >= 3 && Boolean(lastCrop && plannedCrop),
    retry: false,
    placeholderData: keepPreviousData,
    gcTime: 0,
    staleTime: Infinity,
    queryFn: async () =>
      (await apiSend<ParcelPlanData>(
        'POST',
        '/parcels/wizard/analyze',
        { ...choice, ...(parcelId ? { parcelId } : { geometry: draft?.geometry, name: draft?.name }) },
        { offline: false, idempotent: false },
      )) as ParcelPlanData,
  });

  const invalidated = [['parcels'], ['map-overview'], ['tasks'], ['dashboard'], ['intelligence']];
  const finish = () => navigation.popToTop();

  const save = useAction<void>(
    async () => {
      await apiSend(
        'POST',
        '/parcels/wizard/complete',
        { ...choice, seedId: seed?.id, seedName: seed?.name, ...(parcelId ? { parcelId } : { draft }) },
        { offline: false, operationId: operationId.current },
      );
    },
    invalidated,
    finish,
  );

  // Without a connection, the drawn parcel stays saved in the offline queue.
  const saveWithoutPlan = useAction<void>(
    async () => {
      await apiSend('POST', '/parcels', draft);
    },
    invalidated,
    finish,
  );

  const go = (next: number) => {
    setStep(next);
    scroll.current?.scrollTo({ y: 0, animated: false });
  };

  const canContinue =
    step === 1 ? Boolean(lastCrop) : step === 2 ? Boolean(plannedCrop) : analysis.data !== undefined && !analysis.isFetching;

  return (
    <View style={{ flex: 1 }}>
      <View style={styles.progress}>
        <Txt variant="small">{`Step ${step} of ${TOTAL_STEPS}`}</Txt>
        <ProgressTrack percent={(step / TOTAL_STEPS) * 100} />
      </View>

      <ScrollView ref={scroll} contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
        <Txt variant="title">{TITLES[step - 1]}</Txt>

        {step === 1 ? (
          <>
            <OptionList options={LAST_CROPS} value={lastCrop} onChange={setLastCrop} />
            {lastCrop === OTHER_CROP ? <Field label="Crop name (optional)" value={lastCropName} onChangeText={setLastCropName} /> : null}
          </>
        ) : null}

        {step === 2 ? (
          <>
            <OptionList options={PLANNED_CROPS} value={plannedCrop} onChange={setPlannedCrop} />
            <SeedPicker crop={plannedCrop} parcelId={parcelId ?? null} value={seed?.id ?? null} onChange={setSeed} />
          </>
        ) : null}

        {step >= 3 ? (
          <>
            {analysis.isFetching ? <Txt variant="small">Analysing soil, climate and crop rotation.</Txt> : null}
            <QueryState query={analysis}>
              {(plan) => (
                <>
                  {step === 3 && plannedCrop ? <Recommendations plan={plan} selected={plannedCrop} onSelect={setPlannedCrop} /> : null}
                  {step === 4 ? (
                    <Panel>
                      <Txt variant="subtitle">{cropLabel(plan.crop)}</Txt>
                      <PlanChecklist steps={plan.steps} />
                    </Panel>
                  ) : null}
                  {step === 5 ? (
                    <Panel>
                      <Txt variant="subtitle">{cropLabel(plan.crop)}</Txt>
                      <CalendarList entries={stepsToCalendar(plan.steps)} />
                      <Txt variant="small">Activities are added automatically to To Do, the calendar and the parcel panel.</Txt>
                    </Panel>
                  ) : null}
                  {step === 6 ? <PlanReport report={plan.report} /> : null}
                </>
              )}
            </QueryState>
            {analysis.isError && draft ? (
              <Button mode="outlined" loading={saveWithoutPlan.isPending} disabled={saveWithoutPlan.isPending} onPress={() => saveWithoutPlan.mutate()}>
                Save parcel without a plan
              </Button>
            ) : null}
          </>
        ) : null}
      </ScrollView>

      <View style={[styles.footer, { paddingBottom: insets.bottom + space.md }]}>
        <Button style={{ flex: 1 }} mode="outlined" onPress={() => (step === 1 ? navigation.goBack() : go(step - 1))}>
          Back
        </Button>
        {step < TOTAL_STEPS ? (
          <Button style={{ flex: 1 }} mode="contained" disabled={!canContinue} onPress={() => go(step + 1)}>
            Continue
          </Button>
        ) : (
          <Button style={{ flex: 1 }} mode="contained" loading={save.isPending} disabled={!canContinue || save.isPending} onPress={() => save.mutate()}>
            {parcelId ? 'Save plan' : 'Save parcel'}
          </Button>
        )}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  progress: { padding: space.lg, paddingBottom: space.sm, gap: space.xs },
  content: { padding: space.lg, gap: space.lg, paddingBottom: space.xxl },
  option: { minHeight: 52, flexDirection: 'row', alignItems: 'center', gap: space.md, paddingHorizontal: space.md, borderRadius: 6, borderWidth: 1, borderColor: colors.line, backgroundColor: colors.furrow },
  optionOn: { borderColor: colors.wheat, backgroundColor: colors.ridge },
  recommendation: { padding: space.md, gap: space.sm, borderRadius: 6, borderWidth: 1, borderColor: colors.line, backgroundColor: colors.furrow },
  recommendationHead: { flexDirection: 'row', alignItems: 'center', gap: space.md },
  footer: { flexDirection: 'row', gap: space.sm, paddingHorizontal: space.lg, paddingTop: space.md, borderTopWidth: 1, borderTopColor: colors.line, backgroundColor: colors.furrow },
});
