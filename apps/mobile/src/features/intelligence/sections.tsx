import { MaterialCommunityIcons } from '@expo/vector-icons';
import { BottomSheetTextInput } from '@gorhom/bottom-sheet';
import { formatEuro, type CropCode } from '@fermapp/domain';
import { useMutation } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, View } from 'react-native';
import { Button } from 'react-native-paper';
import { apiSend, isQueued } from '../../api/client';
import type { TaskStatus } from '../../api/types';
import { Choice, Panel, ProgressTrack, Row, Section, Stat, Txt, type IconName } from '../../components/ui';
import { clock, hoursMinutes, isPositive, number, parseNumber, shortDate, TASK_TYPES, toRoDate, WORK_TYPES } from '../../lib/format';
import { errorMessage, useAction, useLiveProgress } from '../../lib/hooks';
import { colors, fonts, space } from '../../theme';
import { CircularProgress } from './CircularProgress';
import {
  intelligenceKey,
  useCropRecommendations,
  type ActiveIrrigation,
  type ActiveWork,
  type ParcelIntelligence,
  type SatellitePoint,
  type WeatherDay,
} from './api';

const CROP_ICONS: Record<CropCode, IconName> = {
  WHEAT: 'barley',
  BARLEY: 'barley',
  OAT: 'barley',
  CORN: 'corn',
  SUNFLOWER: 'flower',
  RAPESEED: 'flower-outline',
  SOY: 'seed-outline',
  ALFALFA: 'grass',
  POTATO: 'sprout',
  BEET: 'sprout',
  VEGETABLES: 'carrot',
};

const CROP_STATUS = {
  NOT_SOWN: { label: 'Not sown', color: colors.straw },
  GROWING: { label: 'Growing', color: colors.leaf },
  READY: { label: 'Near harvest', color: colors.wheat },
  HARVESTED: { label: 'Harvested', color: colors.straw },
} as const;

const VERDICT_COLOR = { RECOMMENDED: colors.leaf, ACCEPTABLE: colors.amber, NOT_RECOMMENDED: colors.rust } as const;
const LEVEL_COLOR = { LOW: colors.leaf, MEDIUM: colors.amber, HIGH: colors.rust } as const;
const LEVEL_LABEL = { LOW: 'Low', MEDIUM: 'Medium', HIGH: 'High' } as const;
const HEALTH_COLOR = { green: colors.leaf, yellow: colors.amber, red: colors.rust } as const;
const HEALTH_LABEL = { green: 'Healthy', yellow: 'Warning', red: 'Critical' } as const;

const dateText = (value: string | null | undefined) => (value ? toRoDate(new Date(value)) : 'Not filled in');

function Missing({ children }: { children: ReactNode }) {
  return <Txt variant="small">{children}</Txt>;
}

export function SheetInput({
  label,
  value,
  onChangeText,
  keyboardType,
  multiline,
}: {
  label: string;
  value: string;
  onChangeText: (value: string) => void;
  keyboardType?: 'decimal-pad' | 'default';
  multiline?: boolean;
}) {
  return (
    <View style={{ gap: space.xs, flex: multiline ? undefined : 1 }}>
      <Txt variant="small">{label}</Txt>
      <BottomSheetTextInput
        accessibilityLabel={label}
        value={value}
        onChangeText={onChangeText}
        keyboardType={keyboardType}
        multiline={multiline}
        placeholderTextColor={colors.straw}
        style={[styles.input, multiline && styles.inputMultiline]}
      />
    </View>
  );
}

/** Asks the assistant for an explanation based on the parcel's real data. */
function AiExplain({ parcelId, question }: { parcelId: string; question: string }) {
  const ask = useMutation({
    mutationFn: async () => {
      const result = await apiSend<{ message: { content: string } }>('POST', '/ai/chat', { message: question, parcelId }, { offline: false });
      return isQueued(result) ? '' : result.message.content;
    },
  });
  if (ask.data) return <Txt>{ask.data}</Txt>;
  return (
    <View style={{ gap: space.xs }}>
      <Button compact mode="text" icon="sprout-outline" loading={ask.isPending} disabled={ask.isPending} onPress={() => ask.mutate()} style={{ alignSelf: 'flex-start' }}>
        Explain in more detail
      </Button>
      {ask.isError ? <Txt variant="small" color={colors.rust}>{errorMessage(ask.error)}</Txt> : null}
    </View>
  );
}

