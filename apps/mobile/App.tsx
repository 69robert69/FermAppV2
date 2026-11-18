import { Barlow_400Regular, Barlow_500Medium, Barlow_600SemiBold, Barlow_700Bold } from '@expo-google-fonts/barlow';
import { BarlowCondensed_600SemiBold } from '@expo-google-fonts/barlow-condensed';
import { BottomSheetModalProvider } from '@gorhom/bottom-sheet';
import { createAsyncStoragePersister } from '@tanstack/query-async-storage-persister';
import { PersistQueryClientProvider } from '@tanstack/react-query-persist-client';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { useFonts } from 'expo-font';
import * as SplashScreen from 'expo-splash-screen';
import { StatusBar } from 'expo-status-bar';
import { useEffect } from 'react';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import { PaperProvider } from 'react-native-paper';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { queryClient } from './src/api/queryClient';
import { RootNavigator } from './src/navigation/RootNavigator';
import { startSyncListener } from './src/offline/sync';
import { useAuth } from './src/store/auth';
import { paperTheme } from './src/theme';

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

  useEffect(() => {
    void useAuth.getState().restore();
    return startSyncListener();
  }, []);

  useEffect(() => {
    if (fontsLoaded && status !== 'loading') void SplashScreen.hideAsync();
  }, [fontsLoaded, status]);

  if (!fontsLoaded || status === 'loading') return null;

  return (
    <GestureHandlerRootView style={{ flex: 1 }}>
      <SafeAreaProvider>
        <PersistQueryClientProvider client={queryClient} persistOptions={{ persister, maxAge: ONE_WEEK }}>
          <PaperProvider theme={paperTheme}>
            <BottomSheetModalProvider>
              <StatusBar style="light" />
              <RootNavigator />
            </BottomSheetModalProvider>
          </PaperProvider>
        </PersistQueryClientProvider>
      </SafeAreaProvider>
    </GestureHandlerRootView>
  );
}
