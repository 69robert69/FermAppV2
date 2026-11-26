import { useNavigation, type NavigationProp } from '@react-navigation/native';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Alert, Pressable, View } from 'react-native';
import { Button, Switch } from 'react-native-paper';
import { apiGet, apiSend, isQueued } from '../api/client';
import { queryClient } from '../api/queryClient';
import type { Role, TeamMember } from '../api/types';
import { Choice, Field, Panel, QueryState, Row, Screen, Section, Txt } from '../components/ui';
import { undo } from '../features/copilot/controller';
import { AUTOMATION_OPTIONS, useCopilot } from '../features/copilot/store';
import { useSatellitePrefs } from '../features/satellite/prefs';
import { REFRESH_OPTIONS, useWeatherPrefs } from '../features/weather';
import { usesFirebase } from '../firebase/config';
import { clock, ROLE_LABELS, shortDate } from '../lib/format';
import { errorMessage, useAction } from '../lib/hooks';
import { failedOperations, removeOperation } from '../offline/db';
import { flushOutbox, refreshOutboxCounts, useSync } from '../offline/sync';
import type { RootStackParams } from '../navigation/RootNavigator';
import { canPlan, useAuth } from '../store/auth';
import { colors, space } from '../theme';

const SATELLITE_SWITCHES = [
  { key: 'enabled', label: 'Enable Satellite Monitoring', hint: 'Crop health, NDVI and problem zones for each field. Off: no satellite data is loaded.' },
  { key: 'mapLayers', label: 'Enable NDVI Layers', hint: 'NDVI, vegetation health, irrigation risk and crop stress overlays on the map.' },
  { key: 'aiAnalysis', label: 'Enable AI Analysis', hint: 'Explains what the images show and lets the Copilot use them.' },
  { key: 'autoAlerts', label: 'Enable Automatic Field Alerts', hint: 'Notifies managers and agronomists of stress, decline or weak zones.' },
  { key: 'autoRefresh', label: 'Enable Satellite Auto Refresh', hint: 'Analyses new images in the background, at most twice a day per field.' },
] as const;

const ACTION_LABELS = { CREATE: 'Created', CANCEL: 'Cancelled', PRIORITY: 'Reprioritized', RESCHEDULE: 'Rescheduled' } as const;

const roleOptions =(Object.keys(ROLE_LABELS) as Role[]).map((value) => ({ value, label: ROLE_LABELS[value]! }));

