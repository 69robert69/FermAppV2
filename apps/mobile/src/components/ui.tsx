import { MaterialCommunityIcons } from '@expo/vector-icons';
import type { UseQueryResult } from '@tanstack/react-query';
import { useEffect, type ComponentProps, type ReactNode } from 'react';
import {
  ActivityIndicator,
  Pressable,
  RefreshControl,
  ScrollView,
  StyleSheet,
  Text,
  View,
  type StyleProp,
  type TextStyle,
  type ViewStyle,
} from 'react-native';
import { Button, TextInput } from 'react-native-paper';
import Animated, { useAnimatedStyle, useSharedValue, withTiming } from 'react-native-reanimated';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { errorMessage } from '../lib/hooks';
import { useSync } from '../offline/sync';
import { colors, fonts, space } from '../theme';

export type IconName = ComponentProps<typeof MaterialCommunityIcons>['name'];

export function Txt({
  children,
  variant = 'body',
  color,
  style,
  numberOfLines,
}: {
  children: ReactNode;
  variant?: keyof typeof textStyles;
  color?: string;
  style?: StyleProp<TextStyle>;
  numberOfLines?: number;
}) {
  return (
    <Text numberOfLines={numberOfLines} style={[textStyles[variant], color ? { color } : null, style]}>
      {children}
    </Text>
  );
}

const textStyles = StyleSheet.create({
  display: { fontFamily: fonts.bold, fontSize: 28, lineHeight: 34, color: colors.chaff },
  title: { fontFamily: fonts.semibold, fontSize: 19, lineHeight: 25, color: colors.chaff },
  subtitle: { fontFamily: fonts.semibold, fontSize: 16, lineHeight: 22, color: colors.chaff },
  body: { fontFamily: fonts.regular, fontSize: 15, lineHeight: 22, color: colors.chaff },
  small: { fontFamily: fonts.regular, fontSize: 13, lineHeight: 18, color: colors.straw },
  figure: { fontFamily: fonts.figure, fontSize: 30, lineHeight: 34, color: colors.chaff },
});

export function OfflineBanner() {
  const { online, pending, failed, syncing } = useSync();
  if (online && pending === 0 && failed === 0) return null;
  const message = !online
    ? `Working offline${pending ? `. ${pending} changes are waiting for a connection` : '. The data shown is the last saved version'}.`
    : syncing
      ? `Sending ${pending} changes.`
      : failed
        ? `${failed} changes were rejected by the server. See the details in Settings.`
        : `${pending} changes are waiting to be sent.`;
  return (
    <View style={styles.banner} accessibilityLiveRegion="polite">
      <MaterialCommunityIcons name={online ? 'cloud-sync-outline' : 'cloud-off-outline'} size={16} color={colors.wheatInk} />
      <Text style={styles.bannerText}>{message}</Text>
    </View>
  );
}

export function Screen({
  title,
  action,
  children,
  onRefresh,
  refreshing = false,
  scroll = true,
}: {
  title?: string;
  action?: ReactNode;
  children: ReactNode;
  onRefresh?: () => void;
  refreshing?: boolean;
  scroll?: boolean;
}) {
  const insets = useSafeAreaInsets();
  const header = title ? (
    <View style={styles.header}>
      <Txt variant="display" style={{ flex: 1 }} numberOfLines={1}>
        {title}
      </Txt>
      {action}
    </View>
  ) : null;
  return (
    <View style={[styles.screen, { paddingTop: title ? insets.top : 0 }]}>
      <OfflineBanner />
      {scroll ? (
        <ScrollView
          contentContainerStyle={styles.content}
          keyboardShouldPersistTaps="handled"
          refreshControl={
            onRefresh ? <RefreshControl refreshing={refreshing} onRefresh={onRefresh} tintColor={colors.wheat} /> : undefined
          }
        >
          {header}
          {children}
        </ScrollView>
      ) : (
        <View style={{ flex: 1 }}>
          {header}
          {children}
        </View>
      )}
    </View>
  );
}

export function Panel({ children, style }: { children: ReactNode; style?: StyleProp<ViewStyle> }) {
  return <View style={[styles.panel, style]}>{children}</View>;
}

export function Section({ title, action, children }: { title: string; action?: ReactNode; children: ReactNode }) {
  return (
    <View style={styles.section}>
      <View style={styles.sectionHead}>
        <Txt variant="title" style={{ flex: 1 }}>
          {title}
        </Txt>
        {action}
      </View>
      {children}
    </View>
  );
}

export function Stat({ value, unit, label, color }: { value: string; unit?: string; label: string; color?: string }) {
  return (
    <View style={styles.stat}>
      <Text style={[textStyles.figure, color ? { color } : null]}>
        {value}
        {unit ? <Text style={styles.unit}> {unit}</Text> : null}
      </Text>
      <Txt variant="small">{label}</Txt>
    </View>
  );
}

export function Row({ label, value }: { label: string; value: ReactNode }) {
  return (
    <View style={styles.row}>
      <Txt variant="small" style={{ flex: 1 }}>
        {label}
      </Txt>
      <Txt style={{ textAlign: 'right', flexShrink: 1 }}>{value}</Txt>
    </View>
  );
}

