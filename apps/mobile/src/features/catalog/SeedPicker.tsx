import type { CropCode } from '@fermapp/domain';
import { useMemo } from 'react';
import { Choice, Txt } from '../../components/ui';
import { usesFirebase } from '../../firebase/config';
import { useCatalogSeeds, useSeedRanking, type CatalogSeed } from './api';

const NONE = 'none';

/** Shows only the hybrids and varieties compatible with the chosen crop. */
export function SeedPicker({
  crop,
  value,
  onChange,
  parcelId = null,
}: {
  crop: CropCode | null;
  value: string | null;
  onChange: (seed: CatalogSeed | null) => void;
  parcelId?: string | null;
}) {
  const seeds = useCatalogSeeds();
  const ranking = useSeedRanking(parcelId, crop);
  const compatible = useMemo(() => (seeds.data ?? []).filter((seed) => seed.crop === crop), [seeds.data, crop]);
  if (!usesFirebase || !crop) return null;
  if (seeds.data && compatible.length === 0) return <Txt variant="small">The catalog has no products for this crop yet.</Txt>;

  const scoreOf = (seed: CatalogSeed) => ranking.data?.find((item) => item.seed.id === seed.id)?.score;
  const options = [
    { value: NONE, label: 'Not chosen yet' },
    ...compatible.map((seed) => {
      const score = scoreOf(seed);
      return { value: seed.id, label: score === undefined ? seed.name : `${seed.name}, ${score}%` };
    }),
  ];
  return (
    <Choice
      label="Which hybrid or variety are you using?"
      options={options}
      value={value ?? NONE}
      onChange={(id) => onChange(compatible.find((seed) => seed.id === id) ?? null)}
    />
  );
}
