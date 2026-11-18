import { MaterialCommunityIcons } from '@expo/vector-icons';
import { BottomSheetTextInput } from '@gorhom/bottom-sheet';
import type { CropCode } from '@fermapp/domain';
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
  NOT_SOWN: { label: 'Nesemănat', color: colors.straw },
  GROWING: { label: 'În vegetație', color: colors.leaf },
  READY: { label: 'Aproape de recoltare', color: colors.wheat },
  HARVESTED: { label: 'Recoltat', color: colors.straw },
} as const;

const VERDICT_COLOR = { RECOMMENDED: colors.leaf, ACCEPTABLE: colors.amber, NOT_RECOMMENDED: colors.rust } as const;
const LEVEL_COLOR = { LOW: colors.leaf, MEDIUM: colors.amber, HIGH: colors.rust } as const;
const LEVEL_LABEL = { LOW: 'Scăzut', MEDIUM: 'Mediu', HIGH: 'Ridicat' } as const;
const HEALTH_COLOR = { green: colors.leaf, yellow: colors.amber, red: colors.rust } as const;
const HEALTH_LABEL = { green: 'Sănătos', yellow: 'Atenție', red: 'Critic' } as const;

const dateText = (value: string | null | undefined) => (value ? toRoDate(new Date(value)) : 'Necompletată');

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

/** Cere asistentului o explicație pe baza datelor reale ale parcelei. */
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
        Explică mai detaliat
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
        <Stat value={number(header.areaHa, 2)} unit="ha" label="Suprafață" color={colors.wheat} />
        <Stat value={header.code} label="Cod parcelă" />
      </View>
      <Row label="Județ" value={header.county ?? 'Necompletat'} />
      <Row label="Localitate" value={header.locality ?? 'Necompletată'} />
      <Row label="Coordonate GPS" value={`${header.lat.toFixed(5)}, ${header.lng.toFixed(5)}`} />
    </View>
  );
}

export function CropSection({ data }: { data: ParcelIntelligence }) {
  const { crop } = data;
  if (!crop) {
    return (
      <Section title="Cultura curentă">
        <Panel><Missing>Nu există o cultură înregistrată pentru acest an.</Missing></Panel>
      </Section>
    );
  }
  const status = CROP_STATUS[crop.status];
  return (
    <Section title="Cultura curentă">
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
        <Row label="Semănat" value={dateText(crop.sowingDate)} />
        <Row label="Recoltare estimată" value={dateText(crop.estimatedHarvestDate)} />
        <Row label="Zi de vegetație" value={`${crop.vegetationDay} / ${crop.totalDays} zile`} />
      </Panel>
    </Section>
  );
}

export function RotationSection({ data }: { data: ParcelIntelligence }) {
  const { rotation, header } = data;
  const color = VERDICT_COLOR[rotation.verdict];
  return (
    <Section title="Rotația culturilor">
      <Panel>
        <View style={styles.inline}>
          <CircularProgress percent={rotation.score} size={76} color={color} />
          <View style={{ flex: 1, gap: 2 }}>
            <Txt variant="subtitle" color={color}>{rotation.label}</Txt>
            {rotation.mode === 'SUGGESTED' ? <Txt variant="small">Cea mai bună opțiune pentru acest an</Txt> : null}
            <Txt variant="small">{rotation.explanation}</Txt>
          </View>
        </View>
        {rotation.history.length === 0 ? <Missing>Adaugă istoricul culturilor pentru un scor mai precis.</Missing> : null}
        {rotation.history.map((item) => (
          <Row key={item.year} label={String(item.year)} value={item.name} />
        ))}
        <AiExplain parcelId={header.id} question="Explică scorul de rotație al acestei parcele și ce risc există." />
      </Panel>
    </Section>
  );
}

