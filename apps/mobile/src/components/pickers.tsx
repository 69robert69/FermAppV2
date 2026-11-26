import { useMemo } from 'react';
import { useParcels } from '../lib/hooks';
import { Choice, Txt } from './ui';

export function ParcelPicker({
  value,
  onChange,
  label = 'Parcel',
}: {
  value: string | null;
  onChange: (id: string) => void;
  label?: string;
}) {
  const parcels = useParcels();
  const options = useMemo(
    () => (parcels.data ?? []).map((parcel) => ({ value: parcel.id, label: `${parcel.name} (${parcel.areaHa} ha)` })),
    [parcels.data],
  );
  if (parcels.data && options.length === 0) {
    return <Txt variant="small">First add a parcel from the Map screen.</Txt>;
  }
  return <Choice label={label} options={options} value={value} onChange={onChange} />;
}
