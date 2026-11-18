import { CROPS, filterSeeds, filterTractors, LEVEL_LABELS, SEED_TYPE_LABELS, type CatalogLevel, type CropCode } from '@fermapp/domain';
import { useNavigation, type NavigationProp } from '@react-navigation/native';
import * as DocumentPicker from 'expo-document-picker';
import { useMemo, useState } from 'react';
import { Alert, Pressable, View } from 'react-native';
import { Button } from 'react-native-paper';
import { apiUpload } from '../../api/client';
import { Choice, Empty, Field, Panel, QueryState, Screen, Txt } from '../../components/ui';
import { number, parseNumber } from '../../lib/format';
import { errorMessage, useAction } from '../../lib/hooks';
import type { RootStackParams } from '../../navigation/RootNavigator';
import { useAuth } from '../../store/auth';
import { useCatalogFilters } from '../../store/catalogPrefs';
import { colors, space } from '../../theme';
import { useCatalogImplements, useCatalogSeeds, useCatalogTractors } from './api';

type Tab = 'seeds' | 'tractors' | 'implements';

const TABS: { value: Tab; label: string }[] = [
  { value: 'seeds', label: 'Semințe' },
  { value: 'tractors', label: 'Tractoare' },
  { value: 'implements', label: 'Utilaje' },
];
const ALL = 'ALL';
const levels = (label: string) => [{ value: ALL, label }, ...(['LOW', 'MEDIUM', 'HIGH'] as CatalogLevel[]).map((value) => ({ value, label: LEVEL_LABELS[value] }))];
const levelValue = (value: string) => (value === ALL ? null : (value as CatalogLevel));

function SeedsTab() {
  const navigation = useNavigation<NavigationProp<RootStackParams>>();
  const seeds = useCatalogSeeds();
  const { seeds: filters, setSeeds, resetSeeds } = useCatalogFilters();
  const [showFilters, setShowFilters] = useState(false);
  const crops = useMemo(() => [...new Set((seeds.data ?? []).map((seed) => seed.crop))], [seeds.data]);
  const brands = useMemo(() => [...new Set((seeds.data ?? []).flatMap((seed) => (seed.brand ? [seed.brand] : [])))].sort(), [seeds.data]);
  const visible = useMemo(() => filterSeeds(seeds.data ?? [], filters), [seeds.data, filters]);
  const faoValue = (value: string) => (value.trim() ? parseNumber(value) : null);

  return (
    <>
      <Field label="Caută hibrid, soi sau producător" value={filters.search ?? ''} onChangeText={(search) => setSeeds({ search })} />
      <Choice
        options={[{ value: ALL, label: 'Toate culturile' }, ...crops.map((value) => ({ value, label: CROPS[value].name }))]}
        value={filters.crop ?? ALL}
        onChange={(value) => setSeeds({ crop: value === ALL ? null : (value as CropCode) })}
      />
      <View style={{ flexDirection: 'row', gap: space.sm }}>
        <Button style={{ flex: 1 }} mode="outlined" icon="filter-variant" onPress={() => setShowFilters((value) => !value)}>
          {showFilters ? 'Ascunde filtrele' : 'Filtre'}
        </Button>
        <Button style={{ flex: 1 }} onPress={resetSeeds}>Resetează</Button>
      </View>
      {showFilters ? (
        <Panel>
          <Choice label="Producător" options={[{ value: ALL, label: 'Toți' }, ...brands.map((value) => ({ value, label: value }))]} value={filters.brand ?? ALL} onChange={(value) => setSeeds({ brand: value === ALL ? null : value })} />
          <View style={{ flexDirection: 'row', gap: space.sm }}>
            <Field style={{ flex: 1 }} label="FAO minim" keyboardType="number-pad" value={filters.faoMin?.toString() ?? ''} onChangeText={(value) => setSeeds({ faoMin: faoValue(value) })} />
            <Field style={{ flex: 1 }} label="FAO maxim" keyboardType="number-pad" value={filters.faoMax?.toString() ?? ''} onChangeText={(value) => setSeeds({ faoMax: faoValue(value) })} />
          </View>
          <Choice label="Necesar de apă" options={levels('Oricare')} value={filters.waterRequirement ?? ALL} onChange={(value) => setSeeds({ waterRequirement: levelValue(value) })} />
          <Choice label="Toleranță la secetă" options={levels('Oricare')} value={filters.droughtTolerance ?? ALL} onChange={(value) => setSeeds({ droughtTolerance: levelValue(value) })} />
          <Choice label="Potențial de producție" options={levels('Oricare')} value={filters.yieldPotential ?? ALL} onChange={(value) => setSeeds({ yieldPotential: levelValue(value) })} />
          <Choice
            label="Sortează după"
            options={[{ value: 'name', label: 'Nume' }, { value: 'fao', label: 'FAO' }, { value: 'yield', label: 'Producție' }]}
            value={filters.sort ?? 'name'}
            onChange={(sort) => setSeeds({ sort })}
          />
        </Panel>
      ) : null}
      <QueryState query={seeds}>
        {() =>
          visible.length === 0 ? (
            <Empty icon="seed-off-outline" title="Niciun produs găsit" hint="Schimbă filtrele sau caută alt nume." />
          ) : (
            <>
              {visible.map((seed) => (
                <Pressable key={seed.id} accessibilityRole="button" onPress={() => navigation.navigate('CatalogDetail', { kind: 'seed', id: seed.id })}>
                  <Panel style={{ gap: 2 }}>
                    <Txt variant="subtitle">{seed.name}</Txt>
                    <Txt variant="small">
                      {[CROPS[seed.crop].name, SEED_TYPE_LABELS[seed.type], seed.brand, seed.fao ? `FAO ${seed.fao}` : null].filter(Boolean).join(', ')}
                    </Txt>
                  </Panel>
                </Pressable>
              ))}
            </>
          )
        }
      </QueryState>
    </>
  );
}

