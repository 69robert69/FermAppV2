import { MaterialCommunityIcons } from '@expo/vector-icons';
import {
  CHEMICAL_KINDS,
  CHEMICAL_LABELS,
  CHEMICAL_PRESETS,
  chemicalCost,
  economicInsights,
  emptySheet,
  FERTILIZER_PRESETS,
  fertilizerCost,
  formatEuro,
  miscCost,
  SEED_CATEGORIES,
  SEED_DEFAULTS,
  SEED_SUPPLIERS,
  seedCost,
  type ChemicalLine,
  type EconomicSheet,
  type FertilizerLine,
  type MiscLine,
  type OperationCost,
  type ProfitResult,
  type SeedLine,
} from '@fermapp/domain';
import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';
import { Button } from 'react-native-paper';
import { Choice, Empty, Field, Panel, QueryState, Row, Section, Txt } from '../../components/ui';
import { number, parseNumber } from '../../lib/format';
import { canPlan, useAuth } from '../../store/auth';
import { colors, fonts, space } from '../../theme';
import { useTillageSettings } from '../tillage/prefs';
import { newLineId, useFarmEconomics, useSaveSheet, useSheet } from './api';
import { CostBreakdown, InsightList, Kpi } from './components';

// ------------------------------------------------------------------ small inputs

/** A number field that keeps what the user types and reports the parsed value. */
function NumField({ label, value, onChange, flex = 1, disabled }: { label: string; value: number | null | undefined; onChange: (value: number | null) => void; flex?: number; disabled?: boolean }) {
  const [text, setText] = useState(value == null ? '' : String(value).replace('.', ','));
  return (
    <Field
      style={{ flex }}
      label={label}
      value={text}
      editable={!disabled}
      keyboardType="decimal-pad"
      onChangeText={(next) => {
        setText(next);
        const parsed = parseNumber(next);
        onChange(next.trim() === '' || !Number.isFinite(parsed) ? null : parsed);
      }}
    />
  );
}

function LineCard({ title, subtitle, figures, onRemove, editable, children }: { title: string; subtitle: string; figures: ReactNode; onRemove: () => void; editable: boolean; children: ReactNode }) {
  const [open, setOpen] = useState(!title);
  return (
    <View style={styles.line}>
      <Pressable accessibilityRole="button" accessibilityState={{ expanded: open }} onPress={() => setOpen((value) => !value)} style={styles.lineHead}>
        <View style={{ flex: 1 }}>
          <Txt color={colors.chaff} numberOfLines={1}>{title || 'New line'}</Txt>
          <Txt variant="small" numberOfLines={1}>{subtitle}</Txt>
        </View>
        <View style={styles.lineFigures}>{figures}</View>
        <MaterialCommunityIcons name={open ? 'chevron-up' : 'chevron-down'} size={20} color={colors.straw} />
      </Pressable>
      {open ? (
        <View style={styles.lineBody}>
          {children}
          {editable ? (
            <Button compact mode="text" textColor={colors.rust} icon="delete-outline" onPress={onRemove}>
              Remove
            </Button>
          ) : null}
        </View>
      ) : null}
    </View>
  );
}

const Figure = ({ main, detail }: { main: string; detail: string }) => (
  <>
    <Txt color={colors.chaff} style={styles.figure}>{main}</Txt>
    <Txt variant="small">{detail}</Txt>
  </>
);

function PresetChips<T extends { product: string }>({ presets, onPick }: { presets: T[]; onPick: (preset: T) => void }) {
  return (
    <View style={styles.chips}>
      {presets.map((preset) => (
        <Pressable key={preset.product} accessibilityRole="button" onPress={() => onPick(preset)} style={styles.chip}>
          <MaterialCommunityIcons name="plus" size={14} color={colors.wheat} />
          <Txt variant="small" color={colors.chaff}>{preset.product}</Txt>
        </Pressable>
      ))}
    </View>
  );
}

// ------------------------------------------------------------------ sections

