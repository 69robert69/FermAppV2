import AsyncStorage from '@react-native-async-storage/async-storage';
import { getApps, initializeApp } from 'firebase/app';
import * as FirebaseAuth from 'firebase/auth';
import { initializeFirestore, memoryLocalCache } from 'firebase/firestore';
import { firebaseConfig } from './config';

// Funcția există doar în varianta React Native a pachetului, pe care tipurile implicite nu o descriu.
const { getReactNativePersistence } = FirebaseAuth as unknown as {
  getReactNativePersistence: (storage: typeof AsyncStorage) => FirebaseAuth.Persistence;
};

const existing = getApps()[0];
export const firebaseApp = existing ?? initializeApp(firebaseConfig);

export const auth = existing
  ? FirebaseAuth.getAuth(firebaseApp)
  : FirebaseAuth.initializeAuth(firebaseApp, { persistence: getReactNativePersistence(AsyncStorage) });

// În Expo Go, Firestore păstrează datele doar în memorie. Persistența durabilă este în outbox.ts.
export const firestore = initializeFirestore(firebaseApp, {
  localCache: memoryLocalCache(),
  experimentalAutoDetectLongPolling: true,
});
