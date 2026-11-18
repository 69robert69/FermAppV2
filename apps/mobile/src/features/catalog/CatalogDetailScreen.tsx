import { CROPS, LEVEL_LABELS, SEED_TYPE_LABELS, type CatalogLevel } from '@fermapp/domain';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import { useState } from 'react';
import { Alert, View } from 'react-native';
import { Button } from 'react-native-paper';
import { apiSend } from '../../api/client';
import { ParcelPicker } from '../../components/pickers';
import { Empty, Panel, ProgressTrack, Row, Screen, Section, Txt } from '../../components/ui';
import { number } from '../../lib/format';
import { useAction } from '../../lib/hooks';
import type { RootStackParams } from '../../navigation/RootNavigator';
import { useAuth } from '../../store/auth';
import { colors, space } from '../../theme';
import { useCatalogSeeds, useCatalogTractors, useSeedRanking, type CatalogSeed, type CatalogTractor } from './api';

type Props = NativeStackScreenProps<RootStackParams, 'CatalogDetail'>;

const MISSING = 'Necompletat';
const level = (value: CatalogLevel | null) => (value ? LEVEL_LABELS[value] : MISSING);
const SOIL_LABELS = { SANDY: 'Nisipos', LOAMY: 'Lutos', CLAY_LOAM: 'Lutos-argilos', CLAY: 'Argilos' } as const;

function SeedDetails({ seed }: { seed: CatalogSeed }) {
  const [parcelId, setParcelId] = useState<string | null>(null);
  const ranking = useSeedRanking(parcelId, seed.crop);
  const match = ranking.data?.find((item) => item.seed.id === seed.id);
  const position = ranking.data ? ranking.data.findIndex((item) => item.seed.id === seed.id) + 1 : 0;
  return (
    <>
      <Panel>
        <Txt variant="title">{seed.name}</Txt>
        <Row label="Tip" value={SEED_TYPE_LABELS[seed.type]} />
        <Row label="Cultură" value={CROPS[seed.crop].name} />
        <Row label="Producător" value={seed.brand ?? MISSING} />
        <Row label="Grupa FAO" value={seed.fao ? `${seed.fao}${seed.maturityGroup ? `, ${seed.maturityGroup.toLowerCase()}` : ''}` : MISSING} />
        <Row label="Zile de vegetație" value={seed.vegetationDays ?? MISSING} />
        <Row label="Rezistență la secetă" value={level(seed.droughtTolerance)} />
        <Row label="Producție așteptată" value={seed.expectedYieldMin !== null && seed.expectedYieldMax !== null ? `${number(seed.expectedYieldMin)}-${number(seed.expectedYieldMax)} t/ha` : MISSING} />
        <Row label="Regiuni recomandate" value={seed.recommendedRegions.join(', ') || MISSING} />
        <Row label="Necesar de apă" value={level(seed.waterRequirement)} />
        <Row label="Compatibilitate sol" value={seed.soilPreference.map((item) => SOIL_LABELS[item]).join(', ') || MISSING} />
        {seed.notes ? <Txt variant="small">{seed.notes}</Txt> : null}
      </Panel>
      <Section title="Compatibilitate cu parcela">
        <Panel>
          <ParcelPicker value={parcelId} onChange={setParcelId} />
          {match ? (
            <View style={{ gap: space.sm }}>
              <Row label={`Locul ${position} din ${ranking.data?.length}`} value={`${match.score}%`} />
              <ProgressTrack percent={match.score} color={colors.leaf} />
              <Txt variant="small">{match.reason}</Txt>
            </View>
          ) : null}
        </Panel>
      </Section>
    </>
  );
}

function TractorDetails({ tractor }: { tractor: CatalogTractor }) {
  return (
    <Panel>
      <Txt variant="title">{`${tractor.brand} ${tractor.model}`}</Txt>
      <Row label="Serie" value={tractor.series ?? MISSING} />
      <Row label="Putere" value={`${tractor.horsePower} CP`} />
      <Row label="Consum" value={`${number(tractor.fuelConsumptionMin)}-${number(tractor.fuelConsumptionMax)} l/oră`} />
      {tractor.fuelEstimated ? <Txt variant="small">Consumul este estimat din putere. Corectează-l după fișa tehnică.</Txt> : null}
      <Row label="Rezervor" value={tractor.tankCapacity ? `${tractor.tankCapacity} l` : MISSING} />
      <Row label="Greutate" value={tractor.weight ? `${tractor.weight} kg` : MISSING} />
      <Row label="Tracțiune" value={tractor.driveSystem ?? MISSING} />
      <Row label="Categorie" value={tractor.category} />
      <Txt variant="small">{tractor.recommendedUsage}</Txt>
    </Panel>
  );
}

export function CatalogDetailScreen({ route, navigation }: Props) {
  const { kind, id } = route.params;
  const role = useAuth((state) => state.role);
  const seeds = useCatalogSeeds();
  const tractors = useCatalogTractors();
  const seed = kind === 'seed' ? seeds.data?.find((item) => item.id === id) : undefined;
  const tractor = kind === 'tractor' ? tractors.data?.find((item) => item.id === id) : undefined;

  const remove = useAction<void>(
    async () => {
      await apiSend('DELETE', `/catalog/${kind === 'seed' ? 'seeds' : 'tractors'}/${id}`);
    },
    [['catalog']],
    () => navigation.goBack(),
  );

  const confirmDelete = () =>
    Alert.alert('Șterge din catalog', 'Produsul dispare din catalogul fermei.', [
      { text: 'Renunță', style: 'cancel' },
      { text: 'Șterge', style: 'destructive', onPress: () => remove.mutate() },
    ]);

  if (!seed && !tractor) return <Screen><Empty icon="book-search-outline" title="Produsul nu a fost găsit" /></Screen>;

  return (
    <Screen>
      {seed ? <SeedDetails seed={seed} /> : null}
      {tractor ? <TractorDetails tractor={tractor} /> : null}
      {role === 'ADMIN' ? (
        <View style={{ flexDirection: 'row', gap: space.sm }}>
          <Button style={{ flex: 1 }} mode="contained" onPress={() => navigation.navigate('CatalogEditor', { kind, id })}>Editează</Button>
          <Button style={{ flex: 1 }} textColor={colors.rust} loading={remove.isPending} disabled={remove.isPending} onPress={confirmDelete}>Șterge</Button>
        </View>
      ) : null}
    </Screen>
  );
}
