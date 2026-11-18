import { useNavigation, type NavigationProp } from '@react-navigation/native';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Alert, View } from 'react-native';
import { Button } from 'react-native-paper';
import { apiGet, apiSend, isQueued } from '../api/client';
import { queryClient } from '../api/queryClient';
import type { Role, TeamMember } from '../api/types';
import { Choice, Field, Panel, QueryState, Row, Screen, Section, Txt } from '../components/ui';
import { usesFirebase } from '../firebase/config';
import { ROLE_LABELS } from '../lib/format';
import { errorMessage, useAction } from '../lib/hooks';
import { failedOperations, removeOperation } from '../offline/db';
import { flushOutbox, refreshOutboxCounts, useSync } from '../offline/sync';
import type { RootStackParams } from '../navigation/RootNavigator';
import { useAuth } from '../store/auth';
import { colors, space } from '../theme';

const roleOptions = (Object.keys(ROLE_LABELS) as Role[]).map((value) => ({ value, label: ROLE_LABELS[value]! }));

export function SettingsScreen() {
  const navigation = useNavigation<NavigationProp<RootStackParams>>();
  const auth = useAuth();
  const sync = useSync();
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
      Alert.alert('Sincronizează mai întâi', 'Modificările netrimise aparțin fermei curente.');
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
      Alert.alert('Ferma nu a putut fi schimbată', errorMessage(error));
    }
  }

  async function signOut() {
    const refreshToken = auth.refreshToken;
    if (refreshToken) await apiSend('POST', '/auth/logout', { refreshToken }, { offline: false }).catch(() => undefined);
    await auth.signOut();
  }

  const confirmSignOut = () => {
    const warning = sync.pending ? ` ${sync.pending} modificări netrimise se vor pierde.` : '';
    Alert.alert('Ieși din cont', `Datele salvate pe acest telefon se șterg.${warning}`, [
      { text: 'Renunță', style: 'cancel' },
      { text: 'Ieși din cont', style: 'destructive', onPress: () => void signOut() },
    ]);
  };

  return (
    <Screen>
      <Panel>
        <Txt variant="title">{auth.user?.fullName}</Txt>
        <Txt variant="small">{auth.user?.email}</Txt>
        <Row label="Fermă" value={auth.farm?.name} />
        <Row label="Rol" value={auth.role ? ROLE_LABELS[auth.role] : ''} />
      </Panel>

      {usesFirebase ? (
        <Button mode="outlined" icon="book-open-variant" onPress={() => navigation.navigate('Catalog')}>Catalog agricol</Button>
      ) : null}

      {auth.farms && auth.farms.length > 1 ? (
        <Section title="Schimbă ferma">
          <Choice options={auth.farms.map((farm) => ({ value: farm.id, label: farm.name }))} value={auth.farm?.id} onChange={(id) => void switchFarm(id)} />
        </Section>
      ) : null}

      <Section title="Sincronizare">
        <Panel>
          <Row label="Conexiune" value={sync.online ? 'Online' : 'Offline'} />
          <Row label="Modificări în așteptare" value={sync.pending} />
          <Button mode="outlined" disabled={!sync.online || sync.pending === 0} loading={sync.syncing} onPress={() => void flushOutbox()}>
            Sincronizează acum
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
                Renunță la modificare
              </Button>
            </View>
          ))}
        </Panel>
      </Section>

      <Section title="Echipa">
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
            <Field label="Nume" value={fullName} onChangeText={setFullName} />
            <Field label="Email" value={email} onChangeText={setEmail} autoCapitalize="none" keyboardType="email-address" />
            <Field label="Parolă inițială (pentru conturi noi)" value={password} onChangeText={setPassword} secureTextEntry />
            <Choice label="Rol" options={roleOptions} value={role} onChange={setRole} />
            <Button mode="contained" disabled={!email.trim() || !fullName.trim() || invite.isPending} loading={invite.isPending} onPress={() => invite.mutate()}>
              Adaugă în echipă
            </Button>
          </Panel>
        ) : null}
      </Section>

      <Button textColor={colors.rust} onPress={confirmSignOut}>Ieși din cont</Button>
    </Screen>
  );
}
