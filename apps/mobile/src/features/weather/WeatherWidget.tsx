import { MaterialCommunityIcons } from '@expo/vector-icons';
import type { ConditionKind, FarmInsight, FieldAlert, FieldAlertType, InsightTone, WeatherBrief } from '@fermapp/domain';
import { useState, type ReactNode } from 'react';
import { ActivityIndicator, Platform, Pressable, StyleSheet, Text, View, type StyleProp, type ViewStyle } from 'react-native';
import Animated, { FadeIn, FadeInUp, LinearTransition } from 'react-native-reanimated';
import Svg, { Defs, LinearGradient, Rect, Stop } from 'react-native-svg';
import type { IconName } from '../../components/ui';
import { clock } from '../../lib/format';
import { colors, fonts, space } from '../../theme';
import { useWeatherPrefs } from './prefs';
import { useFarmWeather, type FarmWeather } from './useFarmWeather';
import { WeatherAmbience } from './WeatherAmbience';

const CARD_MAX_WIDTH = 352;
const AMBIENCE_HEIGHT = 118;

const CONDITION_ICON: Record<ConditionKind, { day: IconName; night?: IconName; color: string }> = {
  CLEAR: { day: 'weather-sunny', night: 'weather-night', color: colors.wheat },
  PARTLY_CLOUDY: { day: 'weather-partly-cloudy', night: 'weather-night-partly-cloudy', color: colors.chaff },
  CLOUDY: { day: 'weather-cloudy', color: colors.straw },
  FOG: { day: 'weather-fog', color: colors.straw },
  DRIZZLE: { day: 'weather-partly-rainy', color: colors.water },
  RAIN: { day: 'weather-rainy', color: colors.water },
  HEAVY_RAIN: { day: 'weather-pouring', color: colors.water },
  SNOW: { day: 'weather-snowy', color: colors.chaff },
  SLEET: { day: 'weather-snowy-rainy', color: colors.water },
  STORM: { day: 'weather-lightning-rainy', color: colors.amber },
  HAIL: { day: 'weather-hail', color: colors.amber },
};

const ALERT_ICON: Record<FieldAlertType, IconName> = {
  STORM: 'weather-lightning',
  FROST: 'snowflake-thermometer',
  WIND: 'weather-windy',
  HEAT: 'thermometer-high',
  HEAVY_RAIN: 'weather-pouring',
};

const TONE_COLOR: Record<InsightTone, string> = { GOOD: colors.leaf, INFO: colors.water, CAUTION: colors.amber, WARNING: colors.rust };

const INSIGHT_ICON: Record<FarmInsight['code'], IconName> = {
  SPRAY_WIND: 'weather-windy-variant',
  RAIN_SOON: 'weather-rainy',
  SPRAY_GOOD: 'spray',
  SPRAY_HOT: 'thermometer-high',
  SPRAY_COLD: 'thermometer-low',
  SPRAY_DRY_AIR: 'water-outline',
  WET_SOIL: 'tractor-variant',
  SOIL_DRYING: 'sprout',
  STABLE: 'check-circle-outline',
};

const degrees = (value: number) => `${Math.round(value)}°`;
const percent = (value: number | null | undefined) => (value == null ? '–' : `${Math.round(value)}%`);
const alertColor = (alert: FieldAlert) => (alert.severity === 'SEVERE' ? colors.rust : colors.amber);

function conditionIcon(kind: ConditionKind, isDay = true) {
  const entry = CONDITION_ICON[kind];
  return { name: (!isDay && entry.night) || entry.day, color: !isDay && kind === 'CLEAR' ? colors.straw : entry.color };
}