export function HeaderSection({ data }: { data: ParcelIntelligence }) {
  const { header } = data;
  return (
    <View style={{ gap: space.xs }}>
      <Txt variant="small">Parcel Intelligence</Txt>
      <Txt variant="display">{header.name}</Txt>
      <View style={styles.grid}>
        <Stat value={number(header.areaHa, 2)} unit="ha" label="Area" color={colors.wheat} />
        <Stat value={header.code} label="Parcel code" />
      </View>
      <Row label="County" value={header.county ?? 'Not filled in'} />
      <Row label="Locality" value={header.locality ?? 'Not filled in'} />
      <Row label="GPS coordinates" value={`${header.lat.toFixed(5)}, ${header.lng.toFixed(5)}`} />
    </View>
  );
}

export function CropSection({ data }: { data: ParcelIntelligence }) {
  const { crop } = data;
  if (!crop) {
    return (
      <Section title="Current crop">
        <Panel><Missing>There is no crop recorded for this year.</Missing></Panel>
      </Section>
    );
  }
  const status = CROP_STATUS[crop.status];
  return (
    <Section title="Current crop">
      <Panel>
        <View style={styles.inline}>
          <MaterialCommunityIcons name={CROP_ICONS[crop.code]} size={30} color={status.color} />
          <View style={{ flex: 1 }}>
            <Txt variant="title">{crop.name}</Txt>
            <Txt variant="small" color={status.color}>{status.label}</Txt>
          </View>
          <Txt variant="figure" color={status.color}>{`${crop.percent}%`}</Txt>
        </View>
        <ProgressTrack percent={crop.percent} color={status.color} />
        <Row label="Sown" value={dateText(crop.sowingDate)} />
        <Row label="Estimated harvest" value={dateText(crop.estimatedHarvestDate)} />
        <Row label="Growing day" value={`${crop.vegetationDay} / ${crop.totalDays} days`} />
      </Panel>
    </Section>
  );
}

export function RotationSection({ data }: { data: ParcelIntelligence }) {
  const { rotation, header } = data;
  const color = VERDICT_COLOR[rotation.verdict];
  return (
    <Section title="Crop rotation">
      <Panel>
        <View style={styles.inline}>
          <CircularProgress percent={rotation.score} size={76} color={color} />
          <View style={{ flex: 1, gap: 2 }}>
            <Txt variant="subtitle" color={color}>{rotation.label}</Txt>
            {rotation.mode === 'SUGGESTED' ? <Txt variant="small">The best option for this year</Txt> : null}
            <Txt variant="small">{rotation.explanation}</Txt>
          </View>
        </View>
        {rotation.history.length === 0 ? <Missing>Add the crop history for a more accurate score.</Missing> : null}
        {rotation.history.map((item) => (
          <Row key={item.year} label={String(item.year)} value={item.name} />
        ))}
        <AiExplain parcelId={header.id} question="Explain this parcel's rotation score and what risk exists." />
      </Panel>
    </Section>
  );
}

export function IrrigationCoverageSection({ data }: { data: ParcelIntelligence }) {
  const { irrigation } = data;
  return (
    <Section title="Irrigation">
      <Panel>
        {irrigation.configured ? (
          <View style={styles.inline}>
            <CircularProgress percent={irrigation.coveragePct ?? 0} icon="water" />
            <View style={{ flex: 1, gap: space.sm }}>
              <Stat value={number(irrigation.irrigatedHa ?? 0, 2)} unit="ha" label="Irrigated" color={colors.water} />
              <Stat value={number(irrigation.notIrrigatedHa ?? 0, 2)} unit="ha" label="Not irrigated" />
            </View>
          </View>
        ) : (
          <Missing>Fill in the irrigated area in the parcel editor.</Missing>
        )}
      </Panel>
    </Section>
  );
}

