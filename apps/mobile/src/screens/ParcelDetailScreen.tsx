import { CROP_CODES, CROPS, type CropCode, type RotationResult, formatEuro } from '@fermapp/domain';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import { useQuery } from '@tanstack/react-query';
import { useLayoutEffect, useState } from 'react';
import { Image, View } from 'react-native';
import { Button } from 'react-native-paper';
import { API_URL, apiGet, apiSend } from '../api/client';
import type { CropSeason, Parcel } from '../api/types';
import { ParcelShape } from '../components/ParcelShape';
import { Choice, Empty, Field, IconButton, Panel, ProgressTrack, QueryState, Row, Screen, Section, Stat, Txt } from '../components/ui';
import { SeedPicker } from '../features/catalog/SeedPicker';
import { fullDate, ha, isPositive, number, parseNumber, parseRoDate, shortDate } from '../lib/format';
import { useAction } from '../lib/hooks';
import type { RootStackParams } from '../navigation/RootNavigator';
import { ParcelEconomicsTab } from '../features/economics';
import { CompletionScore, FieldStatusBadge, useFieldStatus } from '../features/fieldStatus';
import { TillageBadge, TillageSection, useParcelById } from '../features/tillage';
import { canManage, canPlan, useAuth } from '../store/auth';
import { colors, healthColor, space } from '../theme';

type Props = NativeStackScreenProps<RootStackParams, 'ParcelDetail'>;
type Tab = 'crops' | 'economics' | 'tillage' | 'rotation' | 'soil' | 'weather' | 'windows' | 'water' | 'satellite' | 'costs';

const TABS: { value: Tab; label: string }[] = [
  { value: 'crops', label: 'Crops' },
  { value: 'economics', label: 'Economic analysis' },
  { value: 'tillage', label: 'Tillage' },
  { value: 'rotation', label: 'Rotation' },
  { value: 'soil', label: 'Soil' },
  { value: 'weather', label: 'Weather' },
  { value: 'windows', label: 'Work windows' },
  { value: 'water', label: 'Water' },
  { value: 'satellite', label: 'Satellite' },
  { value: 'costs', label: 'Expenses' },
];

const cropOptions = CROP_CODES.map((code) => ({ value: code, label: CROPS[code].name }));
const VERDICT_COLOR = { RECOMMENDED: colors.leaf, ACCEPTABLE: colors.amber, NOT_RECOMMENDED: colors.rust } as const;