/** Frosted surface: translucent field-dark tint, a light sheen from the top and a hairline edge. */
function Glass({ children, style, radius }: { children: ReactNode; style?: StyleProp<ViewStyle>; radius: number }) {
  return (
    <View style={[styles.shadow, { borderRadius: radius }, style]}>
      <View style={[styles.glass, { borderRadius: radius }]}>
        <Svg pointerEvents="none" style={StyleSheet.absoluteFill} preserveAspectRatio="none" viewBox="0 0 10 10">
          <Defs>
            <LinearGradient id="sheen" x1="0" y1="0" x2="0" y2="1">
              <Stop offset="0" stopColor="#FFFFFF" stopOpacity={0.11} />
              <Stop offset="0.5" stopColor="#FFFFFF" stopOpacity={0.02} />
              <Stop offset="1" stopColor="#FFFFFF" stopOpacity={0} />
            </LinearGradient>
          </Defs>
          <Rect width="10" height="10" fill="url(#sheen)" />
        </Svg>
        {children}
      </View>
    </View>
  );
}

function CollapsedPill({ weather, onPress }: { weather: FarmWeather; onPress: () => void }) {
  const { brief } = weather;
  const top = brief?.alerts[0];
  const icon = brief ? conditionIcon(brief.current.condition.kind, brief.current.isDay) : null;
  const label = brief
    ? `Weather: ${Math.round(brief.current.temperatureC)} degrees, ${brief.current.condition.label}.${brief.alerts.length ? ` ${brief.alerts.length} weather alerts.` : ''} Tap to expand.`
    : 'Weather. Tap to expand.';
  return (
    <Animated.View entering={FadeIn.duration(180)}>
      <Pressable onPress={onPress} accessibilityRole="button" accessibilityLabel={label} hitSlop={6}>
        <Glass radius={22} style={styles.pillOuter}>
          <View style={styles.pill}>
            {brief && icon ? (
              <>
                <MaterialCommunityIcons name={icon.name} size={22} color={icon.color} />
                <Text style={styles.pillTemp}>{degrees(brief.current.temperatureC)}</Text>
                {brief.today ? (
                  <Text style={styles.pillRange}>
                    {degrees(brief.today.tMaxC)}
                    <Text style={styles.pillLow}> / {degrees(brief.today.tMinC)}</Text>
                  </Text>
                ) : null}
                {top ? (
                  <View style={[styles.pillAlert, { backgroundColor: alertColor(top) }]}>
                    <MaterialCommunityIcons name={ALERT_ICON[top.type]} size={12} color={colors.loam} />
                    {brief.alerts.length > 1 ? <Text style={styles.pillAlertCount}>{brief.alerts.length}</Text> : null}
                  </View>
                ) : null}
              </>
            ) : weather.error ? (
              <>
                <MaterialCommunityIcons name="weather-cloudy-alert" size={20} color={colors.straw} />
                <Text style={styles.pillRange}>Weather</Text>
              </>
            ) : (
              <>
                <ActivityIndicator size="small" color={colors.straw} />
                <Text style={styles.pillRange}>Weather</Text>
              </>
            )}
          </View>
        </Glass>
      </Pressable>
    </Animated.View>
  );
}

function Metric({ icon, label, value, detail }: { icon: IconName; label: string; value: string; detail?: string }) {
  return (
    <View style={styles.metric} accessible accessibilityLabel={`${label}: ${value}${detail ? `, ${detail}` : ''}`}>
      <MaterialCommunityIcons name={icon} size={16} color={colors.straw} />
      <View style={{ flexShrink: 1 }}>
        <Text style={styles.metricValue} numberOfLines={1}>{value}</Text>
        <Text style={styles.metricLabel} numberOfLines={1}>{detail ?? label}</Text>
      </View>
    </View>
  );
}