function IrrigationCard({ run }: { run: ActiveIrrigation }) {
  const progress = useLiveProgress(run);
  const paused = run.status === 'PAUSED';
  return (
    <Panel>
      <View style={styles.inline}>
        <MaterialCommunityIcons name="sprinkler-variant" size={24} color={colors.water} />
        <Txt variant="subtitle" style={{ flex: 1 }}>{run.label}</Txt>
        <Txt color={paused ? colors.straw : colors.water}>{paused ? 'Paused' : 'Running'}</Txt>
      </View>
      <ProgressTrack percent={progress.percent} color={paused ? colors.straw : colors.water} />
      <Row label="Speed" value={`${number(run.speedMPerH, 0)} m/h`} />
      <Row label="Time left" value={hoursMinutes(progress.remainingSeconds)} />
      <Row label="ETA" value={progress.eta ? clock(progress.eta) : 'Stopped'} />
    </Panel>
  );
}

export function ActiveIrrigationSection({ data }: { data: ParcelIntelligence }) {
  if (data.activeIrrigation.length === 0) return null;
  return (
    <Section title="Active irrigation systems">
      {data.activeIrrigation.map((run) => <IrrigationCard key={run.id} run={run} />)}
    </Section>
  );
}

function WorkCard({ work, onTrack }: { work: ActiveWork; onTrack: (work: ActiveWork) => void }) {
  const progress = useLiveProgress(work);
  return (
    <Panel>
      <View style={styles.inline}>
        <MaterialCommunityIcons name="tractor-variant" size={24} color={colors.wheat} />
        <Txt variant="subtitle" style={{ flex: 1 }}>{work.machine?.name ?? 'No machine'}</Txt>
        <Txt variant="figure" color={colors.wheat}>{`${Math.floor(progress.percent)}%`}</Txt>
      </View>
      <ProgressTrack percent={progress.percent} />
      <Row label="Operator" value={work.operator.fullName} />
      <Row label="Operation" value={WORK_TYPES[work.type]} />
      <Row label="Width" value={`${number(work.workingWidthM)} m`} />
      <Row label="Speed" value={`${number(work.speedKmh)} km/h`} />
      <Row label="ETA" value={work.status === 'PAUSED' ? 'Paused' : hoursMinutes(progress.remainingSeconds)} />
      <Button mode="outlined" icon="map-marker-radius-outline" onPress={() => onTrack(work)}>See live position</Button>
    </Panel>
  );
}

export function ActiveWorkSection({ data, onTrack }: { data: ParcelIntelligence; onTrack: (work: ActiveWork) => void }) {
  if (data.activeWork.length === 0) return null;
  return (
    <Section title="Active operations">
      {data.activeWork.map((work) => <WorkCard key={work.id} work={work} onTrack={onTrack} />)}
    </Section>
  );
}

export function NextActionSection({ data }: { data: ParcelIntelligence }) {
  const { nextAction, header } = data;
  return (
    <Section title="Next recommended action">
      <Panel style={{ borderLeftWidth: 4, borderLeftColor: LEVEL_COLOR[nextAction.priority] }}>
        <Txt variant="title">{nextAction.action}</Txt>
        <Txt color={colors.straw}>{nextAction.reason}</Txt>
        <Row label="Priority" value={<Txt color={LEVEL_COLOR[nextAction.priority]}>{LEVEL_LABEL[nextAction.priority]}</Txt>} />
        <Row label="Risk level" value={<Txt color={LEVEL_COLOR[nextAction.riskLevel]}>{LEVEL_LABEL[nextAction.riskLevel]}</Txt>} />
        <Row label="Confidence level" value={`${nextAction.confidencePct}%`} />
        <AiExplain parcelId={header.id} question={`Explain why this is recommended: ${nextAction.action}`} />
      </Panel>
    </Section>
  );
}

