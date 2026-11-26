import { CROPS, LEVEL_LABELS, type CropCode, formatEuro } from '@fermapp/domain';
import { View } from 'react-native';
import { apiSend } from '../../api/client';
import { Panel, ProgressTrack, Row, Section, Txt } from '../../components/ui';
import { hoursMinutes, number, WORK_TYPES } from '../../lib/format';
import { useAction } from '../../lib/hooks';
import { colors, space } from '../../theme';
import { intelligenceKey, type ParcelIntelligence } from '../intelligence/api';
import { SeedPicker } from './SeedPicker';

const yieldRange = (min: number | null, max: number | null) => (min !== null && max !== null ? `${number(min)}-${number(max)} t/ha` : 'Not filled in');

/** The parcel's hybrid and the catalog recommendations, in the parcel panel. */
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
    <Section title="Recommended hybrid and machine">
      <Panel>
        <Row label="Crop" value={catalog.cropName} />
        {seed ? (
          <>
            <Row label="Hybrid or variety" value={seed.name} />
            <Row label="FAO" value={seed.fao ?? 'Not filled in'} />
            <Row label="Growing days" value={seed.vegetationDays ?? 'Not filled in'} />
            <Row label="Expected yield" value={yieldRange(seed.expectedYieldMin, seed.expectedYieldMax)} />
            <Row label="Water requirement" value={seed.waterRequirement ? LEVEL_LABELS[seed.waterRequirement] : 'Not filled in'} />
            <Row label="Compatibility" value={`${seed.compatibility}%`} />
            <ProgressTrack percent={seed.compatibility} color={colors.leaf} />
            <Txt variant="small">{seed.reason}</Txt>
          </>
        ) : (
          <Txt variant="small">Choose the hybrid or variety used on this parcel.</Txt>
        )}
        {canEdit && catalog.seasonId ? (
          <SeedPicker crop={crop} parcelId={data.header.id} value={seed?.id ?? null} onChange={(item) => save.mutate(item && { id: item.id, name: item.name })} />
        ) : null}
      </Panel>

      {recommended.length > 0 ? (
        <Panel>
          <Txt variant="subtitle">Most compatible products</Txt>
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
          <Txt variant="subtitle">{`Recommended tractor for ${WORK_TYPES[catalog.operation]?.toLowerCase()}`}</Txt>
          <Row label="Tractor" value={machine.tractorName} />
          <Row label="Estimated duration" value={hoursMinutes(machine.durationHours * 3600)} />
          <Row label="Estimated diesel" value={`${number(machine.fuelLiters, 0)} l`} />
          <Row label="Estimated cost" value={formatEuro(machine.fuelCost, 0)} />
          <Txt variant="small">{machine.owned ? machine.reason : `${machine.reason} The tractor is a catalog suggestion, because the farm has not added any tractors yet.`}</Txt>
        </Panel>
      ) : null}
    </Section>
  );
}
