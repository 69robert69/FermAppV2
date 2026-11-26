import AsyncStorage from '@react-native-async-storage/async-storage';
import { getApps, initializeApp } from 'firebase/app';
import * as FirebaseAuth from 'firebase/auth';
import { initializeFirestore, memoryLocalCache } from 'firebase/firestore';
import { firebaseConfig } from './config';

// The function exists only in the React Native build of the package, which the default types do not describe.
const { getReactNativePersistence } = FirebaseAuth as unknown as {
  getReactNativePersistence: (storage: typeof AsyncStorage) => FirebaseAuth.Persistence;
};

const existing = getApps()[0];
export const firebaseApp = existing ?? initializeApp(firebaseConfig);

export const auth = existing
  ? FirebaseAuth.getAuth(firebaseApp)
  : FirebaseAuth.initializeAuth(firebaseApp, { persistence: getReactNativePersistence(AsyncStorage) });

// In Expo Go, Firestore keeps data only in memory. Durable persistence lives in outbox.ts.
export const firestore = initializeFirestore(firebaseApp, {
  localCache: memoryLocalCache(),
  experimentalAutoDetectLongPolling: true,
});