const TASK_FLOW: Record<TaskStatus, { next: TaskStatus; label: string; status: string }> = {
  TODO: { next: 'IN_PROGRESS', label: 'Start', status: 'To do' },
  IN_PROGRESS: { next: 'DONE', label: 'Complete', status: 'In progress' },
  DONE: { next: 'TODO', label: 'Reopen', status: 'Done' },
};

export function TasksSection({ data }: { data: ParcelIntelligence }) {
  const { summary, items } = data.tasks;
  const move = useAction<{ id: string; status: TaskStatus }>(
    async ({ id, status }) => {
      await apiSend('POST', `/tasks/${id}/status`, { status });
    },
    [intelligenceKey(data.header.id), ['tasks'], ['dashboard']],
  );
  return (
    <Section title="Tasks">
      <Panel>
        <View style={styles.grid}>
          <Stat value={String(summary.todo)} label="To do" />
          <Stat value={String(summary.inProgress)} label="In progress" color={colors.wheat} />
          <Stat value={String(summary.done)} label="Done" color={colors.leaf} />
        </View>
        {items.slice(0, 8).map((task) => (
          <View key={task.id} style={styles.inline}>
            <View style={{ flex: 1 }}>
              <Txt>{task.title || TASK_TYPES[task.type]}</Txt>
              <Txt variant="small">{`${TASK_FLOW[task.status].status}, due ${shortDate(task.dueDate)}`}</Txt>
            </View>
            <Pressable accessibilityRole="button" style={styles.textAction} onPress={() => move.mutate({ id: task.id, status: TASK_FLOW[task.status].next })}>
              <Txt color={colors.wheat}>{TASK_FLOW[task.status].label}</Txt>
            </Pressable>
          </View>
        ))}
      </Panel>
    </Section>
  );
}

function ForecastRow({ day }: { day: WeatherDay }) {
  return (
    <Row
      label={shortDate(day.date)}
      value={`${Math.round(day.tMinC)}° / ${Math.round(day.tMaxC)}°, ${number(day.precipitationMm)} mm, ${day.rainProbabilityPct ?? 0}%`}
    />
  );
}

export function WeatherSection({ data }: { data: ParcelIntelligence }) {
  const [range, setRange] = useState<'today' | '7' | '14'>('today');
  const { weather } = data;
  if (!weather) {
    return (
      <Section title="Weather">
        <Panel><Missing>The forecast is not available right now.</Missing></Panel>
      </Section>
    );
  }
  const { current, today } = weather;
  return (
    <Section title="Weather">
      {weather.alerts.map((alert) => (
        <View key={alert.code} style={styles.alert} accessibilityRole="alert">
          <MaterialCommunityIcons name={alert.code === 'WIND' ? 'weather-windy' : 'weather-pouring'} size={20} color={colors.wheatInk} />
          <Txt color={colors.wheatInk} style={{ flex: 1 }}>{alert.message}</Txt>
        </View>
      ))}
      <Panel>
        <Choice
          options={[
            { value: 'today', label: 'Today' },
            { value: '7', label: '7 days' },
            { value: '14', label: '14 days' },
          ]}
          value={range}
          onChange={setRange}
        />
        {range === 'today' && current ? (
          <View style={styles.grid}>
            <Stat value={`${Math.round(current.temperatureC)}°`} label="Temperature" />
            <Stat value={`${Math.round(current.humidityPct)}%`} label="Humidity" />
            <Stat value={number(today?.precipitationMm ?? current.precipitationMm)} unit="mm" label="Rain today" color={colors.water} />
            <Stat value={String(Math.round(current.windKmh))} unit="km/h" label="Wind" />
            <Stat value={number(today?.et0Mm ?? 0)} unit="mm" label="Evapotranspiration" />
          </View>
        ) : null}
        {range === '7' ? weather.next7.map((day) => <ForecastRow key={day.date} day={day} />) : null}
        {range === '14' ? weather.next14.map((day) => <ForecastRow key={day.date} day={day} />) : null}
      </Panel>
    </Section>
  );
}

function IndexBar({ label, value }: { label: string; value: number }) {
  return (
    <View style={{ gap: space.xs }}>
      <Row label={label} value={`${value}/100`} />
      <ProgressTrack percent={value} color={value >= 65 ? colors.leaf : value >= 40 ? colors.amber : colors.rust} />
    </View>
  );
}

