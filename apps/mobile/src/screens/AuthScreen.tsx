import { useState } from 'react';
import { KeyboardAvoidingView, Platform, ScrollView, View } from 'react-native';
import { Button, HelperText } from 'react-native-paper';
import { apiSend, isQueued } from '../api/client';
import { Field, Txt } from '../components/ui';
import { usesFirebase } from '../firebase/config';
import { errorMessage } from '../lib/hooks';
import { useAuth, type Session } from '../store/auth';
import { colors, space } from '../theme';

export function AuthScreen() {
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [fullName, setFullName] = useState('');
  const [farmName, setFarmName] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const registering = mode === 'register';

  async function submit() {
    setBusy(true);
    setError(null);
    try {
      const body = registering ? { email, password, fullName, farmName } : { email, password };
      const result = await apiSend<Session>('POST', `/auth/${mode}`, body, { offline: false });
      if (!isQueued(result)) await useAuth.getState().setSession(result);
    } catch (problem) {
      setError(errorMessage(problem));
    } finally {
      setBusy(false);
    }
  }

  return (
    <KeyboardAvoidingView style={{ flex: 1, backgroundColor: colors.loam }} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <ScrollView contentContainerStyle={{ flexGrow: 1, justifyContent: 'center', padding: space.xl, gap: space.md }} keyboardShouldPersistTaps="handled">
        <View style={{ gap: space.xs, marginBottom: space.lg }}>
          <Txt variant="display" color={colors.wheat}>FermApp</Txt>
          <Txt color={colors.straw}>
            {registering ? 'Create your account and your first farm.' : 'Your farm’s parcels, field work and water, in one place.'}
          </Txt>
        </View>
        {registering ? (
          <>
            <Field label="Your name" value={fullName} onChangeText={setFullName} autoComplete="name" />
            <Field label="Farm name" value={farmName} onChangeText={setFarmName} />
          </>
        ) : null}
        <Field label="Email" value={email} onChangeText={setEmail} autoCapitalize="none" keyboardType="email-address" autoComplete="email" />
        <Field
          label="Password"
          value={password}
          onChangeText={setPassword}
          secureTextEntry
          autoComplete={registering ? 'new-password' : 'current-password'}
        />
        {registering ? <Txt variant="small">The password must be at least 10 characters long.</Txt> : null}
        {registering && usesFirebase ? <Txt variant="small">Did you receive an invitation? Leave the farm name empty.</Txt> : null}
        {error ? <HelperText type="error" visible>{error}</HelperText> : null}
        <Button mode="contained" loading={busy} disabled={busy || !email || !password} onPress={() => void submit()}>
          {registering ? 'Create account' : 'Sign in'}
        </Button>
        <Button onPress={() => { setMode(registering ? 'login' : 'register'); setError(null); }}>
          {registering ? 'I already have an account' : 'Create a new account'}
        </Button>
      </ScrollView>
    </KeyboardAvoidingView>
  );
}
