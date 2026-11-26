import { StyleSheet, View } from 'react-native';
import Svg, { Defs, LinearGradient, Rect, Stop } from 'react-native-svg';
import { Txt } from '../../components/ui';
import { shortDate } from '../../lib/format';
import { colors, fonts, space } from '../../theme';

/** Which layer is on the map and what its colours mean. */
export function SatelliteLegend({ title, scale, date }: { title: string; scale: { low: string; high: string; colors: string[] }; date: string | null }) {
  const id = `legend-${title.replace(/\W/g, '')}`;
  return (
    <View pointerEvents="none" style={styles.box} accessibilityLabel={`${title}: ${scale.low} to ${scale.high}`}>
      <Txt variant="small" color={colors.chaff} style={styles.title}>{`${title}${date ? ` · ${shortDate(date)}` : ''}`}</Txt>
      <Svg width={180} height={8}>
        <Defs>
          <LinearGradient id={id} x1="0" y1="0" x2="1" y2="0">
            {scale.colors.map((color, index) => <Stop key={color} offset={index / (scale.colors.length - 1)} stopColor={color} />)}
          </LinearGradient>
        </Defs>
        <Rect width={180} height={8} rx={4} fill={`url(#${id})`} />
      </Svg>
      <View style={styles.labels}>
        <Txt variant="small" style={styles.label}>{scale.low}</Txt>
        <Txt variant="small" style={styles.label}>{scale.high}</Txt>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  box: { position: 'absolute', left: space.md, bottom: 72, width: 204, padding: space.sm, gap: 4, borderRadius: 10, backgroundColor: 'rgba(24,27,19,0.82)', borderWidth: 1, borderColor: 'rgba(240,236,219,0.14)' },
  title: { fontFamily: fonts.semibold },
  labels: { flexDirection: 'row', justifyContent: 'space-between' },
  label: { fontSize: 11, lineHeight: 14 },
});
