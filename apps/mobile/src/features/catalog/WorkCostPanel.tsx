import { fuelLitersPerHour, IMPLEMENT_CATALOG, workCost, type MachineRecommendation, type WorkOperation, formatEuro } from '@fermapp/domain';
import { View } from 'react-native';
import { Button } from 'react-native-paper';
import type { Machine } from '../../api/types';
import { Field, Panel, Row, Txt } from '../../components/ui';
import { hoursMinutes, isPositive, number, parseNumber } from '../../lib/format';
import { useCatalogPrefs } from '../../store/catalogPrefs';
import { colors, space } from '../../theme';
import { useState } from 'react';
import { useMachineRecommendation } from './api';

/** Hourly consumption of the machine for the chosen operation. */
export function machineFuelLPerH(machine: Machine | undefined, operation: string | null) {
  if (!machine) return null;
  const load = IMPLEMENT_CATALOG.find((item) => item.operation === operation)?.fuelLoad;
  if (machine.fuelConsumptionMin != null && machine.fuelConsumptionMax != null) {
    return fuelLitersPerHour(machine.fuelConsumptionMin, machine.fuelConsumptionMax, load);
  }
  return machine.avgConsumptionLh;
}

/** Tractor recommendation and estimated cost of the operation. */
export function WorkCostPanel({
  parcelId,
  areaHa,
  operation,
  machine,
  durationHours,
  onApply,
}: {
  parcelId: string | null;
  areaHa: number | null;
  operation: string | null;
  machine: Machine | undefined;
  durationHours: number | null;
  onApply: (recommendation: MachineRecommendation) => void;
}) {
  const { fuelPricePerL, setFuelPrice } = useCatalogPrefs();
  const [price, setPrice] = useState(String(fuelPricePerL));
  const recommendation = useMachineRecommendation(parcelId, operation as WorkOperation | null);
  const best = recommendation.data;
  const fuelLPerH = machineFuelLPerH(machine, operation);
  const cost = fuelLPerH && durationHours && areaHa ? workCost({ fuelLPerH, durationHours, fuelPricePerL, areaHa }) : null;

  return (
    <View style={{ gap: space.md }}>
      {best ? (
        <Panel style={{ borderWidth: 1, borderColor: colors.wheat }}>
          <Txt variant="subtitle">{`Recommended: ${best.tractorName}`}</Txt>
          <Row label="Implement" value={`${best.implementName}, ${number(best.workingWidthM)} m`} />
          <Row label="Estimated duration" value={hoursMinutes(best.durationHours * 3600)} />
          <Row label="Estimated diesel" value={`${number(best.fuelLiters, 0)} l`} />
          <Row label="Estimated cost" value={formatEuro(best.fuelCost, 0)} />
          {!best.owned ? <Txt variant="small">Catalog suggestion. Add the farm's tractors for an exact recommendation.</Txt> : null}
          <Button mode="outlined" onPress={() => onApply(best)}>Use recommendation</Button>
        </Panel>
      ) : null}
      {operation === 'HARVESTING' ? <Txt variant="small">Harvesting is done with a combine, so it does not get a tractor recommendation.</Txt> : null}
      <Field
        label="Diesel price (€/l)"
        value={price}
        keyboardType="decimal-pad"
        onChangeText={(value) => {
          setPrice(value);
          if (isPositive(value)) setFuelPrice(parseNumber(value));
        }}
      />
      {cost ? (
        <Panel>
          <Row label="Consumption" value={`${number(cost.fuelLPerH)} l/hour`} />
          <Row label="Diesel" value={`${number(cost.fuelLiters, 0)} l`} />
          <Row label="Diesel cost" value={formatEuro(cost.fuelCost, 0)} />
          <Row label="Cost per hectare" value={`${formatEuro(cost.costPerHa, 2)}/ha`} />
        </Panel>
      ) : null}
    </View>
  );
}