function SeasonsTab({ parcelId, editable }: { parcelId: string; editable: boolean }) {
  const seasons = useQuery({ queryKey: ['seasons', parcelId], queryFn: () => apiGet<CropSeason[]>(`/parcels/${parcelId}/seasons`) });
  const [crop, setCrop] = useState<CropCode | null>(null);
  const [year, setYear] = useState(String(new Date().getFullYear()));
  const [sowing, setSowing] = useState('');
  const [harvest, setHarvest] = useState('');
  const [yieldT, setYieldT] = useState('');
  const [moisture, setMoisture] = useState('');
  const [notes, setNotes] = useState('');
  const [seed, setSeed] = useState<{ id: string; name: string } | null>(null);
  const validDates = (!sowing || parseRoDate(sowing)) && (!harvest || parseRoDate(harvest));

  const add = useAction<void>(
    async () => {
      await apiSend('POST', `/parcels/${parcelId}/seasons`, {
        crop,
        year: Number(year),
        sowingDate: sowing ? parseRoDate(sowing) : undefined,
        harvestDate: harvest ? parseRoDate(harvest) : undefined,
        yieldT: yieldT ? parseNumber(yieldT) : undefined,
        harvestMoisture: moisture ? parseNumber(moisture) : undefined,
        notes: notes.trim() || undefined,
        seedId: seed?.id,
        seedName: seed?.name,
      });
    },
    [['seasons', parcelId], ['parcels'], ['dashboard'], ['rotation', parcelId]],
    () => {
      setCrop(null);
      setSeed(null);
      setSowing('');
      setHarvest('');
      setYieldT('');
      setMoisture('');
      setNotes('');
    },
  );

  return (
    <>
      <QueryState query={seasons}>
        {(items) =>
          items.length === 0 ? (
            <Empty icon="sprout-outline" title="No crop history" hint="Add the crops from recent years to get rotation recommendations." />
          ) : (
            <Panel>
              {items.map((season) => (
                <View key={season.id} style={{ gap: 2, paddingVertical: space.xs }}>
                  <Row label={String(season.year)} value={[CROPS[season.crop].name, season.seedName].filter(Boolean).join(', ')} />
                  {season.yieldT != null || season.sowingDate ? (
                    <Txt variant="small">
                      {[
                        season.sowingDate ? `Sown ${shortDate(season.sowingDate)}` : null,
                        season.harvestDate ? `Harvested ${shortDate(season.harvestDate)}` : null,
                        season.yieldT != null ? `${season.yieldT} t` : null,
                        season.harvestMoisture != null ? `moisture ${season.harvestMoisture}%` : null,
                      ].filter(Boolean).join(', ')}
                    </Txt>
                  ) : null}
                </View>
              ))}
            </Panel>
          )
        }
      </QueryState>
      {editable ? (
        <Section title="Add a crop">
          <Panel>
            <Choice
              label="Crop"
              options={cropOptions}
              value={crop}
              onChange={(value) => {
                setCrop(value);
                setSeed(null);
              }}
            />
            <SeedPicker crop={crop} parcelId={parcelId} value={seed?.id ?? null} onChange={setSeed} />
            <Field label="Year" value={year} onChangeText={setYear} keyboardType="number-pad" />
            <Field label="Sowing date (DD.MM.YYYY)" value={sowing} onChangeText={setSowing} />
            <Field label="Harvest date (DD.MM.YYYY)" value={harvest} onChangeText={setHarvest} />
            <Field label="Yield (tonnes)" value={yieldT} onChangeText={setYieldT} keyboardType="decimal-pad" />
            <Field label="Harvest moisture (%)" value={moisture} onChangeText={setMoisture} keyboardType="decimal-pad" />
            <Field label="Notes" value={notes} onChangeText={setNotes} multiline />
            <Button mode="contained" disabled={!crop || !/^\d{4}$/.test(year) || !validDates || add.isPending} loading={add.isPending} onPress={() => add.mutate()}>
              Save crop
            </Button>
          </Panel>
        </Section>
      ) : null}
    </>
  );
}

function RotationTab({ parcelId }: { parcelId: string }) {
  const year = new Date().getFullYear() + 1;
  const options = useQuery({
    queryKey: ['rotation', parcelId, year],
    queryFn: () => apiGet<RotationResult[]>(`/parcels/${parcelId}/rotation/options`, { year }),
  });
  const recommendations = useQuery({
    queryKey: ['recommendations', parcelId, year],
    queryFn: () =>
      apiGet<{ recommendations: { crop: CropCode; name: string; score: number; reasons: string[]; missingData: string[] }[] }>(
        `/parcels/${parcelId}/recommendations`,
        { year },
      ),
  });
  return (
    <>
      <Section title={`Rotation for ${year}`}>
        <QueryState query={options}>
          {(items) => (
            <>
              {items.map((item) => (
                <Panel key={item.crop}>
                  <View style={{ flexDirection: 'row', alignItems: 'center' }}>
                    <Txt variant="subtitle" style={{ flex: 1 }}>{CROPS[item.crop].name}</Txt>
                    <Txt variant="figure" color={VERDICT_COLOR[item.verdict]}>{String(item.score)}</Txt>
                  </View>
                  <Txt color={VERDICT_COLOR[item.verdict]}>{item.label}</Txt>
                  <Txt variant="small">{item.explanation}</Txt>
                </Panel>
              ))}
            </>
          )}
        </QueryState>
      </Section>
      <Section title="Fit by soil and climate">
        <QueryState query={recommendations}>
          {(data) => (
            <Panel>
              {data.recommendations.slice(0, 5).map((item) => (
                <View key={item.crop} style={{ gap: space.xs, paddingVertical: space.xs }}>
                  <Row label={item.name} value={`${item.score}%`} />
                  <ProgressTrack percent={item.score} color={colors.leaf} />
                  <Txt variant="small">{item.reasons.slice(1, 3).join(' ')}</Txt>
                  {item.missingData.length ? <Txt variant="small">{`Missing data: ${item.missingData.join(', ')}.`}</Txt> : null}
                </View>
              ))}
            </Panel>
          )}
        </QueryState>
      </Section>
    </>
  );
}

