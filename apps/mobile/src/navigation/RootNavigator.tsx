import { MaterialCommunityIcons } from '@expo/vector-icons';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { NavigationContainer } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { useEffect } from 'react';
import { Snackbar } from 'react-native-paper';
import { apiGet } from '../api/client';
import { queryClient } from '../api/queryClient';
import type { IconName } from '../components/ui';
import { CatalogDetailScreen } from '../features/catalog/CatalogDetailScreen';
import { CatalogEditorScreen } from '../features/catalog/CatalogEditorScreen';
import { CalendarScreen } from '../features/calendar/CalendarScreen';
import { CatalogScreen } from '../features/catalog/CatalogScreen';
import { usesFirebase } from '../firebase/config';
import { useMachineTracking } from '../features/intelligence/useMachineTracking';
import { ParcelWizardScreen, type ParcelDraft } from '../features/plan/ParcelWizardScreen';
import { EconomicSettingsScreen, FinancialDashboardScreen } from '../features/economics';
import { SatelliteScreen } from '../features/satellite/SatelliteScreen';
import { TillageAnalysisScreen } from '../features/tillage';
import { isExpoGo } from '../lib/runtime';
import { flushOutbox } from '../offline/sync';
import { useRealtime } from '../realtime/useRealtime';
import { AssistantScreen } from '../screens/AssistantScreen';
import { AuthScreen } from '../screens/AuthScreen';
import { DashboardScreen } from '../screens/DashboardScreen';
import { IrrigationScreen } from '../screens/IrrigationScreen';
import { MachinesScreen } from '../screens/MachinesScreen';
import { MapScreen } from '../screens/MapScreen';
import { ParcelDetailScreen } from '../screens/ParcelDetailScreen';
import { ParcelEditorScreen } from '../screens/ParcelEditorScreen';
import { ReportsScreen } from '../screens/ReportsScreen';
import { SettingsScreen } from '../screens/SettingsScreen';
import { TasksScreen } from '../screens/TasksScreen';
import { WorkScreen } from '../screens/WorkScreen';
import { useAuth } from '../store/auth';
import { useToast } from '../store/toast';
import { colors, fonts, navigationTheme } from '../theme';

export type RootStackParams = {
  Tabs: undefined;
  ParcelDetail: { parcelId: string; name: string };
  ParcelEditor: { parcelId?: string } | undefined;
  ParcelWizard: { draft?: ParcelDraft; parcelId?: string };
  Machines: undefined;
  Settings: undefined;
  Catalog: undefined;
  CatalogDetail: { kind: 'seed' | 'tractor'; id: string };
  CatalogEditor: { kind: 'seed' | 'tractor'; id?: string };
  TillageAnalysis: undefined;
  FinancialDashboard: undefined;
  EconomicSettings: undefined;
  SatelliteMonitoring: { parcelId: string; name: string };
};

const OFFLINE_QUERIES = [
  ['dashboard', '/dashboard'],
  ['parcels', '/parcels'],
  ['work', '/work'],
  ['tasks', '/tasks'],
  ['irrigation', '/irrigation'],
  ['machines', '/machines'],
  ['team', '/team'],
] as const;

const Stack = createNativeStackNavigator<RootStackParams>();
const Tab = createBottomTabNavigator();

const TAB_ICONS: Record<string, IconName> = {
  Dashboard: 'view-dashboard-outline',
  Map: 'map-outline',
  Work: 'tractor-variant',
  'To Do': 'checkbox-marked-circle-outline',
  Calendar: 'calendar-month-outline',
  Irrigation: 'water-outline',
  Reports: 'file-chart-outline',
  AI: 'sprout-outline',
};