function Alerts({ alerts }: { alerts: FieldAlert[] }) {
  const [selected, setSelected] = useState<FieldAlertType | null>(alerts[0]?.type ?? null);
  const active = alerts.find((alert) => alert.type === selected) ?? alerts[0];
  if (!active) return null;
  return (
    <View style={styles.alerts}>
      <View style={styles.alertChips}>
        {alerts.map((alert) => {
          const tint = alertColor(alert);
          const on = alert.type === active.type;
          return (
            <Pressable
              key={alert.type}
              onPress={() => setSelected(alert.type)}
              accessibilityRole="button"
              accessibilityState={{ selected: on }}
              accessibilityLabel={`${alert.title}. ${alert.message}`}
              style={[styles.alertChip, { borderColor: `${tint}${on ? 'CC' : '55'}`, backgroundColor: `${tint}${on ? '33' : '1A'}` }]}
            >
              <MaterialCommunityIcons name={ALERT_ICON[alert.type]} size={13} color={tint} />
              <Text style={styles.alertChipText}>{alert.title}</Text>
            </Pressable>
          );
        })}
      </View>
      <Animated.Text key={active.type} entering={FadeIn.duration(160)} style={styles.alertMessage}>
        {active.message}
      </Animated.Text>
    </View>
  );
}

function Outlook({ days }: { days: WeatherBrief['days'] }) {
  return (
    <View style={styles.days}>
      {days.map((day) => {
        const icon = conditionIcon(day.condition.kind);
        const rain = day.rainProbabilityPct ?? 0;
        return (
          <View
            key={day.date}
            style={styles.day}
            accessible
            accessibilityLabel={`${day.weekday}: ${day.condition.label}, high ${Math.round(day.tMaxC)}, low ${Math.round(day.tMinC)} degrees, ${Math.round(rain)} percent chance of rain`}
          >
            <Text style={styles.dayName}>{day.weekday}</Text>
            <MaterialCommunityIcons name={icon.name} size={22} color={icon.color} />
            <Text style={[styles.dayRain, rain < 10 && { opacity: 0 }]}>{percent(rain)}</Text>
            <Text style={styles.dayMax}>{degrees(day.tMaxC)}</Text>
            <Text style={styles.dayMin}>{degrees(day.tMinC)}</Text>
          </View>
        );
      })}
    </View>
  );
}

function ExpandedCard({ weather, collapsible, onCollapse }: { weather: FarmWeather; collapsible: boolean; onCollapse: () => void }) {
  const [width, setWidth] = useState(0);
  const { brief, place } = weather;
  const icon = brief ? conditionIcon(brief.current.condition.kind, brief.current.isDay) : null;
  return (
    <Animated.View entering={FadeInUp.duration(220)} style={styles.cardSlot}>
      <Glass radius={18}>
        <View onLayout={(event) => setWidth(event.nativeEvent.layout.width)}>
          {brief ? <WeatherAmbience kind={brief.current.condition.kind} isDay={brief.current.isDay} width={width} height={AMBIENCE_HEIGHT} /> : null}
          <View style={styles.card}>
            <View style={styles.header}>
              <MaterialCommunityIcons name="map-marker-outline" size={15} color={colors.straw} />
              <View style={{ flex: 1 }}>
                <Text style={styles.place} numberOfLines={1}>{place?.label ?? 'Weather'}</Text>
                <Text style={styles.updated} numberOfLines={1}>
                  {weather.updatedAt ? `Updated ${clock(weather.updatedAt)}` : weather.loading ? 'Loading forecast…' : 'No forecast yet'}
                </Text>
              </View>
              <Pressable onPress={weather.refresh} disabled={weather.refreshing} hitSlop={8} accessibilityRole="button" accessibilityLabel="Refresh weather" style={styles.headerButton}>
                {weather.refreshing ? <ActivityIndicator size="small" color={colors.straw} /> : <MaterialCommunityIcons name="refresh" size={18} color={colors.straw} />}
              </Pressable>
              {collapsible ? (
                <Pressable onPress={onCollapse} hitSlop={8} accessibilityRole="button" accessibilityLabel="Collapse weather" style={styles.headerButton}>
                  <MaterialCommunityIcons name="chevron-up" size={22} color={colors.chaff} />
                </Pressable>
              ) : null}
            </View>

            {brief && icon ? (
              <>
                <View style={styles.hero}>
                  <MaterialCommunityIcons name={icon.name} size={46} color={icon.color} />
                  <Text style={styles.temp} accessibilityLabel={`${Math.round(brief.current.temperatureC)} degrees`}>
                    {degrees(brief.current.temperatureC)}
                  </Text>
                  <View style={styles.heroSide}>
                    <Text style={styles.condition} numberOfLines={1}>{brief.current.condition.label}</Text>
                    {brief.today ? (
                      <Text style={styles.range}>
                        H {degrees(brief.today.tMaxC)}  ·  L {degrees(brief.today.tMinC)}
                      </Text>
                    ) : null}
                  </View>
                </View>

                <View style={styles.metrics}>
                  <Metric icon="water-percent" label="Humidity" value={percent(brief.current.humidityPct)} />
                  <Metric icon="umbrella-outline" label="Rain today" value={percent(brief.today?.rainProbabilityPct ?? brief.current.precipitationProbabilityPct)} />
                  <Metric
                    icon="weather-windy"
                    label="Wind"
                    value={`${Math.round(brief.current.windKmh)} km/h`}
                    detail={brief.current.gustKmh != null ? `gusts ${Math.round(brief.current.gustKmh)}` : undefined}
                  />
                </View>

                {brief.alerts.length ? <Alerts alerts={brief.alerts} /> : null}

                <View style={styles.insights}>
                  <Text style={styles.sectionLabel}>FIELD CONDITIONS</Text>
                  {brief.insights.map((insight) => (
                    <View key={insight.code} style={styles.insight}>
                      <MaterialCommunityIcons name={INSIGHT_ICON[insight.code]} size={15} color={TONE_COLOR[insight.tone]} style={{ marginTop: 1 }} />
                      <Text style={styles.insightText}>{insight.message}</Text>
                    </View>
                  ))}
                </View>

                {brief.days.length ? <Outlook days={brief.days} /> : null}
              </>
            ) : (
              <View style={styles.placeholder}>
                {weather.error ? (
                  <>
                    <Text style={styles.insightText}>The forecast could not be loaded.</Text>
                    <Pressable onPress={weather.refresh} accessibilityRole="button" style={styles.retry}>
                      <Text style={styles.retryText}>Try again</Text>
                    </Pressable>
                  </>
                ) : (
                  <ActivityIndicator color={colors.straw} />
                )}
              </View>
            )}
          </View>
        </View>
      </Glass>
    </Animated.View>
  );
}