function SoilTab({ parcelId, editable }: { parcelId: string; editable: boolean }) {
  const soil = useQuery({
    queryKey: ['soil', parcelId],
    queryFn: () =>
      apiGet<{
        status: string;
        label?: string;
        ph?: number;
        clayPct?: number;
        sandPct?: number;
        siltPct?: number;
        organicMatterPct?: number;
        organicCarbonGKg?: number;
        waterHoldingMmPerM?: number;
        errorMessage?: string;
      }>(`/parcels/${parcelId}/soil`),
    refetchInterval: (query) => (query.state.data?.status === 'PENDING' ? 4000 : false),
  });
  const refresh = useAction<void>(async () => {
    await apiSend('POST', `/parcels/${parcelId}/soil/refresh`, undefined, { offline: false });
  }, [['soil', parcelId]]);

  return (
    <QueryState query={soil}>
      {(data) =>
        data.status === 'READY' ? (
          <Panel>
            <Txt variant="title">{data.label}</Txt>
            <Txt variant="small">Indicative classification based on SoilGrids maps.</Txt>
            <Row label="pH" value={data.ph} />
            <Row label="Clay" value={`${data.clayPct}%`} />
            <Row label="Sand" value={`${data.sandPct}%`} />
            <Row label="Silt" value={`${data.siltPct}%`} />
            <Row label="Organic matter" value={`${data.organicMatterPct}%`} />
            <Row label="Organic carbon" value={`${data.organicCarbonGKg} g/kg`} />
            <Row label="Water retention" value={`${data.waterHoldingMmPerM} mm/m`} />
          </Panel>
        ) : (
          <Empty
            icon="layers-outline"
            title={data.status === 'PENDING' ? 'Analysing the soil' : 'The soil profile is not available'}
            hint={data.errorMessage}
            action={data.status === 'FAILED' && editable ? <Button loading={refresh.isPending} onPress={() => refresh.mutate()}>Try again</Button> : undefined}
          />
        )
      }
    </QueryState>
  );
}

interface ForecastDay {
  date: string;
  tMaxC: number;
  tMinC: number;
  precipitationMm: number;
  et0Mm: number | null;
  radiationMjM2: number | null;
  windMaxKmh: number | null;
  humidityPct: number | null;
}
interface ForecastHour {
  time: string;
  temperatureC: number;
  precipitationMm: number;
  windKmh: number;
  humidityPct: number;
}

function WeatherTab({ parcelId }: { parcelId: string }) {
  const [range, setRange] = useState<'24h' | '7d' | '14d' | '30d' | '12m'>('7d');
  const isHistory = range === '30d' || range === '12m';
  const forecast = useQuery({
    queryKey: ['forecast', parcelId, range],
    enabled: !isHistory,
    queryFn: () => apiGet<{ current: ForecastHour | null; hours: ForecastHour[]; days: ForecastDay[] }>(`/parcels/${parcelId}/weather/forecast`, { range }),
  });
  const history = useQuery({
    queryKey: ['history', parcelId, range],
    enabled: isHistory,
    queryFn: () => apiGet<{ totals: { precipitationMm: number; et0Mm: number }; days: ForecastDay[] }>(`/parcels/${parcelId}/weather/history`, { range }),
  });

  return (
    <>
      <Choice
        options={[
          { value: '24h', label: '24 hours' },
          { value: '7d', label: '7 days' },
          { value: '14d', label: '14 days' },
          { value: '30d', label: 'Last 30 days' },
          { value: '12m', label: 'Last 12 months' },
        ]}
        value={range}
        onChange={setRange}
      />
      {isHistory ? (
        <QueryState query={history}>
          {(data) => (
            <Panel style={{ flexDirection: 'row' }}>
              <Stat value={number(data.totals.precipitationMm)} unit="mm" label="Rainfall" color={colors.water} />
              <Stat value={number(data.totals.et0Mm)} unit="mm" label="Evapotranspiration" />
            </Panel>
          )}
        </QueryState>
      ) : (
        <QueryState query={forecast}>
          {(data) => (
            <Panel>
              {data.hours.map((hour) => (
                <Row key={hour.time} label={hour.time.slice(11, 16)} value={`${Math.round(hour.temperatureC)}°, ${hour.precipitationMm} mm, wind ${Math.round(hour.windKmh)} km/h`} />
              ))}
              {data.days.map((day) => (
                <View key={day.date} style={{ paddingVertical: space.xs }}>
                  <Row label={shortDate(day.date)} value={`${Math.round(day.tMinC)}° / ${Math.round(day.tMaxC)}°, ${day.precipitationMm} mm`} />
                  <Txt variant="small">
                    {`Wind ${Math.round(day.windMaxKmh ?? 0)} km/h, humidity ${Math.round(day.humidityPct ?? 0)}%, ET₀ ${day.et0Mm ?? 0} mm, radiation ${day.radiationMjM2 ?? 0} MJ/m²`}
                  </Txt>
                </View>
              ))}
            </Panel>
          )}
        </QueryState>
      )}
    </>
  );
}

