import { useMemo, useState } from 'react';
import { Pressable, StyleSheet, View, type GestureResponderEvent, type LayoutChangeEvent } from 'react-native';
import Svg, { Circle, G, Line, Path, Rect, Text as SvgText } from 'react-native-svg';
import { Txt } from '../../components/ui';
import { colors, fonts, space } from '../../theme';

/**
 * Small chart kit for the tillage analysis. Rules followed throughout: one
 * y-axis per chart, thin marks, a 2px surface gap between filled segments,
 * recessive grid, a legend for every multi-series chart and a tap tooltip.
 */

const GRID = colors.line;
const INK_MUTED = colors.straw;
const SURFACE = colors.furrow;

const useWidth = (initial = 300) => {
  const [width, setWidth] = useState(initial);
  const onLayout = (event: LayoutChangeEvent) => {
    const next = Math.round(event.nativeEvent.layout.width);
    if (next > 0 && next !== width) setWidth(next);
  };
  return { width, onLayout };
};

export interface Series {
  key: string;
  label: string;
  color: string;
}

export function Legend({ series, values }: { series: Series[]; values?: Record<string, string> }) {
  return (
    <View style={styles.legend}>
      {series.map((item) => (
        <View key={item.key} style={styles.legendItem}>
          <View style={[styles.swatch, { backgroundColor: item.color }]} />
          <Txt variant="small" color={colors.chaff} style={styles.legendLabel}>{item.label}</Txt>
          {values?.[item.key] ? <Txt variant="small">{values[item.key]}</Txt> : null}
        </View>
      ))}
    </View>
  );
}

// ------------------------------------------------------------------ donut

function arc(cx: number, cy: number, r: number, start: number, end: number) {
  const point = (angle: number) => `${(cx + r * Math.cos(angle)).toFixed(2)} ${(cy + r * Math.sin(angle)).toFixed(2)}`;
  const large = end - start > Math.PI ? 1 : 0;
  return `M ${point(start)} A ${r} ${r} 0 ${large} 1 ${point(end)}`;
}

/** Share of a whole. Tap a segment to read it in the centre. */
export function Donut({
  data,
  total,
  unit,
  centerLabel,
}: {
  data: (Series & { value: number })[];
  total: number;
  unit: string;
  centerLabel: string;
}) {
  const [active, setActive] = useState<string | null>(null);
  const size = 180;
  const stroke = 22;
  const r = (size - stroke) / 2;
  const c = size / 2;
  const visible = data.filter((item) => item.value > 0);
  // A 2px gap at the surface between segments, expressed as an angle.
  const gap = visible.length > 1 ? 2 / r : 0;
  let cursor = -Math.PI / 2;
  const segments = visible.map((item) => {
    const sweep = (item.value / Math.max(total, 1e-9)) * Math.PI * 2;
    const segment = { ...item, start: cursor + gap / 2, end: cursor + sweep - gap / 2 };
    cursor += sweep;
    return segment;
  });
  const focus = data.find((item) => item.key === active);
  const pct = (value: number) => (total > 0 ? Math.round((value / total) * 100) : 0);

  return (
    <View style={styles.donutWrap}>
      <Svg width={size} height={size} accessibilityLabel={`${centerLabel}: ${data.map((item) => `${item.label} ${pct(item.value)}%`).join(', ')}`}>
        <Circle cx={c} cy={c} r={r} stroke={colors.ridge} strokeWidth={stroke} fill="none" />
        {segments.map((segment) => (
          <Path
            key={segment.key}
            d={segments.length === 1 ? `${arc(c, c, r, -Math.PI / 2, Math.PI * 1.5 - 0.0001)}` : arc(c, c, r, segment.start, segment.end)}
            stroke={segment.color}
            strokeWidth={active === segment.key ? stroke + 6 : stroke}
            strokeOpacity={active && active !== segment.key ? 0.45 : 1}
            fill="none"
            onPress={() => setActive((current) => (current === segment.key ? null : segment.key))}
          />
        ))}
      </Svg>
      <View pointerEvents="none" style={[StyleSheet.absoluteFill, styles.donutCenter]}>
        <Txt variant="figure" style={styles.donutFigure}>{focus ? `${pct(focus.value)}%` : String(Math.round(total))}</Txt>
        <Txt variant="small" numberOfLines={2} style={styles.donutLabel}>{focus ? focus.label : `${unit} ${centerLabel}`}</Txt>
      </View>
    </View>
  );
}