export function IrrigationCoverageSection({ data }: { data: ParcelIntelligence }) {
  const { irrigation } = data;
  return (
    <Section title="Irigații">
      <Panel>
        {irrigation.configured ? (
          <View style={styles.inline}>
            <CircularProgress percent={irrigation.coveragePct ?? 0} icon="water" />
            <View style={{ flex: 1, gap: space.sm }}>
              <Stat value={number(irrigation.irrigatedHa ?? 0, 2)} unit="ha" label="Irigat" color={colors.water} />
              <Stat value={number(irrigation.notIrrigatedHa ?? 0, 2)} unit="ha" label="Neirigat" />
            </View>
          </View>
        ) : (
          <Missing>Completează suprafața irigată din editorul parcelei.</Missing>
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
        <Txt color={paused ? colors.straw : colors.water}>{paused ? 'În pauză' : 'Pornit'}</Txt>
      </View>
      <ProgressTrack percent={progress.percent} color={paused ? colors.straw : colors.water} />
      <Row label="Viteză" value={`${number(run.speedMPerH, 0)} m/h`} />
      <Row label="Timp rămas" value={hoursMinutes(progress.remainingSeconds)} />
      <Row label="ETA" value={progress.eta ? clock(progress.eta) : 'Oprit'} />
    </Panel>
  );
}

export function ActiveIrrigationSection({ data }: { data: ParcelIntelligence }) {
  if (data.activeIrrigation.length === 0) return null;
  return (
    <Section title="Sisteme de irigații active">
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
        <Txt variant="subtitle" style={{ flex: 1 }}>{work.machine?.name ?? 'Fără utilaj'}</Txt>
        <Txt variant="figure" color={colors.wheat}>{`${Math.floor(progress.percent)}%`}</Txt>
      </View>
      <ProgressTrack percent={progress.percent} />
      <Row label="Operator" value={work.operator.fullName} />
      <Row label="Lucrare" value={WORK_TYPES[work.type]} />
      <Row label="Lățime" value={`${number(work.workingWidthM)} m`} />
      <Row label="Viteză" value={`${number(work.speedKmh)} km/h`} />
      <Row label="ETA" value={work.status === 'PAUSED' ? 'În pauză' : hoursMinutes(progress.remainingSeconds)} />
      <Button mode="outlined" icon="map-marker-radius-outline" onPress={() => onTrack(work)}>Vezi poziția live</Button>
    </Panel>
  );
}

export function ActiveWorkSection({ data, onTrack }: { data: ParcelIntelligence; onTrack: (work: ActiveWork) => void }) {
  if (data.activeWork.length === 0) return null;
  return (
    <Section title="Lucrări active">
      {data.activeWork.map((work) => <WorkCard key={work.id} work={work} onTrack={onTrack} />)}
    </Section>
  );
}

export function NextActionSection({ data }: { data: ParcelIntelligence }) {
  const { nextAction, header } = data;
  return (
    <Section title="Următoarea acțiune recomandată">
      <Panel style={{ borderLeftWidth: 4, borderLeftColor: LEVEL_COLOR[nextAction.priority] }}>
        <Txt variant="title">{nextAction.action}</Txt>
        <Txt color={colors.straw}>{nextAction.reason}</Txt>
        <Row label="Prioritate" value={<Txt color={LEVEL_COLOR[nextAction.priority]}>{LEVEL_LABEL[nextAction.priority]}</Txt>} />
        <Row label="Nivel de risc" value={<Txt color={LEVEL_COLOR[nextAction.riskLevel]}>{LEVEL_LABEL[nextAction.riskLevel]}</Txt>} />
        <Row label="Nivel de încredere" value={`${nextAction.confidencePct}%`} />
        <AiExplain parcelId={header.id} question={`Explică de ce se recomandă: ${nextAction.action}`} />
      </Panel>
    </Section>
  );
}

const TASK_FLOW: Record<TaskStatus, { next: TaskStatus; label: string; status: string }> = {
  TODO: { next: 'IN_PROGRESS', label: 'Începe', status: 'De făcut' },
  IN_PROGRESS: { next: 'DONE', label: 'Finalizează', status: 'În lucru' },
  DONE: { next: 'TODO', label: 'Redeschide', status: 'Finalizat' },
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
    <Section title="Task-uri">
      <Panel>
        <View style={styles.grid}>
          <Stat value={String(summary.todo)} label="De făcut" />
          <Stat value={String(summary.inProgress)} label="În lucru" color={colors.wheat} />
          <Stat value={String(summary.done)} label="Finalizate" color={colors.leaf} />
        </View>
        {items.slice(0, 8).map((task) => (
          <View key={task.id} style={styles.inline}>
            <View style={{ flex: 1 }}>
              <Txt>{task.title || TASK_TYPES[task.type]}</Txt>
              <Txt variant="small">{`${TASK_FLOW[task.status].status}, termen ${shortDate(task.dueDate)}`}</Txt>
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
      <Section title="Meteo">
        <Panel><Missing>Prognoza nu este disponibilă momentan.</Missing></Panel>
      </Section>
    );
  }
  const { current, today } = weather;
  return (
    <Section title="Meteo">
      {weather.alerts.map((alert) => (
        <View key={alert.code} style={styles.alert} accessibilityRole="alert">
          <MaterialCommunityIcons name={alert.code === 'WIND' ? 'weather-windy' : 'weather-pouring'} size={20} color={colors.wheatInk} />
          <Txt color={colors.wheatInk} style={{ flex: 1 }}>{alert.message}</Txt>
        </View>
      ))}
      <Panel>
        <Choice
          options={[
            { value: 'today', label: 'Azi' },
            { value: '7', label: '7 zile' },
            { value: '14', label: '14 zile' },
          ]}
          value={range}
          onChange={setRange}
        />
        {range === 'today' && current ? (
          <View style={styles.grid}>
            <Stat value={`${Math.round(current.temperatureC)}°`} label="Temperatură" />
            <Stat value={`${Math.round(current.humidityPct)}%`} label="Umiditate" />
            <Stat value={number(today?.precipitationMm ?? current.precipitationMm)} unit="mm" label="Precipitații azi" color={colors.water} />
            <Stat value={String(Math.round(current.windKmh))} unit="km/h" label="Vânt" />
            <Stat value={number(today?.et0Mm ?? 0)} unit="mm" label="Evapotranspirație" />
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
    <Section title="Analiza solului">
      <Panel>
        {soil.status !== 'READY' ? (
          <Missing>{soil.errorMessage ?? 'Profilul de sol este în curs de analiză.'}</Missing>
        ) : (
          <>
            <Txt variant="title">{soil.label}</Txt>
            <Row label="pH" value={soil.ph} />
            <Row label="Argilă" value={`${soil.clayPct}%`} />
            <Row label="Nisip" value={`${soil.sandPct}%`} />
            <Row label="Praf" value={`${soil.siltPct}%`} />
            <Row label="Materie organică" value={`${soil.organicMatterPct}%`} />
            <Row label="Carbon organic" value={`${soil.organicCarbonGKg} g/kg`} />
            {soil.indices ? (
              <>
                <IndexBar label="Scor sol" value={soil.indices.soilScore} />
                <IndexBar label="Indice de fertilitate" value={soil.indices.fertilityIndex} />
                <IndexBar label="Indice de retenție a apei" value={soil.indices.waterRetentionIndex} />
              </>
            ) : null}
            <Txt variant="small">Valori orientative, estimate din hărțile SoilGrids.</Txt>
          </>
        )}
      </Panel>
    </Section>
  );
}

export function RecommendationsSection({ parcelId }: { parcelId: string }) {
  const query = useCropRecommendations(parcelId);
  return (
    <Section title={query.data ? `Culturi recomandate pentru ${query.data.year}` : 'Culturi recomandate'}>
      <Panel>
        {query.isLoading ? <ActivityIndicator color={colors.wheat} accessibilityLabel="Se calculează recomandările" /> : null}
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
  const lei = (value: number | null) => (value === null ? 'Date insuficiente' : `${number(value, 0)} lei`);
  const pct = (value: number | null) => (value === null ? 'Date insuficiente' : `${number(value)}%`);
  return (
    <Section title={`Economie ${economics.year}`}>
      <Panel>
        <Row label="Semințe" value={lei(economics.seedsCost)} />
        <Row label="Îngrășăminte" value={lei(economics.fertilizerCost)} />
        <Row label="Produse chimice" value={lei(economics.chemicalCost)} />
        <Row label="Combustibil" value={lei(economics.fuelCost)} />
        <Row label="Manoperă" value={lei(economics.laborCost)} />
        <Row label="Irigații și altele" value={lei(economics.irrigationCost + economics.otherCost)} />
        <View style={styles.grid}>
          <Stat value={number(economics.totalCost, 0)} unit="lei" label="Cost total" />
          <Stat value={number(economics.costPerHa, 0)} unit="lei/ha" label="Cost pe hectar" />
        </View>
        <Row label="Venit estimat" value={lei(economics.expectedIncome)} />
        <Row label="Profit estimat" value={<Txt color={(economics.expectedProfit ?? 0) >= 0 ? colors.leaf : colors.rust}>{lei(economics.expectedProfit)}</Txt>} />
        <Row label="Marjă de profit" value={pct(economics.profitMarginPct)} />
        <Row label="ROI" value={pct(economics.roiPct)} />
        {canEdit && economics.seasonId && economics.expectedIncome === null ? (
          <View style={{ gap: space.sm }}>
            <Txt variant="small">Completează producția și prețul pentru a calcula profitul.</Txt>
            <View style={{ flexDirection: 'row', gap: space.sm }}>
              <SheetInput label="Producție (t/ha)" value={yieldTHa} onChangeText={setYieldTHa} keyboardType="decimal-pad" />
              <SheetInput label="Preț (lei/t)" value={price} onChangeText={setPrice} keyboardType="decimal-pad" />
            </View>
            <Button mode="contained" disabled={!isPositive(yieldTHa) || !isPositive(price) || save.isPending} loading={save.isPending} onPress={() => save.mutate()}>
              Calculează profitul
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
          <Txt variant="small">{`Indice ${point.healthIndex}`}</Txt>
          <Txt variant="small">{shortDate(point.date)}</Txt>
        </>
      ) : (
        <Txt variant="small">Fără imagine</Txt>
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
    <Section title="Monitorizare satelitară">
      <Panel>
        <View style={{ flexDirection: 'row', gap: space.sm }}>
          <SatelliteColumn title="Ultima imagine" point={satellite.latest} />
          <SatelliteColumn title="Acum 7 zile" point={satellite.sevenDaysAgo} />
          <SatelliteColumn title="Acum 30 zile" point={satellite.thirtyDaysAgo} />
        </View>
        {canRefresh ? (
          <Button mode="outlined" icon="satellite-variant" loading={refresh.isPending} disabled={refresh.isPending} onPress={() => refresh.mutate()}>
            Descarcă imagini Sentinel-2
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