function WindowsTab({ parcelId }: { parcelId: string }) {
  const windows = useQuery({
    queryKey: ['windows', parcelId],
    queryFn: () =>
      apiGet<{ operation: string; message: string; windows: { label: string; hours: number; score: number }[] }[]>(`/parcels/${parcelId}/weather/windows`),
  });
  return (
    <QueryState query={windows}>
      {(items) => (
        <>
          {items.map((item) => (
            <Panel key={item.operation}>
              <Txt variant="subtitle">{item.message}</Txt>
              {item.windows.slice(1, 4).map((window) => (
                <Row key={window.label} label={window.label} value={`${window.hours} hours`} />
              ))}
            </Panel>
          ))}
        </>
      )}
    </QueryState>
  );
}

function WaterTab({ parcelId }: { parcelId: string }) {
  const water = useQuery({
    queryKey: ['water', parcelId],
    retry: false,
    queryFn: () =>
      apiGet<{ cropName: string; requiredMm: number; requiredMinMm: number; requiredMaxMm: number; precipitationMm: number; irrigationMm: number; deficitMm: number; coveragePct: number; suggestion: string }>(`/parcels/${parcelId}/water`),
  });
  return (
    <QueryState query={water}>
      {(data) => (
        <Panel>
          <Txt variant="title">{data.cropName}</Txt>
          <ProgressTrack percent={data.coveragePct} color={colors.water} />
          <View style={{ flexDirection: 'row' }}>
            <Stat value={number(data.precipitationMm + data.irrigationMm, 0)} unit="mm" label="Water received" color={colors.water} />
            <Stat value={number(data.deficitMm, 0)} unit="mm" label="Deficit" color={data.deficitMm ? colors.rust : colors.leaf} />
          </View>
          <Row label="Seasonal requirement" value={`${data.requiredMinMm}-${data.requiredMaxMm} mm`} />
          <Row label="Rainfall" value={`${data.precipitationMm} mm`} />
          <Row label="Irrigation" value={`${data.irrigationMm} mm`} />
          <Txt>{data.suggestion}</Txt>
        </Panel>
      )}
    </QueryState>
  );
}

interface Observation {
  id: string;
  date: string;
  ndvi: number;
  ndre: number | null;
  healthIndex: number;
  healthClass: string;
  health: { label: string };
}

