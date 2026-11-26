import { useMemo, useState } from 'react';
import { Choice, Panel, Row, Txt } from '../../components/ui';
import { number } from '../../lib/format';
import { useCatalogTractors, type CatalogTractor } from './api';

/** Choose the brand and model, and the technical data is filled in automatically. */
export function TractorWizard({ onSelect }: { onSelect: (tractor: CatalogTractor) => void }) {
  const tractors = useCatalogTractors();
  const [brand, setBrand] = useState<string | null>(null);
  const [selected, setSelected] = useState<CatalogTractor | null>(null);
  const brands = useMemo(() => [...new Set((tractors.data ?? []).map((item) => item.brand))].sort(), [tractors.data]);
  const models = (tractors.data ?? []).filter((item) => item.brand === brand).sort((a, b) => a.horsePower - b.horsePower);

  return (
    <Panel>
      <Txt variant="subtitle">Choose from catalog</Txt>
      <Choice
        label="Brand"
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
          <Row label="Power" value={`${selected.horsePower} HP`} />
          <Row label="Consumption" value={`${number(selected.fuelConsumptionMin)}-${number(selected.fuelConsumptionMax)} l/hour${selected.fuelEstimated ? ' (estimated)' : ''}`} />
          <Row label="Tank" value={selected.tankCapacity ? `${selected.tankCapacity} l` : 'Not filled in'} />
          <Row label="Weight" value={selected.weight ? `${selected.weight} kg` : 'Not filled in'} />
          <Row label="Category" value={selected.category} />
        </>
      ) : null}
    </Panel>
  );
}