export function SoilSection({ data }: { data: ParcelIntelligence }) {
  const { soil } = data;
  return (
    <Section title="Soil analysis">
      <Panel>
        {soil.status !== 'READY' ? (
          <Missing>{soil.errorMessage ?? 'The soil profile is being analysed.'}</Missing>
        ) : (
          <>
            <Txt variant="title">{soil.label}</Txt>
            <Row label="pH" value={soil.ph} />
            <Row label="Clay" value={`${soil.clayPct}%`} />
            <Row label="Sand" value={`${soil.sandPct}%`} />
            <Row label="Silt" value={`${soil.siltPct}%`} />
            <Row label="Organic matter" value={`${soil.organicMatterPct}%`} />
            <Row label="Organic carbon" value={`${soil.organicCarbonGKg} g/kg`} />
            {soil.indices ? (
              <>
                <IndexBar label="Soil score" value={soil.indices.soilScore} />
                <IndexBar label="Fertility index" value={soil.indices.fertilityIndex} />
                <IndexBar label="Water retention index" value={soil.indices.waterRetentionIndex} />
              </>
            ) : null}
            <Txt variant="small">Indicative values, estimated from SoilGrids maps.</Txt>
          </>
        )}
      </Panel>
    </Section>
  );
}

export function RecommendationsSection({ parcelId }: { parcelId: string }) {
  const query = useCropRecommendations(parcelId);
  return (
    <Section title={query.data ? `Recommended crops for ${query.data.year}` : 'Recommended crops'}>
      <Panel>
        {query.isLoading ? <ActivityIndicator color={colors.wheat} accessibilityLabel="Calculating recommendations" /> : null}
        {query.isError ? <Missing>{errorMessage(query.error)}</Missing> : null}
        {query.data?.recommendations.slice(0, 4).map((item) => (
          <View key={item.crop} style={{ gap: space.xs }}>
            <Row label={item.name} value={`${item.score}%`} />
            <ProgressTrack percent={item.score} color={item.score >= 75 ? colors.leaf : item.score >= 55 ? colors.amber : colors.rust} />
            <Txt variant="small">{item.reasons.slice(1, 3).join(' ') || item.reasons[0]}</Txt>
          </View>
        ))}
      </Panel>
    </Section>
  );
}

export function EconomicsSection({ data, canEdit }: { data: ParcelIntelligence; canEdit: boolean }) {
  const { economics } = data;
  const [yieldTHa, setYieldTHa] = useState('');
  const [price, setPrice] = useState('');
  const save = useAction<void>(
    async () => {
      await apiSend('PATCH', `/seasons/${economics?.seasonId}`, {
        expectedYieldTHa: parseNumber(yieldTHa),
        expectedPricePerT: parseNumber(price),
      });
    },
    [intelligenceKey(data.header.id)],
  );
  if (!economics) return null;
  const euro = (value: number | null) => (value === null ? 'Insufficient data' : formatEuro(value, 0));
  const pct = (value: number | null) => (value === null ? 'Insufficient data' : `${number(value)}%`);
  return (
    <Section title={`Economics ${economics.year}`}>
      <Panel>
        <Row label="Seeds" value={euro(economics.seedsCost)} />
        <Row label="Fertilizers" value={euro(economics.fertilizerCost)} />
        <Row label="Chemicals" value={euro(economics.chemicalCost)} />
        <Row label="Fuel" value={euro(economics.fuelCost)} />
        <Row label="Labour" value={euro(economics.laborCost)} />
        <Row label="Irrigation and other" value={euro(economics.irrigationCost + economics.otherCost)} />
        <View style={styles.grid}>
          <Stat value={formatEuro(economics.totalCost, 0)} label="Total cost" />
          <Stat value={formatEuro(economics.costPerHa, 2)} unit="/ha" label="Cost per hectare" />
        </View>
        <Row label="Estimated revenue" value={euro(economics.expectedIncome)} />
        <Row label="Estimated profit" value={<Txt color={(economics.expectedProfit ?? 0) >= 0 ? colors.leaf : colors.rust}>{euro(economics.expectedProfit)}</Txt>} />
        <Row label="Profit margin" value={pct(economics.profitMarginPct)} />
        <Row label="ROI" value={pct(economics.roiPct)} />
        {canEdit && economics.seasonId && economics.expectedIncome === null ? (
          <View style={{ gap: space.sm }}>
            <Txt variant="small">Fill in the yield and price to calculate the profit.</Txt>
            <View style={{ flexDirection: 'row', gap: space.sm }}>
              <SheetInput label="Yield (t/ha)" value={yieldTHa} onChangeText={setYieldTHa} keyboardType="decimal-pad" />
              <SheetInput label="Price (€/t)" value={price} onChangeText={setPrice} keyboardType="decimal-pad" />
            </View>
            <Button mode="contained" disabled={!isPositive(yieldTHa) || !isPositive(price) || save.isPending} loading={save.isPending} onPress={() => save.mutate()}>
              Calculate profit
            </Button>
          </View>
        ) : null}
      </Panel>
    </Section>
  );
}