function ProfitPanel({ title, result }: { title: string; result: ProfitResult | null }) {
  if (!result) return null;
  const positive = result.profit >= 0;
  return (
    <View style={styles.profit}>
      <Txt variant="subtitle">{title}</Txt>
      <Row label="Yield" value={`${number(result.yieldTHa)} t/ha at ${formatEuro(result.pricePerT)}/t`} />
      <Row label="Gross revenue" value={`${formatEuro(result.revenue, 0)} (${formatEuro(result.revenuePerHa, 0)}/ha)`} />
      <Row label="Gross profit" value={<Txt color={positive ? colors.leaf : colors.rust}>{formatEuro(result.profit, 0)}</Txt>} />
      <Row label="Profit per hectare" value={<Txt color={positive ? colors.leaf : colors.rust}>{`${formatEuro(result.profitPerHa, 2)}/ha`}</Txt>} />
      <Row label="Profit margin" value={result.marginPct == null ? '-' : `${number(result.marginPct)}%`} />
    </View>
  );
}

function OperationRow({ op }: { op: OperationCost }) {
  return (
    <View style={styles.opRow}>
      <View style={{ flex: 1, gap: 2 }}>
        <View style={styles.inline}>
          <Txt color={colors.chaff}>{op.label}</Txt>
          {op.projected ? (
            <View style={styles.tag}>
              <Txt variant="small" color={colors.water} style={styles.tagText}>Estimated</Txt>
            </View>
          ) : null}
        </View>
        <Txt variant="small">
          {[op.machineName ?? (op.projected ? 'Typical machine' : 'No machine'), `${number(op.areaHa)} ha`, `${number(op.hours)} h`, `${number(op.fuelL, 0)} l diesel`].join(', ')}
        </Txt>
        <Txt variant="small">{`Diesel ${formatEuro(op.fuelCost, 0)}, machine ${formatEuro(op.machineCost, 0)}, labour ${formatEuro(op.laborCost, 0)}`}</Txt>
      </View>
      <View style={styles.lineFigures}>
        <Figure main={formatEuro(op.total, 0)} detail={`${formatEuro(op.costPerHa, 2)}/ha`} />
      </View>
    </View>
  );
}

// ------------------------------------------------------------------ tab

