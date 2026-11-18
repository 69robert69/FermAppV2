import { useMemo, useState } from 'react';
import { Choice, Panel, Row, Txt } from '../../components/ui';
import { number } from '../../lib/format';
import { useCatalogTractors, type CatalogTractor } from './api';

/** Alege marca și modelul, iar datele tehnice se completează automat. */
export function TractorWizard({ onSelect }: { onSelect: (tractor: CatalogTractor) => void }) {
  const tractors = useCatalogTractors();
  const [brand, setBrand] = useState<string | null>(null);
  const [selected, setSelected] = useState<CatalogTractor | null>(null);
  const brands = useMemo(() => [...new Set((tractors.data ?? []).map((item) => item.brand))].sort(), [tractors.data]);
  const models = (tractors.data ?? []).filter((item) => item.brand === brand).sort((a, b) => a.horsePower - b.horsePower);

  return (
    <Panel>
      <Txt variant="subtitle">Alege din catalog</Txt>
      <Choice
        label="Marcă"
        options={brands.map((value) => ({ value, label: value }))}
        value={brand}
        onChange={(value) => {
          setBrand(value);
          setSelected(null);
        }}
      />
      {brand ? (
        <Choice
          label="Model"
          options={models.map((item) => ({ value: item.id, label: item.model }))}
          value={selected?.id}
          onChange={(id) => {
            const tractor = models.find((item) => item.id === id);
            if (!tractor) return;
            setSelected(tractor);
            onSelect(tractor);
          }}
        />
      ) : null}
      {selected ? (
        <>
          <Row label="Putere" value={`${selected.horsePower} CP`} />
          <Row label="Consum" value={`${number(selected.fuelConsumptionMin)}-${number(selected.fuelConsumptionMax)} l/oră${selected.fuelEstimated ? ' (estimat)' : ''}`} />
          <Row label="Rezervor" value={selected.tankCapacity ? `${selected.tankCapacity} l` : 'Necompletat'} />
          <Row label="Greutate" value={selected.weight ? `${selected.weight} kg` : 'Necompletat'} />
          <Row label="Categorie" value={selected.category} />
        </>
      ) : null}
    </Panel>
  );
}