function SatelliteColumn({ title, point }: { title: string; point: SatellitePoint | null }) {
  const color = point ? HEALTH_COLOR[point.health.color] : colors.straw;
  return (
    <View style={styles.satColumn}>
      <Txt variant="small">{title}</Txt>
      {point ? (
        <>
          <Txt variant="figure" color={color}>{point.ndvi.toFixed(2)}</Txt>
          <Txt variant="small" color={color}>{HEALTH_LABEL[point.health.color]}</Txt>
          <Txt variant="small">{`NDRE ${point.ndre === null ? '-' : point.ndre.toFixed(2)}`}</Txt>
          <Txt variant="small">{`Index ${point.healthIndex}`}</Txt>
          <Txt variant="small">{shortDate(point.date)}</Txt>
        </>
      ) : (
        <Txt variant="small">No image</Txt>
      )}
    </View>
  );
}

export function SatelliteSection({ data, canRefresh }: { data: ParcelIntelligence; canRefresh: boolean }) {
  const { satellite, header } = data;
  const refresh = useAction<void>(
    async () => {
      await apiSend('POST', `/parcels/${header.id}/satellite/refresh`, { days: 45 }, { offline: false });
    },
    [intelligenceKey(header.id), ['parcels']],
  );
  return (
    <Section title="Satellite monitoring">
      <Panel>
        <View style={{ flexDirection: 'row', gap: space.sm }}>
          <SatelliteColumn title="Latest image" point={satellite.latest} />
          <SatelliteColumn title="7 days ago" point={satellite.sevenDaysAgo} />
          <SatelliteColumn title="30 days ago" point={satellite.thirtyDaysAgo} />
        </View>
        {canRefresh ? (
          <Button mode="outlined" icon="satellite-variant" loading={refresh.isPending} disabled={refresh.isPending} onPress={() => refresh.mutate()}>
            Download Sentinel-2 images
          </Button>
        ) : null}
      </Panel>
    </Section>
  );
}

const styles = StyleSheet.create({
  grid: { flexDirection: 'row', flexWrap: 'wrap', rowGap: space.md },
  inline: { flexDirection: 'row', alignItems: 'center', gap: space.md },
  alert: { flexDirection: 'row', alignItems: 'center', gap: space.sm, backgroundColor: colors.amber, borderRadius: 6, padding: space.md },
  textAction: { minHeight: 44, justifyContent: 'center', paddingHorizontal: space.sm },
  satColumn: { flex: 1, gap: 2 },
  input: {
    minHeight: 44,
    borderWidth: 1,
    borderColor: colors.line,
    borderRadius: 6,
    paddingHorizontal: space.md,
    color: colors.chaff,
    fontFamily: fonts.regular,
    fontSize: 15,
    backgroundColor: colors.loam,
  },
  inputMultiline: { minHeight: 84, paddingVertical: space.sm, textAlignVertical: 'top' },
});