// ------------------------------------------------------------------ horizontal bars

/** Magnitude per category, labelled at the end of each bar. */
export function BarList({ data, format }: { data: (Series & { value: number; detail?: string })[]; format: (value: number) => string }) {
  const max = Math.max(...data.map((item) => item.value), 1e-9);
  return (
    <View style={{ gap: space.md }}>
      {data.map((item) => (
        <View key={item.key} style={{ gap: 4 }} accessibilityLabel={`${item.label}: ${format(item.value)}`}>
          <View style={styles.barHead}>
            <Txt variant="small" color={colors.chaff} style={styles.legendLabel}>{item.label}</Txt>
            <Txt variant="small" color={colors.chaff}>{format(item.value)}</Txt>
          </View>
          <View style={styles.barTrack}>
            <View style={[styles.barFill, { width: `${(item.value / max) * 100}%`, backgroundColor: item.color }]} />
          </View>
          {item.detail ? <Txt variant="small" style={styles.barDetail}>{item.detail}</Txt> : null}
        </View>
      ))}
    </View>
  );
}

// ------------------------------------------------------------------ stacked columns

/** Hectares per technology, year by year. Tap a column for its breakdown. */
export function StackedColumns({
  years,
  series,
  values,
  unit,
}: {
  years: number[];
  series: Series[];
  /** values[yearIndex][seriesKey] */
  values: Record<string, number>[];
  unit: string;
}) {
  const { width, onLayout } = useWidth();
  const [active, setActive] = useState<number | null>(null);
  const height = 170;
  const left = 34;
  const bottom = 22;
  const plotH = height - bottom - 8;
  const totals = values.map((row) => series.reduce((sum, item) => sum + (row[item.key] ?? 0), 0));
  const max = niceMax(Math.max(...totals, 1));
  const slot = (width - left) / Math.max(years.length, 1);
  const barW = Math.min(36, slot * 0.6);
  const ticks = [0, max / 2, max];

  return (
    <View onLayout={onLayout} style={{ gap: space.sm }}>
      <Svg width={width} height={height}>
        {ticks.map((tick) => {
          const y = 8 + plotH - (tick / max) * plotH;
          return (
            <G key={tick}>
              <Line x1={left} x2={width} y1={y} y2={y} stroke={GRID} strokeWidth={1} strokeDasharray={tick === 0 ? undefined : '3 4'} />
              <SvgText x={left - 6} y={y + 4} fontSize={10} fill={INK_MUTED} textAnchor="end" fontFamily={fonts.regular}>{compact(tick)}</SvgText>
            </G>
          );
        })}
        {years.map((year, index) => {
          const x = left + slot * index + (slot - barW) / 2;
          let y = 8 + plotH;
          const parts = series
            .map((item) => ({ item, value: values[index]?.[item.key] ?? 0 }))
            .filter((part) => part.value > 0);
          return (
            <G key={year} opacity={active === null || active === index ? 1 : 0.45}>
              {parts.map((part, partIndex) => {
                const h = (part.value / max) * plotH;
                y -= h;
                // A 2px surface gap between stacked segments; the top segment gets rounded ends.
                const isTop = partIndex === parts.length - 1;
                return (
                  <Rect
                    key={part.item.key}
                    x={x}
                    y={y}
                    width={barW}
                    height={Math.max(0, h - (isTop ? 0 : 2))}
                    rx={isTop ? 4 : 0}
                    fill={part.item.color}
                  />
                );
              })}
              <SvgText x={x + barW / 2} y={height - 6} fontSize={10.5} fill={active === index ? colors.chaff : INK_MUTED} textAnchor="middle" fontFamily={fonts.medium}>
                {String(year)}
              </SvgText>
              <Rect x={left + slot * index} y={0} width={slot} height={height} fill="transparent" onPress={() => setActive((current) => (current === index ? null : index))} />
            </G>
          );
        })}
      </Svg>
      {active !== null ? (
        <View style={styles.tooltip}>
          <Txt variant="subtitle">{`${years[active]}, ${compact(totals[active] ?? 0)} ${unit}`}</Txt>
          {series.map((item) => (
            <View key={item.key} style={styles.tipRow}>
              <View style={[styles.swatch, { backgroundColor: item.color }]} />
              <Txt variant="small" style={{ flex: 1 }}>{item.label}</Txt>
              <Txt variant="small" color={colors.chaff}>{`${compact(values[active]?.[item.key] ?? 0)} ${unit}`}</Txt>
            </View>
          ))}
        </View>
      ) : null}
      <Legend series={series} />
    </View>
  );
}

