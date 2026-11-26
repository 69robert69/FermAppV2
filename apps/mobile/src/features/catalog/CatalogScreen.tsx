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
  { value: 'seeds', label: 'Seeds' },
  { value: 'tractors', label: 'Tractors' },
  { value: 'implements', label: 'Implements' },
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
      <Field label="Search hybrid, variety or manufacturer" value={filters.search ?? ''} onChangeText={(search) => setSeeds({ search })} />
      <Choice
        options={[{ value: ALL, label: 'All crops' }, ...crops.map((value) => ({ value, label: CROPS[value].name }))]}
        value={filters.crop ?? ALL}
        onChange={(value) => setSeeds({ crop: value === ALL ? null : (value as CropCode) })}
      />
      <View style={{ flexDirection: 'row', gap: space.sm }}>
        <Button style={{ flex: 1 }} mode="outlined" icon="filter-variant" onPress={() => setShowFilters((value) => !value)}>
          {showFilters ? 'Hide filters' : 'Filters'}
        </Button>
        <Button style={{ flex: 1 }} onPress={resetSeeds}>Reset</Button>
      </View>
      {showFilters ? (
        <Panel>
          <Choice label="Manufacturer" options={[{ value: ALL, label: 'All' }, ...brands.map((value) => ({ value, label: value }))]} value={filters.brand ?? ALL} onChange={(value) => setSeeds({ brand: value === ALL ? null : value })} />
          <View style={{ flexDirection: 'row', gap: space.sm }}>
            <Field style={{ flex: 1 }} label="Min FAO" keyboardType="number-pad" value={filters.faoMin?.toString() ?? ''} onChangeText={(value) => setSeeds({ faoMin: faoValue(value) })} />
            <Field style={{ flex: 1 }} label="Max FAO" keyboardType="number-pad" value={filters.faoMax?.toString() ?? ''} onChangeText={(value) => setSeeds({ faoMax: faoValue(value) })} />
          </View>
          <Choice label="Water requirement" options={levels('Any')} value={filters.waterRequirement ?? ALL} onChange={(value) => setSeeds({ waterRequirement: levelValue(value) })} />
          <Choice label="Drought tolerance" options={levels('Any')} value={filters.droughtTolerance ?? ALL} onChange={(value) => setSeeds({ droughtTolerance: levelValue(value) })} />
          <Choice label="Yield potential" options={levels('Any')} value={filters.yieldPotential ?? ALL} onChange={(value) => setSeeds({ yieldPotential: levelValue(value) })} />
          <Choice
            label="Sort by"
            options={[{ value: 'name', label: 'Name' }, { value: 'fao', label: 'FAO' }, { value: 'yield', label: 'Yield' }]}
            value={filters.sort ?? 'name'}
            onChange={(sort) => setSeeds({ sort })}
          />
        </Panel>
      ) : null}
      <QueryState query={seeds}>
        {() =>
          visible.length === 0 ? (
            <Empty icon="seed-off-outline" title="No products found" hint="Change the filters or search for another name." />
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
      <Field label="Search brand or model" value={search} onChangeText={setSearch} />
      <Choice options={[{ value: ALL, label: 'All brands' }, ...brands.map((value) => ({ value, label: value }))]} value={brand} onChange={setBrand} />
      <Choice label="Sort by" options={[{ value: 'name', label: 'Name' }, { value: 'power', label: 'Power' }]} value={sort} onChange={setSort} />
      <QueryState query={tractors}>
        {() => (
          <>
            {visible.map((tractor) => (
              <Pressable key={tractor.id} accessibilityRole="button" onPress={() => navigation.navigate('CatalogDetail', { kind: 'tractor', id: tractor.id })}>
                <Panel style={{ gap: 2 }}>
                  <Txt variant="subtitle">{`${tractor.brand} ${tractor.model}`}</Txt>
                  <Txt variant="small">{`${tractor.horsePower} HP, ${number(tractor.fuelConsumptionMin)}-${number(tractor.fuelConsumptionMax)} l/hour${tractor.fuelEstimated ? ' (estimated)' : ''}, ${tractor.category}`}</Txt>
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
          <Txt variant="small">Indicative values, used to estimate duration and consumption.</Txt>
          {items.map((item) => (
            <Panel key={item.id} style={{ gap: 2 }}>
              <Txt variant="subtitle">{item.name}</Txt>
              <Txt variant="small">{`Widths ${item.widthsM.join(', ')} m, ${item.speedKmh} km/h, ${item.hpPerMeter} HP/m`}</Txt>
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
      const skipped = result.problems.length ? `\n${result.problems.length} rows were skipped. First reason: ${result.problems[0]}` : '';
      Alert.alert('Import complete', `${result.imported} products were added.${skipped}`);
    },
    [['catalog']],
  );

  return (
    <Screen>
      <Choice options={TABS} value={tab} onChange={setTab} />
      {role === 'ADMIN' && tab !== 'implements' ? (
        <View style={{ flexDirection: 'row', gap: space.sm }}>
          <Button style={{ flex: 1 }} mode="contained" icon="plus" onPress={() => navigation.navigate('CatalogEditor', { kind: tab === 'seeds' ? 'seed' : 'tractor' })}>
            Add
          </Button>
          <Button style={{ flex: 1 }} mode="outlined" icon="file-import-outline" loading={importFile.isPending} disabled={importFile.isPending} onPress={() => importFile.mutate()}>
            CSV or Excel
          </Button>
        </View>
      ) : null}
      {importFile.error ? <Txt variant="small" color={colors.rust}>{errorMessage(importFile.error)}</Txt> : null}
      {tab === 'seeds' ? <SeedsTab /> : tab === 'tractors' ? <TractorsTab /> : <ImplementsTab />}
    </Screen>
  );
}
