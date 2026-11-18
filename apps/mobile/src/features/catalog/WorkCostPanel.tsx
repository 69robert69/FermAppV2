import { fuelLitersPerHour, IMPLEMENT_CATALOG, workCost, type MachineRecommendation, type WorkOperation } from '@fermapp/domain';
import { View } from 'react-native';
import { Button } from 'react-native-paper';
import type { Machine } from '../../api/types';
import { Field, Panel, Row, Txt } from '../../components/ui';
import { hoursMinutes, isPositive, number, parseNumber } from '../../lib/format';
import { useCatalogPrefs } from '../../store/catalogPrefs';
import { colors, space } from '../../theme';
import { useState } from 'react';
import { useMachineRecommendation } from './api';

/** Consumul orar al utilajului pentru lucrarea aleasă. */
export function machineFuelLPerH(machine: Machine | undefined, operation: string | null) {
  if (!machine) return null;
  const load = IMPLEMENT_CATALOG.find((item) => item.operation === operation)?.fuelLoad;
  if (machine.fuelConsumptionMin != null && machine.fuelConsumptionMax != null) {
    return fuelLitersPerHour(machine.fuelConsumptionMin, machine.fuelConsumptionMax, load);
  }
  return machine.avgConsumptionLh;
}

/** Recomandarea de tractor și costul estimat al lucrării. */
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
          <Txt variant="subtitle">{`Recomandat: ${best.tractorName}`}</Txt>
          <Row label="Utilaj" value={`${best.implementName}, ${number(best.workingWidthM)} m`} />
          <Row label="Durată estimată" value={hoursMinutes(best.durationHours * 3600)} />
          <Row label="Motorină estimată" value={`${number(best.fuelLiters, 0)} l`} />
          <Row label="Cost estimat" value={`${number(best.fuelCost, 0)} RON`} />
          {!best.owned ? <Txt variant="small">Sugestie din catalog. Adaugă tractoarele fermei pentru o recomandare exactă.</Txt> : null}
          <Button mode="outlined" onPress={() => onApply(best)}>Folosește recomandarea</Button>
        </Panel>
      ) : null}
      {operation === 'HARVESTING' ? <Txt variant="small">Recoltarea se face cu combina, deci nu primește o recomandare de tractor.</Txt> : null}
      <Field
        label="Preț motorină (RON/l)"
        value={price}
        keyboardType="decimal-pad"
        onChangeText={(value) => {
          setPrice(value);
          if (isPositive(value)) setFuelPrice(parseNumber(value));
        }}
      />
      {cost ? (
        <Panel>
          <Row label="Consum" value={`${number(cost.fuelLPerH)} l/oră`} />
          <Row label="Motorină" value={`${number(cost.fuelLiters, 0)} l`} />
          <Row label="Cost motorină" value={`${number(cost.fuelCost, 0)} RON`} />
          <Row label="Cost pe hectar" value={`${number(cost.costPerHa, 0)} RON/ha`} />
        </Panel>
      ) : null}
    </View>
  );
}