function Tabs() {
  return (
    <Tab.Navigator
      screenOptions={({ route }) => ({
        headerShown: false,
        tabBarActiveTintColor: colors.wheat,
        tabBarInactiveTintColor: colors.straw,
        tabBarStyle: { backgroundColor: colors.furrow, borderTopColor: colors.line },
        tabBarLabelStyle: { fontFamily: fonts.medium, fontSize: 10 },
        tabBarItemStyle: { paddingHorizontal: 0 },
        tabBarIcon: ({ color, size }) => (
          <MaterialCommunityIcons name={TAB_ICONS[route.name] ?? 'circle-outline'} color={color} size={size - 2} />
        ),
      })}
    >
      <Tab.Screen name="Dashboard" component={DashboardScreen} />
      <Tab.Screen name="Map" component={MapScreen} />
      <Tab.Screen name="Work" component={WorkScreen} />
      <Tab.Screen name="To Do" component={TasksScreen} />
      <Tab.Screen name="Calendar" component={CalendarScreen} />
      <Tab.Screen name="Irrigation" component={IrrigationScreen} />
      <Tab.Screen name="Reports" component={ReportsScreen} />
      <Tab.Screen name="AI" component={AssistantScreen} options={{ tabBarAccessibilityLabel: 'AI Assistant' }} />
    </Tab.Navigator>
  );
}

const TAB_BAR_CLEARANCE = 64;

function LiveToast() {
  const { message, hide } = useToast();
  return (
    <Snackbar visible={message !== null} onDismiss={hide} duration={4000} wrapperStyle={{ bottom: TAB_BAR_CLEARANCE }}>
      {message ?? ''}
    </Snackbar>
  );
}

function SignedIn() {
  useRealtime();
  useMachineTracking();
  useEffect(() => {
    // Core data reaches the phone even if the user does not open every screen.
    for (const [key, path] of OFFLINE_QUERIES) {
      void queryClient.prefetchQuery({ queryKey: [key], queryFn: () => apiGet(path) });
    }
    void flushOutbox();
    if (!isExpoGo) {
      void import('../notifications/push').then((push) => push.registerForPush()).catch(() => undefined);
      void import('../offline/background').then((sync) => sync.registerBackgroundSync()).catch(() => undefined);
    }
  }, []);

  return (
    <>
    <Stack.Navigator
      screenOptions={{
        headerStyle: { backgroundColor: colors.furrow },
        headerTintColor: colors.chaff,
        headerTitleStyle: { fontFamily: fonts.semibold },
        contentStyle: { backgroundColor: colors.loam },
      }}
    >
      <Stack.Screen name="Tabs" component={Tabs} options={{ headerShown: false }} />
      <Stack.Screen name="ParcelDetail" component={ParcelDetailScreen} options={({ route }) => ({ title: route.params.name })} />
      <Stack.Screen name="ParcelEditor" component={ParcelEditorScreen} options={{ title: 'Parcel' }} />
      <Stack.Screen name="ParcelWizard" component={ParcelWizardScreen} options={{ title: 'Parcel setup' }} />
      <Stack.Screen name="Machines" component={MachinesScreen} options={{ title: 'Machines' }} />
      <Stack.Screen name="Settings" component={SettingsScreen} options={{ title: 'Settings' }} />
      <Stack.Screen name="Catalog" component={CatalogScreen} options={{ title: 'Farm catalog' }} />
      <Stack.Screen name="CatalogDetail" component={CatalogDetailScreen} options={{ title: 'Product sheet' }} />
      <Stack.Screen name="CatalogEditor" component={CatalogEditorScreen} options={{ title: 'Edit catalog' }} />
      <Stack.Screen name="TillageAnalysis" component={TillageAnalysisScreen} options={{ title: 'Tillage analysis' }} />
      <Stack.Screen name="FinancialDashboard" component={FinancialDashboardScreen} options={{ title: 'Financial dashboard' }} />
      <Stack.Screen name="SatelliteMonitoring" component={SatelliteScreen} options={({ route }) => ({ title: `Satellite · ${route.params.name}` })} />
      <Stack.Screen name="EconomicSettings" component={EconomicSettingsScreen} options={{ title: 'Costs of diesel, labour and machines' }} />
    </Stack.Navigator>
    <LiveToast />
    </>
  );
}

export function RootNavigator() {
  const status = useAuth((state) => state.status);
  useEffect(() => {
    if (!usesFirebase) return;
    let stop: (() => void) | undefined;
    void import('../firebase/bootstrap')
      .then(({ startFirebase }) => {
        stop = startFirebase();
      })
      .catch((error: unknown) => console.warn('Firebase did not start', error));
    return () => stop?.();
  }, []);
  return (
    <NavigationContainer theme={navigationTheme}>
      {status === 'signedIn' ? <SignedIn /> : <AuthScreen />}
    </NavigationContainer>
  );
}
