import type { PolygonGeometry } from '@fermapp/domain';
import { memo, useMemo } from 'react';
import Svg, { Path } from 'react-native-svg';
import { colors } from '../theme';

interface Props {
  geometry: PolygonGeometry;
  size?: number;
  color?: string;
  filled?: boolean;
}

/** Conturul real al parcelei, folosit ca semn de recunoaștere în toată aplicația. */
export const ParcelShape = memo(function ParcelShape({ geometry, size = 44, color = colors.wheat, filled = true }: Props) {
  const path = useMemo(() => {
    const ring = geometry.coordinates[0] ?? [];
    if (ring.length === 0) return '';
    const lats = ring.map((point) => point[1]);
    const lngs = ring.map((point) => point[0]);
    const minLat = Math.min(...lats);
    const maxLat = Math.max(...lats);
    const minLng = Math.min(...lngs);
    const maxLng = Math.max(...lngs);
    const stretch = Math.cos((((minLat + maxLat) / 2) * Math.PI) / 180);
    const width = (maxLng - minLng) * stretch || 1e-9;
    const height = maxLat - minLat || 1e-9;
    const scale = 88 / Math.max(width, height);
    const offsetX = (100 - width * scale) / 2;
    const offsetY = (100 - height * scale) / 2;
    return (
      ring
        .map(([lng, lat], index) => {
          const x = offsetX + (lng - minLng) * stretch * scale;
          const y = offsetY + (maxLat - lat) * scale;
          return `${index === 0 ? 'M' : 'L'}${x.toFixed(1)} ${y.toFixed(1)}`;
        })
        .join(' ') + ' Z'
    );
  }, [geometry]);

  return (
    <Svg width={size} height={size} viewBox="0 0 100 100" accessibilityElementsHidden importantForAccessibility="no">
      <Path d={path} fill={filled ? color : 'none'} fillOpacity={0.22} stroke={color} strokeWidth={5} strokeLinejoin="round" />
    </Svg>
  );
});