function SatelliteTab({ parcelId, editable }: { parcelId: string; editable: boolean }) {
  const token = useAuth((state) => state.accessToken);
  const data = useQuery({
    queryKey: ['satellite', parcelId],
    queryFn: () => apiGet<{ configured: boolean; observations: Observation[] }>(`/parcels/${parcelId}/satellite`),
  });
  const [from, setFrom] = useState<string | null>(null);
  const [to, setTo] = useState<string | null>(null);
  const compare = useQuery({
    queryKey: ['satellite-compare', parcelId, from, to],
    enabled: Boolean(from && to && from !== to),
    queryFn: () => apiGet<{ change: { ndvi: number; healthIndex: number } }>(`/parcels/${parcelId}/satellite/compare`, { from: from!, to: to! }),
  });
  const refresh = useAction<void>(async () => {
    await apiSend('POST', `/parcels/${parcelId}/satellite/refresh`, { days: 90 }, { offline: false });
  }, [['satellite', parcelId], ['parcels']]);

  return (
    <QueryState query={data}>
      {({ configured, observations }) => {
        const dates = observations.map((item) => ({ value: item.date.slice(0, 10), label: shortDate(item.date) }));
        const latest = observations[0];
        return (
          <>
            {!configured ? (
              <Empty icon="satellite-variant" title="Satellite imagery is not enabled" hint="The administrator must add the Copernicus Sentinel account on the server." />
            ) : editable ? (
              <Button mode="outlined" loading={refresh.isPending} onPress={() => refresh.mutate()}>Download latest images</Button>
            ) : null}
            {latest ? (
              <Panel>
                <Image
                  accessibilityLabel={`NDVI map from ${fullDate(latest.date)}`}
                  source={{
                    uri: `${API_URL}/parcels/${parcelId}/satellite/image?date=${latest.date.slice(0, 10)}&index=NDVI`,
                    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
                  }}
                  style={{ width: '100%', aspectRatio: 1, backgroundColor: colors.ridge }}
                  resizeMode="contain"
                />
                <Txt variant="title" color={healthColor(latest.healthClass)}>{latest.health.label}</Txt>
                <View style={{ flexDirection: 'row' }}>
                  <Stat value={String(latest.ndvi)} label="NDVI" />
                  <Stat value={latest.ndre === null ? '-' : String(latest.ndre)} label="NDRE" />
                  <Stat value={String(latest.healthIndex)} label="Health Index" />
                </View>
              </Panel>
            ) : configured ? (
              <Empty icon="image-off-outline" title="No cloud-free image" hint="Download the images for the last 90 days." />
            ) : null}
            {dates.length > 1 ? (
              <Section title="Compare two dates">
                <Panel>
                  <Choice label="From" options={dates} value={from} onChange={setFrom} />
                  <Choice label="To" options={dates} value={to} onChange={setTo} />
                  {compare.data ? (
                    <View style={{ flexDirection: 'row' }}>
                      <Stat value={`${compare.data.change.ndvi > 0 ? '+' : ''}${compare.data.change.ndvi}`} label="NDVI change" color={compare.data.change.ndvi >= 0 ? colors.leaf : colors.rust} />
                      <Stat value={`${compare.data.change.healthIndex > 0 ? '+' : ''}${compare.data.change.healthIndex}`} label="Health Index" />
                    </View>
                  ) : null}
                </Panel>
              </Section>
            ) : null}
            {observations.length > 0 ? (
              <Section title="History">
                <Panel>
                  {observations.map((item) => (
                    <Row key={item.id} label={fullDate(item.date)} value={<Txt color={healthColor(item.healthClass)}>{`NDVI ${item.ndvi}`}</Txt>} />
                  ))}
                </Panel>
              </Section>
            ) : null}
          </>
        );
      }}
    </QueryState>
  );
}

const COST_LABELS = { SEED: 'Seeds', FERTILIZER: 'Fertilizers', PESTICIDE: 'Pesticides', FUEL: 'Fuel', LABOR: 'Labour', IRRIGATION: 'Irrigation', OTHER: 'Other' } as const;
type CostCategory = keyof typeof COST_LABELS;

function CostsTab({ parcelId, editable }: { parcelId: string; editable: boolean }) {
  const year = new Date().getFullYear();
  const summary = useQuery({
    queryKey: ['costs', parcelId, year],
    queryFn: () =>
      apiGet<{ totalCost: number; costPerHa: number; byCategory: Record<CostCategory, number>; estimatedProfit: number | null; actualProfit: number | null }>(`/parcels/${parcelId}/costs/summary`, { year }),
  });
  const [category, setCategory] = useState<CostCategory>('SEED');
  const [amount, setAmount] = useState('');
  const add = useAction<void>(
    async () => {
      await apiSend('POST', '/costs', { parcelId, year, category, amount: parseNumber(amount) });
    },
    [['costs', parcelId, year], ['economics']],
    () => setAmount(''),
  );
  const money = (value: number | null) => (value === null ? 'Insufficient data' : formatEuro(value, 2));

  return (
    <>
      <QueryState query={summary}>
        {(data) => (
          <Panel>
            <View style={{ flexDirection: 'row' }}>
              <Stat value={formatEuro(data.totalCost, 0)} label={`Recorded expenses ${year}`} />
              <Stat value={formatEuro(data.costPerHa, 2)} unit="/ha" label="Per hectare" />
            </View>
            {(Object.keys(COST_LABELS) as CostCategory[]).map((key) => (
              <Row key={key} label={COST_LABELS[key]} value={formatEuro(data.byCategory[key], 2)} />
            ))}
            <Row label="Estimated profit" value={money(data.estimatedProfit)} />
            <Row label="Actual profit" value={money(data.actualProfit)} />
          </Panel>
        )}
      </QueryState>
      {editable ? (
        <Section title="Add a cost">
          <Panel>
            <Choice options={(Object.keys(COST_LABELS) as CostCategory[]).map((value) => ({ value, label: COST_LABELS[value] }))} value={category} onChange={setCategory} />
            <Field label="Amount (€)" value={amount} onChangeText={setAmount} keyboardType="decimal-pad" />
            <Button mode="contained" disabled={!isPositive(amount) || add.isPending} loading={add.isPending} onPress={() => add.mutate()}>Save cost</Button>
          </Panel>
        </Section>
      ) : null}
    </>
  );
}

