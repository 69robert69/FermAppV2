import type { ExpoConfig } from 'expo/config';

const googleMapsKey = process.env.GOOGLE_MAPS_API_KEY ?? '';

const config: ExpoConfig = {
  name: 'FermApp',
  slug: 'fermapp',
  version: '1.0.0',
  orientation: 'portrait',
  userInterfaceStyle: 'dark',
  backgroundColor: '#181B13',
  scheme: 'fermapp',
  ios: {
    bundleIdentifier: 'ro.fermapp.mobile',
    supportsTablet: true,
    config: { googleMapsApiKey: googleMapsKey },
    infoPlist: {
      NSLocationWhenInUseUsageDescription: 'Location helps you find and draw parcels on the map.',
      UIBackgroundModes: ['fetch', 'remote-notification'],
    },
  },
  android: {
    package: 'ro.fermapp.mobile',
    config: { googleMaps: { apiKey: googleMapsKey } },
    permissions: ['ACCESS_COARSE_LOCATION', 'ACCESS_FINE_LOCATION', 'POST_NOTIFICATIONS'],
  },
  plugins: [
    'expo-font',
    'expo-secure-store',
    'expo-sqlite',
    'expo-background-task',
    ['expo-image-picker', { cameraPermission: 'The camera is used for field photos.', photosPermission: 'The gallery is used to attach photos to parcels.' }],
    ['expo-splash-screen', { backgroundColor: '#181B13' }],
    ['expo-notifications', { color: '#E2B23D' }],
    ['expo-location', { locationWhenInUsePermission: 'Location helps you find and draw parcels on the map.' }],
  ],
  extra: {
    eas: { projectId: process.env.EAS_PROJECT_ID },
  },
};

export default config;
