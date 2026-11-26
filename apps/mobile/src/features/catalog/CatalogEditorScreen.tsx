import { createSeedEntry, createTractorEntry, CROP_CODES, CROPS, LEVEL_LABELS, SEED_TYPE_LABELS, type CatalogLevel, type CropCode, type SeedType } from '@fermapp/domain';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import { useEffect, useState } from 'react';
import { View } from 'react-native';
import { Button } from 'react-native-paper';
import { apiSend } from '../../api/client';
import { Choice, Field, Panel, Screen } from '../../components/ui';
import { isPositive, parseNumber } from '../../lib/format';
import { useAction } from '../../lib/hooks';
import type { RootStackParams } from '../../navigation/RootNavigator';
import { space } from '../../theme';
import { useCatalogSeeds, useCatalogTractors } from './api';

type Props = NativeStackScreenProps<RootStackParams, 'CatalogEditor'>;

const NONE = 'NONE';
const levelOptions = [{ value: NONE, label: 'Not filled in' }, ...(['LOW', 'MEDIUM', 'HIGH'] as CatalogLevel[]).map((value) => ({ value, label: LEVEL_LABELS[value] }))];
const toText = (value: number | null | undefined) => (value == null ? '' : String(value));
const toNumber = (value: string) => (isPositive(value) ? parseNumber(value) : null);
const validOptional = (value: string) => !value.trim() || isPositive(value);

function SeedForm({ id, onDone }: { id?: string; onDone: () => void }) {
  const seeds = useCatalogSeeds();
  const existing = seeds.data?.find((item) => item.id === id);
  const [name, setName] = useState('');
  const [brand, setBrand] = useState('');
  const [crop, setCrop] = useState<CropCode | null>(null);
  const [type, setType] = useState<SeedType>('HYBRID');
  const [fao, setFao] = useState('');
  const [days, setDays] = useState('');
  const [yieldMin, setYieldMin] = useState('');
  const [yieldMax, setYieldMax] = useState('');
  const [water, setWater] = useState(NONE);
  const [drought, setDrought] = useState(NONE);
  const [regions, setRegions] = useState('');
  const [notes, setNotes] = useState('');

  useEffect(() => {
    if (!existing) return;
    setName(existing.name);
    setBrand(existing.brand ?? '');
    setCrop(existing.crop);
    setType(existing.type);
    setFao(toText(existing.fao));
    setDays(toText(existing.vegetationDays));
    setYieldMin(toText(existing.expectedYieldMin));
    setYieldMax(toText(existing.expectedYieldMax));
    setWater(existing.waterRequirement ?? NONE);
    setDrought(existing.droughtTolerance ?? NONE);
    setRegions(existing.recommendedRegions.join(', '));
    setNotes(existing.notes ?? '');
  }, [existing]);

  const save = useAction<void>(
    async () => {
      const entry = createSeedEntry({
        ...existing,
        id: existing?.id,
        name: name.trim(),
        crop: crop!,
        type,
        brand: brand.trim() || null,
        fao: toNumber(fao),
        maturityGroup: null,
        vegetationDays: toNumber(days),
        expectedYieldMin: toNumber(yieldMin),
        expectedYieldMax: toNumber(yieldMax),
        yieldPotential: null,
        waterRequirement: water === NONE ? null : (water as CatalogLevel),
        droughtTolerance: drought === NONE ? null : (drought as CatalogLevel),
        recommendedRegions: regions.split(',').map((item) => item.trim()).filter(Boolean),
        notes: notes.trim() || null,
      });
      await apiSend('POST', existing ? `/catalog/seeds/${existing.id}` : '/catalog/seeds', { ...entry, id: undefined });
    },
    [['catalog']],
    onDone,
  );

  const numbersValid = [fao, days, yieldMin, yieldMax].every(validOptional);
  return (
    <Panel>
      <Field label="Hybrid or variety name" value={name} onChangeText={setName} />
      <Field label="Manufacturer" value={brand} onChangeText={setBrand} />
      <Choice label="Crop" options={CROP_CODES.map((value) => ({ value, label: CROPS[value].name }))} value={crop} onChange={setCrop} />
      <Choice label="Type" options={(Object.keys(SEED_TYPE_LABELS) as SeedType[]).map((value) => ({ value, label: SEED_TYPE_LABELS[value] }))} value={type} onChange={setType} />
      <View style={{ flexDirection: 'row', gap: space.sm }}>
        <Field style={{ flex: 1 }} label="FAO" value={fao} onChangeText={setFao} keyboardType="number-pad" />
        <Field style={{ flex: 1 }} label="Growing days" value={days} onChangeText={setDays} keyboardType="number-pad" />
      </View>
      <View style={{ flexDirection: 'row', gap: space.sm }}>
        <Field style={{ flex: 1 }} label="Min yield (t/ha)" value={yieldMin} onChangeText={setYieldMin} keyboardType="decimal-pad" />
        <Field style={{ flex: 1 }} label="Max yield (t/ha)" value={yieldMax} onChangeText={setYieldMax} keyboardType="decimal-pad" />
      </View>
      <Choice label="Water requirement" options={levelOptions} value={water} onChange={setWater} />
      <Choice label="Drought tolerance" options={levelOptions} value={drought} onChange={setDrought} />
      <Field label="Recommended regions, comma-separated" value={regions} onChangeText={setRegions} />
      <Field label="Notes" value={notes} onChangeText={setNotes} multiline />
      <Button mode="contained" disabled={!name.trim() || !crop || !numbersValid || save.isPending} loading={save.isPending} onPress={() => save.mutate()}>
        Save product
      </Button>
    </Panel>
  );
}