export function SettingsScreen() {
  const navigation = useNavigation<NavigationProp<RootStackParams>>();
  const auth = useAuth();
  const sync = useSync();
  const weather = useWeatherPrefs();
  const copilot = useCopilot();
  const satellite = useSatellitePrefs();
  const [showLog, setShowLog] = useState(false);
  const team = useQuery({ queryKey: ['team'], queryFn: () => apiGet<TeamMember[]>('/team') });
  const failed = useQuery({ queryKey: ['outbox-failed', sync.failed], queryFn: failedOperations });
  const [email, setEmail] = useState('');
  const [fullName, setFullName] = useState('');
  const [password, setPassword] = useState('');
  const [role, setRole] = useState<Role>('OPERATOR');

  const invite = useAction<void>(
    async () => {
      await apiSend('POST', '/team', { email: email.trim(), fullName: fullName.trim(), role, password: password || undefined }, { offline: false });
    },
    [['team']],
    () => {
      setEmail('');
      setFullName('');
      setPassword('');
    },
  );

  async function switchFarm(farmId: string) {
    if (sync.pending > 0) {
      Alert.alert('Sync first', 'The unsent changes belong to the current farm.');
      return;
    }
    try {
      const result = await apiSend<{ farmId: string; role: Role; accessToken: string; refreshToken: string }>(
        'POST',
        '/auth/switch-farm',
        { farmId },
        { offline: false },
      );
      if (isQueued(result)) return;
      const farm = auth.farms?.find((item) => item.id === farmId);
      if (!farm || !auth.user) return;
      await auth.setSession({
        user: auth.user,
        farm,
        role: result.role,
        farms: auth.farms,
        accessToken: result.accessToken,
        refreshToken: result.refreshToken,
      });
      queryClient.clear();
    } catch (error) {
      Alert.alert('The farm could not be switched', errorMessage(error));
    }
  }

  async function signOut() {
    const refreshToken = auth.refreshToken;
    if (refreshToken) await apiSend('POST', '/auth/logout', { refreshToken }, { offline: false }).catch(() => undefined);
    await auth.signOut();
  }

  const confirmSignOut = () => {
    const warning = sync.pending ? ` ${sync.pending} unsent changes will be lost.` : '';
    Alert.alert('Sign out', `The data saved on this phone will be deleted.${warning}`, [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Sign out', style: 'destructive', onPress: () => void signOut() },
    ]);
  };

  return (
    <Screen>
      <Panel>
        <Txt variant="title">{auth.user?.fullName}</Txt>
        <Txt variant="small">{auth.user?.email}</Txt>
        <Row label="Farm" value={auth.farm?.name} />
        <Row label="Role" value={auth.role ? ROLE_LABELS[auth.role] : ''} />
      </Panel>

      {usesFirebase ? (
        <Button mode="outlined" icon="book-open-variant" onPress={() => navigation.navigate('Catalog')}>Farm catalog</Button>
      ) : null}

      {auth.farms && auth.farms.length > 1 ? (
        <Section title="Switch farm">
          <Choice options={auth.farms.map((farm) => ({ value: farm.id, label: farm.name }))} value={auth.farm?.id} onChange={(id) => void switchFarm(id)} />
        </Section>
      ) : null}

      <Section title="Map">
        <Panel>
          <Pressable
            onPress={() => weather.setEnabled(!weather.enabled)}
            accessibilityRole="switch"
            accessibilityState={{ checked: weather.enabled }}
            style={{ flexDirection: 'row', alignItems: 'center', gap: space.md }}
          >
            <View style={{ flex: 1, gap: 2 }}>
              <Txt variant="subtitle">Show Weather Widget</Txt>
              <Txt variant="small">
                {weather.enabled
                  ? 'Current weather, a 5-day forecast, alerts and field advice on the map.'
                  : 'Hidden. No weather data is loaded for the map.'}
              </Txt>
            </View>
            <Switch value={weather.enabled} onValueChange={weather.setEnabled} />
          </Pressable>
          {weather.enabled ? (
            <Choice
              label="Refresh every"
              options={REFRESH_OPTIONS}
              value={String(weather.refreshMinutes)}
              onChange={(value) => weather.setRefreshMinutes(Number(value))}
            />
          ) : null}
        </Panel>
      </Section>

      {usesFirebase ? (
        <Section title="Satellite Monitoring">
          <Panel>
            {SATELLITE_SWITCHES.map((item) => {
              const value = satellite[item.key];
              const disabled = item.key !== 'enabled' && !satellite.enabled;
              return (
                <Pressable
                  key={item.key}
                  disabled={disabled}
                  onPress={() => satellite.set({ [item.key]: !value })}
                  accessibilityRole="switch"
                  accessibilityState={{ checked: value, disabled }}
                  style={{ flexDirection: 'row', alignItems: 'center', gap: space.md, opacity: disabled ? 0.45 : 1 }}
                >
                  <View style={{ flex: 1, gap: 2 }}>
                    <Txt variant="subtitle">{item.label}</Txt>
                    <Txt variant="small">{item.hint}</Txt>
                  </View>
                  <Switch value={value} disabled={disabled} onValueChange={(next) => satellite.set({ [item.key]: next })} />
                </Pressable>
              );
            })}
            <Txt variant="small">Free Copernicus Sentinel-2 images via Microsoft Planetary Computer; no account needed. Images are cached on the phone.</Txt>
          </Panel>
        </Section>
      ) : null}

      {usesFirebase ? (
        <Section title="AI Task Automation">
          <Panel>
            {canPlan(auth.role) ? (
              AUTOMATION_OPTIONS.map((option) => {
                const selected = copilot.mode === option.value;
                return (
                  <Pressable
                    key={option.value}
                    accessibilityRole="radio"
                    accessibilityState={{ selected }}
                    onPress={() => copilot.setMode(option.value)}
                    style={{ flexDirection: 'row', gap: space.md, alignItems: 'flex-start', paddingVertical: space.xs }}
                  >
                    <Txt color={selected ? colors.wheat : colors.straw}>{selected ? '◉' : '○'}</Txt>
                    <View style={{ flex: 1, gap: 2 }}>
                      <Txt variant="subtitle" color={selected ? colors.wheat : colors.chaff}>{option.label}</Txt>
                      <Txt variant="small">{option.hint}</Txt>
                    </View>
                  </Pressable>
                );
              })
            ) : (
              <Txt variant="small">Operators receive recommendations only: managers and agronomists approve AI tasks.</Txt>
            )}
            <Button mode="outlined" icon="history" onPress={() => setShowLog((value) => !value)}>
              {showLog ? 'Hide AI activity' : `AI activity (${copilot.actions.length})`}
            </Button>
            {showLog
              ? copilot.actions.length === 0
                ? <Txt variant="small">The copilot has not created or changed any task yet.</Txt>
                : copilot.actions.slice(0, 30).map((action) => (
                    <View key={action.id} style={{ flexDirection: 'row', gap: space.sm, alignItems: 'center' }}>
                      <View style={{ flex: 1 }}>
                        <Txt variant="small" color={action.undoneAt ? colors.straw : colors.chaff}>
                          {`${ACTION_LABELS[action.kind]}: ${action.title}`}
                        </Txt>
                        <Txt variant="small">
                          {[`${shortDate(action.at)} ${clock(action.at)}`, action.summary, action.auto ? 'automatic' : 'approved', action.undoneAt ? 'undone' : null].filter(Boolean).join(' · ')}
                        </Txt>
                      </View>
                      {!action.undoneAt ? (
                        <Button compact onPress={() => void undo(action).catch((error: unknown) => Alert.alert('Undo failed', errorMessage(error)))}>Undo</Button>
                      ) : null}
                    </View>
                  ))
              : null}
          </Panel>
        </Section>
      ) : null}

      <Section title="Sync">
        <Panel>
          <Row label="Connection" value={sync.online ? 'Online' : 'Offline'} />
          <Row label="Pending changes" value={sync.pending} />
          <Button mode="outlined" disabled={!sync.online || sync.pending === 0} loading={sync.syncing} onPress={() => void flushOutbox()}>
            Sync now
          </Button>
          {(failed.data ?? []).map((item) => (
            <View key={item.id} style={{ gap: space.xs }}>
              <Txt color={colors.rust}>{item.error}</Txt>
              <Button
                compact
                onPress={() => {
                  void removeOperation(item.id).then(refreshOutboxCounts);
                }}
              >
                Discard change
              </Button>
            </View>
          ))}
        </Panel>
      </Section>

      <Section title="Team">
        <QueryState query={team}>
          {(members) => (
            <Panel>
              {members.map((member) => (
                <Row key={member.userId} label={member.fullName} value={ROLE_LABELS[member.role]} />
              ))}
            </Panel>
          )}
        </QueryState>
        {auth.role === 'ADMIN' ? (
          <Panel>
            <Field label="Name" value={fullName} onChangeText={setFullName} />
            <Field label="Email" value={email} onChangeText={setEmail} autoCapitalize="none" keyboardType="email-address" />
            <Field label="Initial password (for new accounts)" value={password} onChangeText={setPassword} secureTextEntry />
            <Choice label="Role" options={roleOptions} value={role} onChange={setRole} />
            <Button mode="contained" disabled={!email.trim() || !fullName.trim() || invite.isPending} loading={invite.isPending} onPress={() => invite.mutate()}>
              Add to team
            </Button>
          </Panel>
        ) : null}
      </Section>

      <Button textColor={colors.rust} onPress={confirmSignOut}>Sign out</Button>
    </Screen>
  );
}
