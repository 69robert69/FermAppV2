import { memo } from 'react';
import Svg, { Circle, G, Rect } from 'react-native-svg';
import { colors } from '../../theme';

/** Implement colour per operation, so the machine tells what it is doing. */
export const IMPLEMENT_COLOR: Record<string, string> = {
  PLOWING: '#8A5A34',
  DISCING: '#A0703F',
  SUBSOILING: '#7A5234',
  SOWING: '#58A23A',
  FERTILIZING: '#9BC46A',
  SPRAYING: '#4FB39A',
  HARVESTING: '#E2B23D',
};

/**
 * A tractor seen from above, pointing up. The marker rotates it with the
 * heading, so it always faces the way it drives, implement behind.
 */
export const TractorGlyph = memo(function TractorGlyph({ type, paused, size = 40 }: { type: string; paused?: boolean; size?: number }) {
  const body = paused ? colors.straw : colors.wheat;
  const implement = IMPLEMENT_COLOR[type] ?? colors.leaf;
  const combine = type === 'HARVESTING';
  return (
    <Svg width={size} height={size} viewBox="0 0 40 40">
      {/* Soft halo so the machine stands out on satellite imagery. */}
      <Circle cx={20} cy={20} r={19} fill={colors.loam} fillOpacity={0.35} />
      <G>
        {combine ? (
          // Header in front of a combine.
          <Rect x={5} y={4} width={30} height={5} rx={1.5} fill={implement} stroke={colors.loam} strokeWidth={1} />
        ) : (
          // Implement trailing behind a tractor.
          <Rect x={6} y={31} width={28} height={4} rx={1.5} fill={implement} stroke={colors.loam} strokeWidth={1} />
        )}
        {/* Wheels: small at the front, large at the back. */}
        <Rect x={11} y={10} width={4} height={6} rx={1.5} fill={colors.loam} />
        <Rect x={25} y={10} width={4} height={6} rx={1.5} fill={colors.loam} />
        <Rect x={9} y={20} width={5} height={9} rx={2} fill={colors.loam} />
        <Rect x={26} y={20} width={5} height={9} rx={2} fill={colors.loam} />
        {/* Bonnet and cab. */}
        <Rect x={15} y={8} width={10} height={12} rx={2.5} fill={body} stroke={colors.loam} strokeWidth={1} />
        <Rect x={13.5} y={18} width={13} height={11} rx={2.5} fill={body} stroke={colors.loam} strokeWidth={1} />
        <Rect x={15.5} y={19.5} width={9} height={4} rx={1} fill={colors.loam} fillOpacity={0.55} />
      </G>
    </Svg>
  );
});
