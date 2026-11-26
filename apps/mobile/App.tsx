import { Barlow_400Regular, Barlow_500Medium, Barlow_600SemiBold, Barlow_700Bold } from '@expo-google-fonts/barlow';
import { BarlowCondensed_600SemiBold } from '@expo-google-fonts/barlow-condensed';
import { BottomSheetModalProvider } from '@gorhom/bottom-sheet';
import { createAsyncStoragePersister } from '@tanstack/query-async-storage-persister';
import { PersistQueryClientProvider } from '@tanstack/react-query-persist-client';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { useFonts } from 'expo-font';
import * as SplashScreen from 'expo-splash-screen';
import { StatusBar } from 'expo-status-bar';
import { useCallback, useEffect, useState } from 'react';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { PaperProvider } from 'react-native-paper';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { queryClient } from './src/api/queryClient';
import { AppLoader } from './src/features/loading';
import { RootNavigator } from './src/navigation/RootNavigator';
import { startSyncListener } from './src/offline/sync';
import { useAuth } from './src/store/auth';
import { colors, paperTheme } from './src/theme';

void SplashScreen.preventAutoHideAsync();

const persister = createAsyncStoragePersister({ storage: AsyncStorage, key: 'fermapp-query-cache' });
const ONE_WEEK = 7 * 24 * 3_600_000;

export default function App() {
  const [fontsLoaded] = useFonts({
    Barlow_400Regular,
    Barlow_500Medium,
    Barlow_600SemiBold,
    Barlow_700Bold,
    BarlowCondensed_600SemiBold,
  });
  const status = useAuth((state) => state.status);
  const sessionReady = status !== 'loading';
  const [cacheRestored, setCacheRestored] = useState(false);
  const [loaderDone, setLoaderDone] = useState(false);
  const markCacheRestored = useCallback(() => setCacheRestored(true), []);
  const finishLoader = useCallback(() => setLoaderDone(true), []);

  useEffect(() => {
    void useAuth.getState().restore();
    return startSyncListener();
  }, []);

  // The native splash only covers font loading; the themed loader takes over from there.
  useEffect(() => {
    if (fontsLoaded) void SplashScreen.hideAsync();
  }, [fontsLoaded]);

  if (!fontsLoaded) return null;

  // Loading steps: fonts, saved session, offline data cache.
  const loadProgress = (1 + Number(sessionReady) + Number(cacheRestored)) / 3;

  return (
    <GestureHandlerRootView style={{ flex: 1, backgroundColor: colors.loam }}>
      <SafeAreaProvider>
        {sessionReady ? (
          <PersistQueryClientProvider
            client={queryClient}
            persistOptions={{ persister, maxAge: ONE_WEEK }}
            onSuccess={markCacheRestored}
            onError={markCacheRestored}
          >
            <PaperProvider theme={paperTheme}>
              <BottomSheetModalProvider>
                <StatusBar style="light" />
                <RootNavigator />
              </BottomSheetModalProvider>
            </PaperProvider>
          </PersistQueryClientProvider>
        ) : null}
        {loaderDone ? null : <AppLoader progress={loadProgress} onFinish={finishLoader} />}
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}
