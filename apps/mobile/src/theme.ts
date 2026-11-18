import { DarkTheme as NavigationDark, type Theme as NavigationTheme } from '@react-navigation/native';
import { configureFonts, MD3DarkTheme, type MD3Theme } from 'react-native-paper';

/** Paleta pornește de la câmp: pământ afânat, paie, grâu copt, frunză, apă și rugină. */
export const colors = {
  loam: '#181B13',
  furrow: '#22261B',
  ridge: '#2D3224',
  line: '#414835',
  chaff: '#F0ECDB',
  straw: '#ACAE96',
  wheat: '#E2B23D',
  wheatInk: '#2A2106',
  leaf: '#8DBB5E',
  water: '#66ADCB',
  rust: '#E06E48',
  amber: '#E9A23B',
} as const;

export const fonts = {
  regular: 'Barlow_400Regular',
  medium: 'Barlow_500Medium',
  semibold: 'Barlow_600SemiBold',
  bold: 'Barlow_700Bold',
  figure: 'BarlowCondensed_600SemiBold',
} as const;

export const space = { xs: 4, sm: 8, md: 12, lg: 16, xl: 24, xxl: 32 } as const;

export const paperTheme: MD3Theme = {
  ...MD3DarkTheme,
  roundness: 2,
  fonts: configureFonts({ config: { fontFamily: fonts.regular } }),
  colors: {
    ...MD3DarkTheme.colors,
    primary: colors.wheat,
    onPrimary: colors.wheatInk,
    primaryContainer: colors.ridge,
    onPrimaryContainer: colors.chaff,
    secondary: colors.leaf,
    secondaryContainer: colors.ridge,
    onSecondaryContainer: colors.chaff,
    background: colors.loam,
    surface: colors.furrow,
    surfaceVariant: colors.ridge,
    onSurface: colors.chaff,
    onSurfaceVariant: colors.straw,
    outline: colors.line,
    outlineVariant: colors.line,
    error: colors.rust,
    elevation: {
      level0: 'transparent',
      level1: colors.furrow,
      level2: colors.furrow,
      level3: colors.ridge,
      level4: colors.ridge,
      level5: colors.ridge,
    },
  },
};

export const navigationTheme: NavigationTheme = {
  ...NavigationDark,
  colors: {
    ...NavigationDark.colors,
    primary: colors.wheat,
    background: colors.loam,
    card: colors.furrow,
    text: colors.chaff,
    border: colors.line,
    notification: colors.rust,
  },
};

export const healthColor = (healthClass?: string | null) =>
  healthClass === 'GOOD' ? colors.leaf : healthClass === 'MODERATE' ? colors.amber : healthClass === 'PROBLEM' ? colors.rust : colors.straw;
