import { useAuth } from '../store/auth';
import { startAuthListener, useFirebaseSession } from './auth';
import { startLiveStore } from './liveStore';
import { startOutboxSync } from './outbox';

/** Pornește sesiunea Firebase, datele live și sincronizarea automată. */
export function startFirebase() {
  let stopLive: (() => void) | null = null;
  let liveFarm: string | null = null;

  const stopSession = useFirebaseSession.subscribe(({ status, profile }) => {
    if (status === 'signedIn' && profile && liveFarm !== profile.farmId) {
      stopLive?.();
      liveFarm = profile.farmId;
      stopLive = startLiveStore(profile);
    }
    if (status === 'signedOut') {
      stopLive?.();
      stopLive = null;
      liveFarm = null;
      // O sesiune salvată de pe serverul vechi nu este valabilă pe Firebase.
      if (useAuth.getState().status === 'signedIn') void useAuth.getState().signOut();
    }
  });
  const stopAuth = startAuthListener();
  const stopSync = startOutboxSync();

  return () => {
    stopSession();
    stopAuth();
    stopSync();
    stopLive?.();
  };
}