function TractorForm({ id, onDone }: { id?: string; onDone: () => void }) {
  const tractors = useCatalogTractors();
  const existing = tractors.data?.find((item) => item.id === id);
  const [brand, setBrand] = useState('');
  const [series, setSeries] = useState('');
  const [model, setModel] = useState('');
  const [power, setPower] = useState('');
  const [fuelMin, setFuelMin] = useState('');
  const [fuelMax, setFuelMax] = useState('');
  const [tank, setTank] = useState('');
  const [weight, setWeight] = useState('');

  useEffect(() => {
    if (!existing) return;
    setBrand(existing.brand);
    setSeries(existing.series ?? '');
    setModel(existing.model);
    setPower(String(existing.horsePower));
    setFuelMin(existing.fuelEstimated ? '' : String(existing.fuelConsumptionMin));
    setFuelMax(existing.fuelEstimated ? '' : String(existing.fuelConsumptionMax));
    setTank(toText(existing.tankCapacity));
    setWeight(toText(existing.weight));
  }, [existing]);

  const save = useAction<void>(
    async () => {
      const entry = createTractorEntry({
        id: existing?.id,
        brand: brand.trim(),
        series: series.trim() || null,
        model: model.trim(),
        horsePower: parseNumber(power),
        fuelConsumptionMin: toNumber(fuelMin) ?? undefined,
        fuelConsumptionMax: toNumber(fuelMax) ?? undefined,
        tankCapacity: toNumber(tank),
        weight: toNumber(weight),
      });
      await apiSend('POST', existing ? `/catalog/tractors/${existing.id}` : '/catalog/tractors', { ...entry, id: undefined });
    },
    [['catalog']],
    onDone,
  );

  const numbersValid = [fuelMin, fuelMax, tank, weight].every(validOptional);
  return (
    <Panel>
      <Field label="Brand" value={brand} onChangeText={setBrand} />
      <Field label="Series" value={series} onChangeText={setSeries} />
      <Field label="Model" value={model} onChangeText={setModel} />
      <Field label="Power (HP)" value={power} onChangeText={setPower} keyboardType="decimal-pad" />
      <View style={{ flexDirection: 'row', gap: space.sm }}>
        <Field style={{ flex: 1 }} label="Min consumption (l/hour)" value={fuelMin} onChangeText={setFuelMin} keyboardType="decimal-pad" />
        <Field style={{ flex: 1 }} label="Max consumption (l/hour)" value={fuelMax} onChangeText={setFuelMax} keyboardType="decimal-pad" />
      </View>
      <View style={{ flexDirection: 'row', gap: space.sm }}>
        <Field style={{ flex: 1 }} label="Tank (l)" value={tank} onChangeText={setTank} keyboardType="decimal-pad" />
        <Field style={{ flex: 1 }} label="Weight (kg)" value={weight} onChangeText={setWeight} keyboardType="decimal-pad" />
      </View>
      <Button mode="contained" disabled={!brand.trim() || !model.trim() || !isPositive(power) || !numbersValid || save.isPending} loading={save.isPending} onPress={() => save.mutate()}>
        Save tractor
      </Button>
    </Panel>
  );
}

export function CatalogEditorScreen({ route, navigation }: Props) {
  const { kind, id } = route.params;
  return <Screen>{kind === 'seed' ? <SeedForm id={id} onDone={() => navigation.goBack()} /> : <TractorForm id={id} onDone={() => navigation.goBack()} />}</Screen>;
}