export function ParcelDetailScreen({ route, navigation }: Props) {
  const { parcelId } = route.params;
  const role = useAuth((state) => state.role);
  const [tab, setTab] = useState<Tab>('crops');
  const parcel = useQuery({ queryKey: ['parcel', parcelId], queryFn: () => apiGet<Parcel>(`/parcels/${parcelId}`) });
  // The farm list carries the same parcel with its current-season crop; it is kept fresh by the map.
  const listed = useParcelById(parcelId);
  const fieldState = useFieldStatus(parcelId);
  const visibleTabs = role === 'OPERATOR' ? TABS.filter((item) => item.value !== 'costs' && item.value !== 'economics') : TABS;

  useLayoutEffect(() => {
    navigation.setOptions({
      headerRight: canManage(role)
        ? () => <IconButton icon="pencil-outline" label="Edit parcel" onPress={() => navigation.navigate('ParcelEditor', { parcelId })} />
        : undefined,
    });
  }, [navigation, parcelId, role]);

  return (
    <Screen>
      <QueryState query={parcel}>
        {(data) => (
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: space.md }}>
            <ParcelShape geometry={data.geometry} size={72} />
            <View style={{ flex: 1 }}>
              <Txt variant="title">{ha(data.areaHa)}</Txt>
              <Txt variant="small">{`${data.code}, perimeter ${number(data.perimeterM, 0)} m`}</Txt>
              {data.locality || data.county ? <Txt variant="small">{[data.locality, data.county].filter(Boolean).join(', ')}</Txt> : null}
            </View>
            <TillageBadge system={(listed ?? data).tillageSystem} />
          </View>
        )}
      </QueryState>
      {fieldState ? (
        <View style={{ gap: space.sm }}>
          <FieldStatusBadge status={fieldState} />
          <CompletionScore status={fieldState} />
        </View>
      ) : null}
      <Choice options={visibleTabs} value={tab} onChange={setTab} />
      {tab === 'crops' ? <SeasonsTab parcelId={parcelId} editable={canPlan(role)} /> : null}
      {tab === 'economics' ? <ParcelEconomicsTab parcelId={parcelId} /> : null}
      {tab === 'tillage' && (listed ?? parcel.data) ? (
        <TillageSection parcel={(listed ?? parcel.data)!} canEdit={canManage(role)} crop={(listed ?? parcel.data)?.seasons?.find((season) => season.year === new Date().getFullYear())?.crop} />
      ) : null}
      {tab === 'rotation' ? <RotationTab parcelId={parcelId} /> : null}
      {tab === 'soil' ? <SoilTab parcelId={parcelId} editable={canPlan(role)} /> : null}
      {tab === 'weather' ? <WeatherTab parcelId={parcelId} /> : null}
      {tab === 'windows' ? <WindowsTab parcelId={parcelId} /> : null}
      {tab === 'water' ? <WaterTab parcelId={parcelId} /> : null}
      {tab === 'satellite' ? <SatelliteTab parcelId={parcelId} editable={canPlan(role)} /> : null}
      {tab === 'costs' ? <CostsTab parcelId={parcelId} editable={canManage(role)} /> : null}
    </Screen>
  );
}