// ------------------------------------------------------------------ line

/** One measure over time, with a crosshair that follows the finger. */
export function TrendLine({
  points,
  color,
  domain,
  format,
  label,
}: {
  points: { x: number; y: number | null }[];
  color: string;
  domain: [number, number];
  format: (value: number) => string;
  label: string;
}) {
  const { width, onLayout } = useWidth();
  const [active, setActive] = useState<number | null>(null);
  const height = 150;
  const left = 34;
  const right = 12;
  const top = 12;
  const bottom = 22;
  const plotW = width - left - right;
  const plotH = height - top - bottom;
  const [lo, hi] = domain;
  const xAt = (index: number) => left + (points.length > 1 ? (index / (points.length - 1)) * plotW : plotW / 2);
  const yAt = (value: number) => top + plotH - ((value - lo) / Math.max(hi - lo, 1e-9)) * plotH;

  const path = useMemo(() => {
    let d = '';
    let pen = false;
    points.forEach((point, index) => {
      if (point.y == null) {
        pen = false;
        return;
      }
      d += `${pen ? 'L' : 'M'} ${xAt(index).toFixed(1)} ${yAt(point.y).toFixed(1)} `;
      pen = true;
    });
    return d;
    // xAt and yAt depend only on the values listed.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [points, width, lo, hi]);

  const pick = (event: GestureResponderEvent) => {
    const x = event.nativeEvent.locationX;
    const index = points.length > 1 ? Math.round(((x - left) / plotW) * (points.length - 1)) : 0;
    setActive(Math.max(0, Math.min(points.length - 1, index)));
  };
  const focus = active !== null ? points[active] : null;
  const ticks = [lo, (lo + hi) / 2, hi];

  return (
    <View onLayout={onLayout}>
      <View
        onStartShouldSetResponder={() => true}
        onMoveShouldSetResponder={(event) => Math.abs(event.nativeEvent.locationX) > 0}
        onResponderGrant={pick}
        onResponderMove={pick}
        accessibilityLabel={`${label}: ${points.map((point) => `${point.x} ${point.y == null ? 'no data' : format(point.y)}`).join(', ')}`}
      >
        <Svg width={width} height={height}>
          {ticks.map((tick) => (
            <G key={tick}>
              <Line x1={left} x2={width - right} y1={yAt(tick)} y2={yAt(tick)} stroke={GRID} strokeWidth={1} strokeDasharray={tick === lo ? undefined : '3 4'} />
              <SvgText x={left - 6} y={yAt(tick) + 4} fontSize={10} fill={INK_MUTED} textAnchor="end" fontFamily={fonts.regular}>{format(tick)}</SvgText>
            </G>
          ))}
          {points.map((point, index) => (
            <SvgText key={point.x} x={xAt(index)} y={height - 6} fontSize={10.5} fill={active === index ? colors.chaff : INK_MUTED} textAnchor="middle" fontFamily={fonts.medium}>
              {String(point.x)}
            </SvgText>
          ))}
          {focus ? <Line x1={xAt(active!)} x2={xAt(active!)} y1={top} y2={top + plotH} stroke={colors.straw} strokeWidth={1} /> : null}
          <Path d={path} stroke={color} strokeWidth={2} fill="none" strokeLinejoin="round" strokeLinecap="round" />
          {points.map((point, index) =>
            point.y == null ? null : (
              <Circle
                key={point.x}
                cx={xAt(index)}
                cy={yAt(point.y)}
                r={active === index ? 6 : 4}
                fill={color}
                stroke={SURFACE}
                strokeWidth={2}
              />
            ),
          )}
        </Svg>
      </View>
      <View style={styles.trendFoot}>
        <Txt variant="small">{label}</Txt>
        <Txt variant="small" color={colors.chaff}>{focus ? `${focus.x}: ${focus.y == null ? 'no data' : format(focus.y)}` : 'Touch the chart to read a year'}</Txt>
      </View>
    </View>
  );
}

// ------------------------------------------------------------------ helpers

export function niceMax(value: number) {
  if (value <= 0) return 1;
  const magnitude = 10 ** Math.floor(Math.log10(value));
  const step = [1, 2, 2.5, 5, 10].find((candidate) => candidate * magnitude >= value)!;
  return step * magnitude;
}

export const compact = (value: number) =>
  Math.abs(value) >= 10_000 ? `${Math.round(value / 1000)}k` : Math.abs(value) >= 1000 ? `${(value / 1000).toFixed(1)}k` : String(Math.round(value));

/** Switch between the chart and a plain table of the same numbers. */
export function ChartTableToggle({ table, onChange }: { table: boolean; onChange: (table: boolean) => void }) {
  return (
    <View style={styles.toggle}>
      {[
        { value: false, label: 'Chart' },
        { value: true, label: 'Table' },
      ].map((option) => (
        <Pressable
          key={option.label}
          accessibilityRole="radio"
          accessibilityState={{ selected: table === option.value }}
          onPress={() => onChange(option.value)}
          style={[styles.toggleItem, table === option.value && styles.toggleOn]}
        >
          <Txt variant="small" color={table === option.value ? colors.wheatInk : colors.straw} style={styles.toggleText}>{option.label}</Txt>
        </Pressable>
      ))}
    </View>
  );
}

export function DataTable({ columns, rows }: { columns: string[]; rows: (string | number)[][] }) {
  return (
    <View style={styles.table}>
      <View style={[styles.tableRow, styles.tableHead]}>
        {columns.map((column, index) => (
          <Txt key={column} variant="small" color={colors.chaff} style={[styles.cell, index > 0 && styles.cellNum, styles.headText]}>{column}</Txt>
        ))}
      </View>
      {rows.map((row) => (
        <View key={String(row[0])} style={styles.tableRow}>
          {row.map((cell, index) => (
            <Txt key={index} variant="small" color={index === 0 ? colors.chaff : undefined} style={[styles.cell, index > 0 && styles.cellNum]}>{String(cell)}</Txt>
          ))}
        </View>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  legend: { flexDirection: 'row', flexWrap: 'wrap', columnGap: space.lg, rowGap: space.xs },
  legendItem: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  legendLabel: { fontFamily: fonts.medium },
  swatch: { width: 10, height: 10, borderRadius: 3 },
  donutWrap: { width: 180, height: 180, alignSelf: 'center' },
  donutCenter: { alignItems: 'center', justifyContent: 'center', paddingHorizontal: 40 },
  donutFigure: { fontSize: 28 },
  donutLabel: { textAlign: 'center', fontSize: 11.5, lineHeight: 14 },
  barHead: { flexDirection: 'row', justifyContent: 'space-between' },
  barTrack: { height: 10, borderRadius: 4, backgroundColor: colors.ridge, overflow: 'hidden' },
  barFill: { height: '100%', borderTopRightRadius: 4, borderBottomRightRadius: 4 },
  barDetail: { fontSize: 11.5, lineHeight: 15 },
  tooltip: { padding: space.md, borderRadius: 10, backgroundColor: colors.ridge, gap: 4, borderWidth: 1, borderColor: colors.line },
  tipRow: { flexDirection: 'row', alignItems: 'center', gap: space.sm },
  trendFoot: { flexDirection: 'row', justifyContent: 'space-between', gap: space.sm },
  toggle: { flexDirection: 'row', borderRadius: 8, borderWidth: 1, borderColor: colors.line, overflow: 'hidden' },
  toggleItem: { paddingHorizontal: space.md, minHeight: 32, justifyContent: 'center' },
  toggleOn: { backgroundColor: colors.wheat },
  toggleText: { fontFamily: fonts.semibold, fontSize: 12 },
  table: { borderRadius: 8, borderWidth: 1, borderColor: colors.line, overflow: 'hidden' },
  tableRow: { flexDirection: 'row', paddingHorizontal: space.sm, paddingVertical: 6, borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: colors.line },
  tableHead: { backgroundColor: colors.ridge, borderTopWidth: 0 },
  headText: { fontFamily: fonts.semibold },
  cell: { flex: 1.4 },
  cellNum: { flex: 1, textAlign: 'right' },
});
