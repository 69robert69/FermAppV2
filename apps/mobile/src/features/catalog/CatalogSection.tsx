import { CROPS, LEVEL_LABELS, type CropCode } from '@fermapp/domain';
import { View } from 'react-native';
import { apiSend } from '../../api/client';
import { Panel, ProgressTrack, Row, Section, Txt } from '../../components/ui';
import { hoursMinutes, number, WORK_TYPES } from '../../lib/format';
import { useAction } from '../../lib/hooks';
import { colors, space } from '../../theme';
import { intelligenceKey, type ParcelIntelligence } from '../intelligence/api';
import { SeedPicker } from './SeedPicker';

const yieldRange = (min: number | null, max: number | null) => (min !== null && max !== null ? `${number(min)}-${number(max)} t/ha` : 'Necompletat');

/** Hibridul parcelei și recomandările din catalog, în panoul parcelei. */
export function CatalogSection({ data, canEdit }: { data: ParcelIntelligence; canEdit: boolean }) {
  const catalog = data.catalog;
  const save = useAction<{ id: string; name: string } | null>(
    async (seed) => {
      await apiSend('PATCH', `/seasons/${catalog?.seasonId}`, { seedId: seed?.id ?? null, seedName: seed?.name ?? null });
    },
    [intelligenceKey(data.header.id)],
  );
  if (!catalog?.cropName) return null;
  const { seed, machine, recommended } = catalog;
  const crop = (Object.keys(CROPS) as CropCode[]).find((code) => CROPS[code].name === catalog.cropName) ?? null;

  return (
    <Section title="Hibrid și utilaj recomandat">
      <Panel>
        <Row label="Cultură" value={catalog.cropName} />
        {seed ? (
          <>
            <Row label="Hibrid sau soi" value={seed.name} />
            <Row label="FAO" value={seed.fao ?? 'Necompletat'} />
            <Row label="Zile de vegetație" value={seed.vegetationDays ?? 'Necompletat'} />
            <Row label="Producție așteptată" value={yieldRange(seed.expectedYieldMin, seed.expectedYieldMax)} />
            <Row label="Necesar de apă" value={seed.waterRequirement ? LEVEL_LABELS[seed.waterRequirement] : 'Necompletat'} />
            <Row label="Compatibilitate" value={`${seed.compatibility}%`} />
            <ProgressTrack percent={seed.compatibility} color={colors.leaf} />
            <Txt variant="small">{seed.reason}</Txt>
          </>
        ) : (
          <Txt variant="small">Alege hibridul sau soiul folosit pe această parcelă.</Txt>
        )}
        {canEdit && catalog.seasonId ? (
          <SeedPicker crop={crop} parcelId={data.header.id} value={seed?.id ?? null} onChange={(item) => save.mutate(item && { id: item.id, name: item.name })} />
        ) : null}
      </Panel>

      {recommended.length > 0 ? (
        <Panel>
          <Txt variant="subtitle">Cele mai compatibile produse</Txt>
          {recommended.map((item, index) => (
            <View key={item.id} style={{ gap: 2 }}>
              <Row label={`${index + 1}. ${item.name}`} value={`${item.score}%`} />
              <Txt variant="small">{item.reason}</Txt>
            </View>
          ))}
        </Panel>
      ) : null}

      {machine && catalog.operation ? (
        <Panel>
          <Txt variant="subtitle">{`Tractor recomandat pentru ${WORK_TYPES[catalog.operation]?.toLowerCase()}`}</Txt>
          <Row label="Tractor" value={machine.tractorName} />
          <Row label="Durată estimată" value={hoursMinutes(machine.durationHours * 3600)} />
          <Row label="Motorină estimată" value={`${number(machine.fuelLiters, 0)} l`} />
          <Row label="Cost estimat" value={`${number(machine.fuelCost, 0)} RON`} />
          <Txt variant="small">{machine.owned ? machine.reason : `${machine.reason} Tractorul este o sugestie din catalog, deoarece ferma nu are încă tractoare adăugate.`}</Txt>
        </Panel>
      ) : null}
    </Section>
  );
}