function TractorsTab() {
  const navigation = useNavigation<NavigationProp<RootStackParams>>();
  const tractors = useCatalogTractors();
  const [search, setSearch] = useState('');
  const [brand, setBrand] = useState(ALL);
  const [sort, setSort] = useState<'name' | 'power'>('name');
  const brands = useMemo(() => [...new Set((tractors.data ?? []).map((item) => item.brand))].sort(), [tractors.data]);
  const visible = useMemo(
    () => filterTractors(tractors.data ?? [], { search, brand: brand === ALL ? null : brand, sort }),
    [tractors.data, search, brand, sort],
  );
  return (
    <>
      <Field label="Caută marcă sau model" value={search} onChangeText={setSearch} />
      <Choice options={[{ value: ALL, label: 'Toate mărcile' }, ...brands.map((value) => ({ value, label: value }))]} value={brand} onChange={setBrand} />
      <Choice label="Sortează după" options={[{ value: 'name', label: 'Nume' }, { value: 'power', label: 'Putere' }]} value={sort} onChange={setSort} />
      <QueryState query={tractors}>
        {() => (
          <>
            {visible.map((tractor) => (
              <Pressable key={tractor.id} accessibilityRole="button" onPress={() => navigation.navigate('CatalogDetail', { kind: 'tractor', id: tractor.id })}>
                <Panel style={{ gap: 2 }}>
                  <Txt variant="subtitle">{`${tractor.brand} ${tractor.model}`}</Txt>
                  <Txt variant="small">{`${tractor.horsePower} CP, ${number(tractor.fuelConsumptionMin)}-${number(tractor.fuelConsumptionMax)} l/oră${tractor.fuelEstimated ? ' (estimat)' : ''}, ${tractor.category}`}</Txt>
                </Panel>
              </Pressable>
            ))}
          </>
        )}
      </QueryState>
    </>
  );
}

function ImplementsTab() {
  const implementsList = useCatalogImplements();
  return (
    <QueryState query={implementsList}>
      {(items) => (
        <>
          <Txt variant="small">Valori orientative, folosite pentru estimarea duratei și a consumului.</Txt>
          {items.map((item) => (
            <Panel key={item.id} style={{ gap: 2 }}>
              <Txt variant="subtitle">{item.name}</Txt>
              <Txt variant="small">{`Lățimi ${item.widthsM.join(', ')} m, ${item.speedKmh} km/h, ${item.hpPerMeter} CP/m`}</Txt>
            </Panel>
          ))}
        </>
      )}
    </QueryState>
  );
}

export function CatalogScreen() {
  const navigation = useNavigation<NavigationProp<RootStackParams>>();
  const role = useAuth((state) => state.role);
  const [tab, setTab] = useState<Tab>('seeds');
  const kind = tab === 'tractors' ? 'tractors' : 'seeds';

  const importFile = useAction<void>(
    async () => {
      const picked = await DocumentPicker.getDocumentAsync({ copyToCacheDirectory: true, multiple: false });
      const file = picked.assets?.[0];
      if (picked.canceled || !file) return;
      const result = await apiUpload<{ imported: number; problems: string[] }>(`/catalog/import?kind=${kind}`, { uri: file.uri, name: file.name, mimeType: file.mimeType });
      const skipped = result.problems.length ? `\n${result.problems.length} rânduri au fost ignorate. Primul motiv: ${result.problems[0]}` : '';
      Alert.alert('Import finalizat', `Au fost adăugate ${result.imported} produse.${skipped}`);
    },
    [['catalog']],
  );

  return (
    <Screen>
      <Choice options={TABS} value={tab} onChange={setTab} />
      {role === 'ADMIN' && tab !== 'implements' ? (
        <View style={{ flexDirection: 'row', gap: space.sm }}>
          <Button style={{ flex: 1 }} mode="contained" icon="plus" onPress={() => navigation.navigate('CatalogEditor', { kind: tab === 'seeds' ? 'seed' : 'tractor' })}>
            Adaugă
          </Button>
          <Button style={{ flex: 1 }} mode="outlined" icon="file-import-outline" loading={importFile.isPending} disabled={importFile.isPending} onPress={() => importFile.mutate()}>
            CSV sau Excel
          </Button>
        </View>
      ) : null}
      {importFile.error ? <Txt variant="small" color={colors.rust}>{errorMessage(importFile.error)}</Txt> : null}
      {tab === 'seeds' ? <SeedsTab /> : tab === 'tractors' ? <TractorsTab /> : <ImplementsTab />}
    </Screen>
  );
}