/** "Economic analysis" of a parcel: real cost per hectare, its structure, profit and advice. */
export function ParcelEconomicsTab({ parcelId }: { parcelId: string }) {
  const role = useAuth((state) => state.role);
  const editable = canPlan(role);
  const thisYear = new Date().getFullYear();
  const [year, setYear] = useState(thisYear);
  const [view, setView] = useState<'season' | 'toDate'>('season');
  const farm = useFarmEconomics(year);
  const stored = useSheet(parcelId, year);
  const [draft, setDraft] = useState<EconomicSheet>(emptySheet());
  const [dirty, setDirty] = useState(false);
  const save = useSaveSheet(parcelId, year, () => setDirty(false));
  const tillageSettings = useTillageSettings();

  useEffect(() => {
    if (stored.data) {
      setDraft(stored.data.sheet);
      setDirty(false);
    }
  }, [stored.data]);

  const item = farm.data?.parcels.find((entry) => entry.parcelId === parcelId);
  const areaHa = item?.result.areaHa ?? 0;
  const update = (change: (sheet: EconomicSheet) => EconomicSheet) => {
    setDraft((current) => change(current));
    setDirty(true);
  };
  const insights = useMemo(() => (item ? economicInsights(item.result, { tillage: item.tillage, tillageSettings }) : []), [item, tillageSettings]);

  const setLine = <K extends 'seeds' | 'fertilizers' | 'chemicals' | 'misc'>(key: K, id: string, patch: Partial<EconomicSheet[K][number]>) =>
    update((sheet) => ({ ...sheet, [key]: (sheet[key] as { id: string }[]).map((line) => (line.id === id ? { ...line, ...patch } : line)) }));
  const removeLine = (key: 'seeds' | 'fertilizers' | 'chemicals' | 'misc', id: string) =>
    update((sheet) => ({ ...sheet, [key]: (sheet[key] as { id: string }[]).filter((line) => line.id !== id) }));

  const crop = item?.crop ?? null;
  const addSeed = (supplier = '') => {
    const defaults = (crop && SEED_DEFAULTS[crop]) || { unit: 'SEEDS' as const, bagSize: 80_000, ratePerHa: 70_000, pricePerBag: 180 };
    update((sheet) => ({ ...sheet, seeds: [...sheet.seeds, { id: newLineId(), supplier, hybrid: '', category: null, bagsUsed: null, ...defaults }] }));
  };
  const totals = item ? (view === 'season' ? item.result.season : item.result.toDate) : null;
  const outlook = item ? (item.result.actual ?? item.result.expected) : null;

  return (
    <QueryState query={farm}>
      {() =>
        !item ? (
          <Empty icon="calculator-variant-outline" title="This parcel is archived or not found" />
        ) : (
          <>
            <Choice options={[thisYear - 1, thisYear, thisYear + 1].map((value) => ({ value: String(value), label: String(value) }))} value={String(year)} onChange={(value) => setYear(Number(value))} />

            <Section title="Real cost per hectare">
              <View style={styles.kpis}>
                <Kpi icon="vector-square" label="Area" value={`${number(areaHa)} ha`} detail={item.cropName ?? 'No crop'} />
                <Kpi icon="cash-multiple" label={view === 'season' ? 'Season total' : 'Spent so far'} value={formatEuro(totals!.total, 0)} />
                <Kpi icon="calculator-variant-outline" label="Cost per hectare" value={formatEuro(totals!.perHa, 2)} tone={colors.wheat} />
                <Kpi
                  icon={(outlook?.profit ?? 0) >= 0 ? 'trending-up' : 'trending-down'}
                  label={item.result.actual ? 'Actual profit/ha' : 'Expected profit/ha'}
                  value={outlook ? formatEuro(outlook.profitPerHa, 0) : '-'}
                  tone={(outlook?.profit ?? 0) >= 0 ? colors.leaf : colors.rust}
                />
              </View>
              <Panel>
                <Choice
                  options={[
                    { value: 'season', label: 'Full season' },
                    { value: 'toDate', label: 'Spent so far' },
                  ]}
                  value={view}
                  onChange={setView}
                />
                <Txt variant="small">
                  {view === 'season'
                    ? 'Inputs, finished work, recorded expenses and the operations still to come.'
                    : 'Inputs, finished work and recorded expenses only.'}
                </Txt>
                <CostBreakdown byCategory={totals!.byCategory} total={totals!.total} areaHa={areaHa} />
                {item.result.recordedTotal > 0 ? <Txt variant="small">{`Includes ${formatEuro(item.result.recordedTotal, 0)} of expenses recorded in the Expenses tab.`}</Txt> : null}
              </Panel>
            </Section>

            <Section title="Recommendations">
              <InsightList insights={insights} />
            </Section>

            <Section title="Profitability">
              <Panel key={`revenue-${year}-${stored.dataUpdatedAt}`}>
                <View style={styles.inputs}>
                  <NumField label="Expected yield (t/ha)" value={draft.revenue.expectedYieldTHa} disabled={!editable} onChange={(value) => update((sheet) => ({ ...sheet, revenue: { ...sheet.revenue, expectedYieldTHa: value } }))} />
                  <NumField label="Actual yield (t/ha)" value={draft.revenue.actualYieldTHa} disabled={!editable} onChange={(value) => update((sheet) => ({ ...sheet, revenue: { ...sheet.revenue, actualYieldTHa: value } }))} />
                </View>
                <View style={styles.inputs}>
                  <NumField label="Sale price (€/t)" value={draft.revenue.pricePerT} disabled={!editable} onChange={(value) => update((sheet) => ({ ...sheet, revenue: { ...sheet.revenue, pricePerT: value } }))} />
                  <NumField label="Price obtained (€/t)" value={draft.revenue.actualPricePerT} disabled={!editable} onChange={(value) => update((sheet) => ({ ...sheet, revenue: { ...sheet.revenue, actualPricePerT: value } }))} />
                </View>
                <ProfitPanel title="Expected" result={item.result.expected} />
                <ProfitPanel title="Actual" result={item.result.actual} />
              </Panel>
            </Section>

            <Section title="Seeds">
              <Panel>
                {draft.seeds.map((line: SeedLine) => {
                  const cost = seedCost(line, areaHa);
                  return (
                    <LineCard
                      key={line.id}
                      title={[line.supplier, line.hybrid].filter(Boolean).join(' ')}
                      subtitle={`${number(line.ratePerHa, 0)} ${line.unit === 'SEEDS' ? 'seeds' : 'kg'}/ha, ${number(cost.bagsNeeded)} bags needed`}
                      figures={<Figure main={`${formatEuro(cost.costPerHa, 2)}/ha`} detail={formatEuro(cost.total, 0)} />}
                      editable={editable}
                      onRemove={() => removeLine('seeds', line.id)}
                    >
                      <Choice options={(crop && SEED_SUPPLIERS[crop] ? SEED_SUPPLIERS[crop]! : ['Pioneer', 'Dekalb', 'KWS', 'Syngenta', 'Corteva', 'LG']).map((value) => ({ value, label: value }))} value={line.supplier} onChange={(supplier) => setLine('seeds', line.id, { supplier })} />
                      <View style={styles.inputs}>
                        <Field style={styles.flex} label="Supplier" value={line.supplier} editable={editable} onChangeText={(supplier) => setLine('seeds', line.id, { supplier })} />
                        <Field style={styles.flex} label="Hybrid / variety" value={line.hybrid} editable={editable} onChangeText={(hybrid) => setLine('seeds', line.id, { hybrid })} />
                      </View>
                      <Choice label="Sold by" options={[{ value: 'SEEDS', label: 'Seeds per bag' }, { value: 'KG', label: 'Kilograms' }]} value={line.unit} onChange={(unit) => setLine('seeds', line.id, { unit })} />
                      {line.unit === 'KG' ? <Choice label="Seed category" options={SEED_CATEGORIES.map((value) => ({ value, label: value }))} value={line.category ?? null} onChange={(category) => setLine('seeds', line.id, { category })} /> : null}
                      <View style={styles.inputs}>
                        <NumField label={line.unit === 'SEEDS' ? 'Seeds per bag' : 'kg per bag'} value={line.bagSize} disabled={!editable} onChange={(value) => setLine('seeds', line.id, { bagSize: value ?? 1 })} />
                        <NumField label={line.unit === 'SEEDS' ? 'Seeding rate (seeds/ha)' : 'Seeding rate (kg/ha)'} value={line.ratePerHa} disabled={!editable} onChange={(value) => setLine('seeds', line.id, { ratePerHa: value ?? 0 })} />
                      </View>
                      <View style={styles.inputs}>
                        <NumField label="Cost per bag (€)" value={line.pricePerBag} disabled={!editable} onChange={(value) => setLine('seeds', line.id, { pricePerBag: value ?? 0 })} />
                        <NumField label="Bags used" value={line.bagsUsed} disabled={!editable} onChange={(value) => setLine('seeds', line.id, { bagsUsed: value })} />
                      </View>
                      <Txt variant="small">
                        {line.unit === 'KG' ? `${formatEuro(cost.costPerKg, 2)}/kg` : `${formatEuro(cost.costPer1000Seeds, 2)} per 1,000 seeds`}
                        {`, ${formatEuro(cost.costPerHa, 2)}/ha`}
                      </Txt>
                    </LineCard>
                  );
                })}
                {editable ? <Button mode="outlined" icon="plus" onPress={() => addSeed()}>Add seed</Button> : null}
              </Panel>
            </Section>

            <Section title="Fertilisers">
              <Panel>
                {draft.fertilizers.map((line: FertilizerLine) => {
                  const cost = fertilizerCost(line, areaHa);
                  return (
                    <LineCard
                      key={line.id}
                      title={line.product}
                      subtitle={`${number(line.ratePerHaKg, 0)} kg/ha${(line.applications ?? 1) > 1 ? ` × ${line.applications}` : ''}, N ${cost.nutrients.n} P ${cost.nutrients.p} K ${cost.nutrients.k} kg/ha`}
                      figures={<Figure main={`${formatEuro(cost.costPerHa, 2)}/ha`} detail={formatEuro(cost.total, 0)} />}
                      editable={editable}
                      onRemove={() => removeLine('fertilizers', line.id)}
                    >
                      <Field label="Product" value={line.product} editable={editable} onChangeText={(product) => setLine('fertilizers', line.id, { product })} />
                      <View style={styles.inputs}>
                        <NumField label="Dose (kg/ha)" value={line.ratePerHaKg} disabled={!editable} onChange={(value) => setLine('fertilizers', line.id, { ratePerHaKg: value ?? 0 })} />
                        <NumField label="Price (€/t)" value={line.pricePerT} disabled={!editable} onChange={(value) => setLine('fertilizers', line.id, { pricePerT: value ?? 0 })} />
                        <NumField label="Passes" value={line.applications ?? 1} disabled={!editable} onChange={(value) => setLine('fertilizers', line.id, { applications: Math.max(1, Math.round(value ?? 1)) })} />
                      </View>
                      <Txt variant="small">{`${formatEuro(cost.pricePerKg, 3)}/kg, ${formatEuro(cost.costPerHa, 2)}/ha, ${formatEuro(cost.total, 0)} for the parcel`}</Txt>
                    </LineCard>
                  );
                })}
                {editable ? (
                  <>
                    <Txt variant="small">Add a product</Txt>
                    <PresetChips presets={FERTILIZER_PRESETS} onPick={(preset) => update((sheet) => ({ ...sheet, fertilizers: [...sheet.fertilizers, { ...preset, id: newLineId(), applications: 1 }] }))} />
                  </>
                ) : null}
              </Panel>
            </Section>

            <Section title="Crop protection">
              <Panel>
                {draft.chemicals.map((line: ChemicalLine) => {
                  const cost = chemicalCost(line, areaHa);
                  return (
                    <LineCard
                      key={line.id}
                      title={line.product}
                      subtitle={`${CHEMICAL_LABELS[line.kind]}, ${number(line.dosePerHa, 2)} ${line.unit === 'L' ? 'l' : 'kg'}/ha${(line.applications ?? 1) > 1 ? ` × ${line.applications}` : ''}`}
                      figures={<Figure main={`${formatEuro(cost.costPerHa, 2)}/ha`} detail={formatEuro(cost.total, 0)} />}
                      editable={editable}
                      onRemove={() => removeLine('chemicals', line.id)}
                    >
                      <Field label="Product" value={line.product} editable={editable} onChangeText={(product) => setLine('chemicals', line.id, { product })} />
                      <Choice options={CHEMICAL_KINDS.map((kind) => ({ value: kind, label: CHEMICAL_LABELS[kind] }))} value={line.kind} onChange={(kind) => setLine('chemicals', line.id, { kind })} />
                      <Choice options={[{ value: 'L', label: 'Litres' }, { value: 'KG', label: 'Kilograms' }]} value={line.unit} onChange={(unit) => setLine('chemicals', line.id, { unit })} />
                      <View style={styles.inputs}>
                        <NumField label={`Dose (${line.unit === 'L' ? 'l' : 'kg'}/ha)`} value={line.dosePerHa} disabled={!editable} onChange={(value) => setLine('chemicals', line.id, { dosePerHa: value ?? 0 })} />
                        <NumField label={`Price (€/${line.unit === 'L' ? 'l' : 'kg'})`} value={line.pricePerUnit} disabled={!editable} onChange={(value) => setLine('chemicals', line.id, { pricePerUnit: value ?? 0 })} />
                        <NumField label="Treatments" value={line.applications ?? 1} disabled={!editable} onChange={(value) => setLine('chemicals', line.id, { applications: Math.max(1, Math.round(value ?? 1)) })} />
                      </View>
                      <Txt variant="small">{`${number(cost.quantity)} ${line.unit === 'L' ? 'l' : 'kg'} for the campaign, ${formatEuro(cost.total, 0)}`}</Txt>
                    </LineCard>
                  );
                })}
                {draft.chemicals.length > 0 ? (
                  <Row label="Treatments, whole campaign" value={formatEuro(draft.chemicals.reduce((sum, line) => sum + chemicalCost(line, areaHa).total, 0), 0)} />
                ) : null}
                {editable ? (
                  <>
                    <Txt variant="small">Add a product</Txt>
                    <PresetChips presets={CHEMICAL_PRESETS} onPick={(preset) => update((sheet) => ({ ...sheet, chemicals: [...sheet.chemicals, { ...preset, id: newLineId(), applications: 1 }] }))} />
                  </>
                ) : null}
              </Panel>
            </Section>

            <Section title="Other costs">
              <Panel>
                {draft.misc.map((line: MiscLine) => (
                  <LineCard
                    key={line.id}
                    title={line.label}
                    subtitle={line.basis === 'PER_HA' ? `${formatEuro(line.amount, 2)}/ha` : 'For the whole parcel'}
                    figures={<Figure main={formatEuro(miscCost(line, areaHa), 0)} detail="" />}
                    editable={editable}
                    onRemove={() => removeLine('misc', line.id)}
                  >
                    <Field label="What is it (rent, insurance, drying, transport...)" value={line.label} editable={editable} onChangeText={(label) => setLine('misc', line.id, { label })} />
                    <View style={styles.inputs}>
                      <NumField label="Amount (€)" value={line.amount} disabled={!editable} onChange={(value) => setLine('misc', line.id, { amount: value ?? 0 })} />
                    </View>
                    <Choice options={[{ value: 'PER_HA', label: 'Per hectare' }, { value: 'TOTAL', label: 'For the parcel' }]} value={line.basis} onChange={(basis) => setLine('misc', line.id, { basis })} />
                  </LineCard>
                ))}
                {editable ? (
                  <Button mode="outlined" icon="plus" onPress={() => update((sheet) => ({ ...sheet, misc: [...sheet.misc, { id: newLineId(), label: '', amount: 0, basis: 'PER_HA' }] }))}>
                    Add a cost
                  </Button>
                ) : null}
              </Panel>
            </Section>

            {editable ? (
              <Button mode="contained" icon="content-save-outline" disabled={!dirty || save.isPending} loading={save.isPending} onPress={() => save.mutate(draft)}>
                {dirty ? 'Save and recalculate' : 'Saved'}
              </Button>
            ) : null}

            <Section title="Field operations">
              <Panel>
                {item.result.operations.length + item.result.projected.length === 0 ? <Txt variant="small">No operations yet.</Txt> : null}
                {item.result.operations.map((op) => <OperationRow key={op.id} op={op} />)}
                {item.result.projected.length > 0 ? <Txt variant="small">Still to come, estimated from the technology and the inputs:</Txt> : null}
                {item.result.projected.map((op) => <OperationRow key={op.id} op={op} />)}
              </Panel>
            </Section>
          </>
        )
      }
    </QueryState>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  inline: { flexDirection: 'row', alignItems: 'center', gap: space.sm },
  kpis: { flexDirection: 'row', flexWrap: 'wrap', gap: space.sm },
  inputs: { flexDirection: 'row', gap: space.sm },
  line: { borderRadius: 10, borderWidth: 1, borderColor: colors.line, overflow: 'hidden' },
  lineHead: { flexDirection: 'row', alignItems: 'center', gap: space.sm, padding: space.md, minHeight: 56 },
  lineBody: { padding: space.md, paddingTop: 0, gap: space.sm },
  lineFigures: { alignItems: 'flex-end' },
  figure: { fontFamily: fonts.semibold },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: space.xs },
  chip: { flexDirection: 'row', alignItems: 'center', gap: 4, minHeight: 34, paddingHorizontal: 10, borderRadius: 17, borderWidth: 1, borderColor: colors.line },
  profit: { gap: space.xs, paddingTop: space.sm, borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: colors.line },
  opRow: { flexDirection: 'row', gap: space.sm, paddingVertical: space.sm, borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: colors.line },
  tag: { paddingHorizontal: 6, borderRadius: 8, backgroundColor: `${colors.water}26` },
  tagText: { fontFamily: fonts.semibold, fontSize: 10.5, lineHeight: 15 },
});