/**
 * Weather over the map. On phones it is a small pill that opens on tap; on wide
 * screens it starts open. Mount it only when the setting is on: it loads data as soon as it mounts.
 */
export function WeatherWidget({ wide }: { wide: boolean }) {
  const weather = useFarmWeather();
  const expandedPref = useWeatherPrefs((state) => state.expanded);
  const setExpanded = useWeatherPrefs((state) => state.setExpanded);
  // Without parcels or a known position there is nothing to forecast.
  if (!weather.place) return null;
  const expanded = expandedPref ?? wide;
  return (
    <Animated.View layout={LinearTransition.duration(220)} style={wide ? styles.wide : styles.narrow}>
      {expanded ? (
        <ExpandedCard weather={weather} collapsible onCollapse={() => setExpanded(false)} />
      ) : (
        <CollapsedPill weather={weather} onPress={() => setExpanded(true)} />
      )}
    </Animated.View>
  );
}

const glassTint = Platform.OS === 'android' ? 'rgba(24,27,19,0.80)' : 'rgba(24,27,19,0.62)';

const styles = StyleSheet.create({
  wide: { width: CARD_MAX_WIDTH, alignItems: 'flex-start' },
  narrow: { width: '100%', maxWidth: CARD_MAX_WIDTH, alignItems: 'flex-start' },
  cardSlot: { alignSelf: 'stretch' },
  shadow: {
    shadowColor: '#000',
    shadowOpacity: 0.35,
    shadowRadius: 18,
    shadowOffset: { width: 0, height: 8 },
    elevation: 8,
  },
  glass: {
    overflow: 'hidden',
    backgroundColor: glassTint,
    borderWidth: 1,
    borderColor: 'rgba(240,236,219,0.14)',
  },
  pillOuter: { alignSelf: 'flex-start' },
  pill: { height: 44, flexDirection: 'row', alignItems: 'center', gap: 8, paddingLeft: 12, paddingRight: 14 },
  pillTemp: { fontFamily: fonts.figure, fontSize: 22, lineHeight: 26, color: colors.chaff },
  pillRange: { fontFamily: fonts.semibold, fontSize: 13, color: colors.chaff },
  pillLow: { fontFamily: fonts.regular, color: colors.straw },
  pillAlert: { flexDirection: 'row', alignItems: 'center', gap: 2, height: 20, minWidth: 20, paddingHorizontal: 4, borderRadius: 10, justifyContent: 'center' },
  pillAlertCount: { fontFamily: fonts.bold, fontSize: 11, color: colors.loam },
  card: { padding: space.md, paddingTop: space.sm + 2, gap: space.md },
  header: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  headerButton: { width: 32, height: 32, alignItems: 'center', justifyContent: 'center' },
  place: { fontFamily: fonts.semibold, fontSize: 14, lineHeight: 18, color: colors.chaff },
  updated: { fontFamily: fonts.regular, fontSize: 11, lineHeight: 14, color: colors.straw },
  hero: { flexDirection: 'row', alignItems: 'center', gap: space.sm },
  temp: { fontFamily: fonts.figure, fontSize: 46, lineHeight: 50, color: colors.chaff, letterSpacing: -0.5 },
  heroSide: { flex: 1, alignItems: 'flex-end', gap: 2 },
  condition: { fontFamily: fonts.semibold, fontSize: 15, color: colors.chaff },
  range: { fontFamily: fonts.medium, fontSize: 13, color: colors.straw },
  metrics: {
    flexDirection: 'row',
    gap: space.xs,
    paddingVertical: space.sm,
    paddingHorizontal: space.sm,
    borderRadius: 12,
    backgroundColor: 'rgba(240,236,219,0.06)',
  },
  metric: { flex: 1, flexDirection: 'row', alignItems: 'center', gap: 6 },
  metricValue: { fontFamily: fonts.semibold, fontSize: 14, lineHeight: 18, color: colors.chaff },
  metricLabel: { fontFamily: fonts.regular, fontSize: 11, lineHeight: 14, color: colors.straw },
  alerts: { gap: 6 },
  alertChips: { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
  alertChip: { flexDirection: 'row', alignItems: 'center', gap: 4, paddingHorizontal: 8, height: 26, borderRadius: 13, borderWidth: 1 },
  alertChipText: { fontFamily: fonts.semibold, fontSize: 12, color: colors.chaff },
  alertMessage: { fontFamily: fonts.regular, fontSize: 12.5, lineHeight: 17, color: colors.chaff },
  insights: { gap: 6 },
  sectionLabel: { fontFamily: fonts.semibold, fontSize: 10.5, letterSpacing: 1, color: colors.straw },
  insight: { flexDirection: 'row', alignItems: 'flex-start', gap: 8 },
  insightText: { flex: 1, fontFamily: fonts.regular, fontSize: 13, lineHeight: 18, color: colors.chaff },
  days: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingTop: space.sm,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: 'rgba(240,236,219,0.18)',
  },
  day: { flex: 1, alignItems: 'center', gap: 2 },
  dayName: { fontFamily: fonts.medium, fontSize: 12, color: colors.straw },
  dayRain: { fontFamily: fonts.semibold, fontSize: 11, lineHeight: 14, color: colors.water },
  dayMax: { fontFamily: fonts.semibold, fontSize: 14, lineHeight: 18, color: colors.chaff },
  dayMin: { fontFamily: fonts.regular, fontSize: 12, lineHeight: 16, color: colors.straw },
  placeholder: { alignItems: 'center', gap: space.sm, paddingVertical: space.lg },
  retry: { paddingHorizontal: space.md, paddingVertical: 6, borderRadius: 14, borderWidth: 1, borderColor: colors.line },
  retryText: { fontFamily: fonts.semibold, fontSize: 13, color: colors.wheat },
});