export function ProgressTrack({ percent, color = colors.wheat }: { percent: number; color?: string }) {
  const width = useSharedValue(percent);
  useEffect(() => {
    width.value = withTiming(percent, { duration: 600 });
  }, [percent, width]);
  const fill = useAnimatedStyle(() => ({ width: `${Math.min(100, width.value)}%` }));
  return (
    <View
      style={styles.track}
      accessibilityRole="progressbar"
      accessibilityValue={{ min: 0, max: 100, now: Math.round(percent) }}
    >
      <Animated.View style={[styles.fill, { backgroundColor: color }, fill]} />
    </View>
  );
}

export function Choice<T extends string>({
  options,
  value,
  onChange,
  label,
}: {
  options: { value: T; label: string }[];
  value: T | null | undefined;
  onChange: (value: T) => void;
  label?: string;
}) {
  return (
    <View style={{ gap: space.xs }}>
      {label ? <Txt variant="small">{label}</Txt> : null}
      <ScrollView horizontal showsHorizontalScrollIndicator={false} keyboardShouldPersistTaps="handled" contentContainerStyle={styles.choices}>
        {options.map((option) => {
          const selected = option.value === value;
          return (
            <Pressable
              key={option.value}
              onPress={() => onChange(option.value)}
              accessibilityRole="radio"
              accessibilityState={{ selected }}
              style={[styles.choice, selected && styles.choiceOn]}
            >
              <Text style={[styles.choiceText, selected && styles.choiceTextOn]}>{option.label}</Text>
            </Pressable>
          );
        })}
      </ScrollView>
    </View>
  );
}

export function Field(props: ComponentProps<typeof TextInput>) {
  return <TextInput mode="outlined" dense {...props} style={[{ backgroundColor: colors.furrow }, props.style]} />;
}

export function Empty({ icon, title, hint, action }: { icon: IconName; title: string; hint?: string; action?: ReactNode }) {
  return (
    <View style={styles.empty}>
      <MaterialCommunityIcons name={icon} size={36} color={colors.straw} />
      <Txt variant="subtitle" style={{ textAlign: 'center' }}>
        {title}
      </Txt>
      {hint ? (
        <Txt variant="small" style={{ textAlign: 'center' }}>
          {hint}
        </Txt>
      ) : null}
      {action}
    </View>
  );
}

/** Loading and error states for any query. */
export function QueryState<T>({ query, children }: { query: UseQueryResult<T>; children: (data: T) => ReactNode }) {
  if (query.data !== undefined) return <>{children(query.data)}</>;
  if (query.isError) {
    return (
      <Empty
        icon="alert-circle-outline"
        title="The data could not be loaded"
        hint={errorMessage(query.error)}
        action={<Button onPress={() => void query.refetch()}>Try again</Button>}
      />
    );
  }
  return (
    <View style={styles.loading}>
      <ActivityIndicator color={colors.wheat} accessibilityLabel="Loading" />
    </View>
  );
}

export function IconButton({
  icon,
  label,
  onPress,
  color = colors.chaff,
}: {
  icon: IconName;
  label: string;
  onPress: () => void;
  color?: string;
}) {
  return (
    <Pressable onPress={onPress} hitSlop={8} accessibilityRole="button" accessibilityLabel={label} style={styles.iconButton}>
      <MaterialCommunityIcons name={icon} size={24} color={color} />
    </Pressable>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: colors.loam },
  content: { padding: space.lg, paddingBottom: space.xxl, gap: space.lg },
  header: { flexDirection: 'row', alignItems: 'center', gap: space.sm, paddingTop: space.sm },
  banner: { flexDirection: 'row', alignItems: 'center', gap: space.sm, backgroundColor: colors.wheat, paddingHorizontal: space.lg, paddingVertical: 6 },
  bannerText: { fontFamily: fonts.medium, fontSize: 13, color: colors.wheatInk, flex: 1 },
  panel: { backgroundColor: colors.furrow, borderRadius: 6, padding: space.lg, gap: space.md },
  section: { gap: space.md },
  sectionHead: { flexDirection: 'row', alignItems: 'center', gap: space.sm },
  stat: { flex: 1, minWidth: 130, gap: 2 },
  unit: { fontFamily: fonts.medium, fontSize: 15, color: colors.straw },
  row: { flexDirection: 'row', alignItems: 'baseline', gap: space.md, paddingVertical: 3 },
  track: { height: 8, borderRadius: 4, backgroundColor: colors.ridge, overflow: 'hidden' },
  fill: { height: '100%', borderRadius: 4 },
  choices: { gap: space.sm, paddingRight: space.lg },
  choice: { minHeight: 40, justifyContent: 'center', paddingHorizontal: 14, borderRadius: 20, borderWidth: 1, borderColor: colors.line },
  choiceOn: { backgroundColor: colors.wheat, borderColor: colors.wheat },
  choiceText: { fontFamily: fonts.medium, fontSize: 14, color: colors.chaff },
  choiceTextOn: { color: colors.wheatInk },
  empty: { alignItems: 'center', gap: space.sm, paddingVertical: space.xxl, paddingHorizontal: space.lg },
  loading: { paddingVertical: space.xxl, alignItems: 'center' },
  iconButton: { width: 44, height: 44, alignItems: 'center', justifyContent: 'center' },
});
