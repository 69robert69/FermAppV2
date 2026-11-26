import { useAuth } from '../store/auth';
import { startAuthListener, useFirebaseSession } from './auth';
import { startLiveStore } from './liveStore';
import { startOutboxSync } from './outbox';

/** Starts the Firebase session, live data and automatic sync. */
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
      // A session saved from the old server is not valid on Firebase.
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
