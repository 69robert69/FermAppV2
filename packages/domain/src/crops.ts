export const CROP_CODES = [
  'WHEAT',
  'BARLEY',
  'CORN',
  'SUNFLOWER',
  'RAPESEED',
  'SOY',
  'ALFALFA',
  'OAT',
  'POTATO',
  'BEET',
  'VEGETABLES',
] as const;

export type CropCode = (typeof CROP_CODES)[number];

export type CropFamily =
  | 'CEREAL'
  | 'LEGUME'
  | 'ASTERACEAE'
  | 'BRASSICA'
  | 'SOLANACEAE'
  | 'CHENOPOD'
  | 'VEGETABLE';

export type SoilTexture = 'SANDY' | 'LOAMY' | 'CLAY_LOAM' | 'CLAY';

export interface CropProfile {
  code: CropCode;
  name: string;
  family: CropFamily;
  /** Ani întregi fără cultură, între două culturi identice pe aceeași parcelă. */
  minBreakYears: number;
  /** Necesar de apă pe sezon, în mm. */
  waterMm: readonly [min: number, max: number];
  phRange: readonly [min: number, max: number];
  /** Temperatura medie optimă a sezonului de vegetație, în °C. */
  seasonTempC: readonly [min: number, max: number];
  gddBaseC: number;
  gddCapC: number;
  gddToMaturity: number;
  textures: readonly SoilTexture[];
  /** Producție orientativă, în t/ha. */
  referenceYieldTHa: number;
}

export const CROPS: Record<CropCode, CropProfile> = {
  WHEAT: {
    code: 'WHEAT',
    name: 'Grâu',
    family: 'CEREAL',
    minBreakYears: 1,
    waterMm: [350, 600],
    phRange: [6, 7.5],
    seasonTempC: [8, 18],
    gddBaseC: 0,
    gddCapC: 30,
    gddToMaturity: 2100,
    textures: ['LOAMY', 'CLAY_LOAM', 'CLAY'],
    referenceYieldTHa: 6,
  },
  BARLEY: {
    code: 'BARLEY',
    name: 'Orz',
    family: 'CEREAL',
    minBreakYears: 1,
    waterMm: [300, 500],
    phRange: [6, 8],
    seasonTempC: [7, 17],
    gddBaseC: 0,
    gddCapC: 30,
    gddToMaturity: 1800,
    textures: ['LOAMY', 'CLAY_LOAM'],
    referenceYieldTHa: 5.5,
  },
  CORN: {
    code: 'CORN',
    name: 'Porumb',
    family: 'CEREAL',
    minBreakYears: 1,
    waterMm: [450, 800],
    phRange: [5.8, 7.5],
    seasonTempC: [18, 26],
    gddBaseC: 10,
    gddCapC: 30,
    gddToMaturity: 1450,
    textures: ['LOAMY', 'CLAY_LOAM'],
    referenceYieldTHa: 8,
  },
  SUNFLOWER: {
    code: 'SUNFLOWER',
    name: 'Floarea-soarelui',
    family: 'ASTERACEAE',
    minBreakYears: 4,
    waterMm: [400, 700],
    phRange: [6, 7.8],
    seasonTempC: [17, 26],
    gddBaseC: 6,
    gddCapC: 30,
    gddToMaturity: 1600,
    textures: ['LOAMY', 'CLAY_LOAM', 'CLAY'],
    referenceYieldTHa: 2.8,
  },
  RAPESEED: {
    code: 'RAPESEED',
    name: 'Rapiță',
    family: 'BRASSICA',
    minBreakYears: 3,
    waterMm: [400, 600],
    phRange: [6, 7.5],
    seasonTempC: [7, 17],
    gddBaseC: 5,
    gddCapC: 30,
    gddToMaturity: 1500,
    textures: ['LOAMY', 'CLAY_LOAM'],
    referenceYieldTHa: 3.2,
  },
  SOY: {
    code: 'SOY',
    name: 'Soia',
    family: 'LEGUME',
    minBreakYears: 2,
    waterMm: [450, 700],
    phRange: [6, 7.2],
    seasonTempC: [18, 26],
    gddBaseC: 10,
    gddCapC: 30,
    gddToMaturity: 1300,
    textures: ['LOAMY', 'CLAY_LOAM'],
    referenceYieldTHa: 2.8,
  },
  ALFALFA: {
    code: 'ALFALFA',
    name: 'Lucernă',
    family: 'LEGUME',
    minBreakYears: 3,
    waterMm: [600, 1000],
    phRange: [6.5, 7.8],
    seasonTempC: [14, 25],
    gddBaseC: 5,
    gddCapC: 30,
    gddToMaturity: 1200,
    textures: ['LOAMY', 'CLAY_LOAM'],
    referenceYieldTHa: 9,
  },
  OAT: {
    code: 'OAT',
    name: 'Ovăz',
    family: 'CEREAL',
    minBreakYears: 2,
    waterMm: [350, 550],
    phRange: [5.2, 7],
    seasonTempC: [8, 17],
    gddBaseC: 0,
    gddCapC: 30,
    gddToMaturity: 1700,
    textures: ['SANDY', 'LOAMY', 'CLAY_LOAM'],
    referenceYieldTHa: 3.5,
  },
  POTATO: {
    code: 'POTATO',
    name: 'Cartof',
    family: 'SOLANACEAE',
    minBreakYears: 3,
    waterMm: [500, 700],
    phRange: [5, 6.5],
    seasonTempC: [14, 20],
    gddBaseC: 7,
    gddCapC: 30,
    gddToMaturity: 1300,
    textures: ['SANDY', 'LOAMY'],
    referenceYieldTHa: 28,
  },
  BEET: {
    code: 'BEET',
    name: 'Sfeclă',
    family: 'CHENOPOD',
    minBreakYears: 3,
    waterMm: [550, 750],
    phRange: [6.5, 8],
    seasonTempC: [14, 22],
    gddBaseC: 3,
    gddCapC: 30,
    gddToMaturity: 2400,
    textures: ['LOAMY', 'CLAY_LOAM'],
    referenceYieldTHa: 60,
  },
  VEGETABLES: {
    code: 'VEGETABLES',
    name: 'Legume',
    family: 'VEGETABLE',
    minBreakYears: 2,
    waterMm: [400, 800],
    phRange: [6, 7.2],
    seasonTempC: [15, 25],
    gddBaseC: 8,
    gddCapC: 30,
    gddToMaturity: 1100,
    textures: ['SANDY', 'LOAMY', 'CLAY_LOAM'],
    referenceYieldTHa: 30,
  },
};

export const cropName = (code: CropCode): string => CROPS[code].name;
