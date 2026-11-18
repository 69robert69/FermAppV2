import { initializeApp } from 'firebase/app';
import { connectAuthEmulator, getAuth } from 'firebase/auth';
import { connectFirestoreEmulator, initializeFirestore, memoryLocalCache } from 'firebase/firestore';
import { connectStorageEmulator, getStorage } from 'firebase/storage';

export const firebaseApp = initializeApp({ apiKey: 'demo-key', projectId: 'demo-fermapp', storageBucket: 'demo-fermapp.appspot.com' });
export const auth = getAuth(firebaseApp);
connectAuthEmulator(auth, 'http://127.0.0.1:9099', { disableWarnings: true });
export const firestore = initializeFirestore(firebaseApp, { localCache: memoryLocalCache() });
connectFirestoreEmulator(firestore, '127.0.0.1', 8180);
export const storage = getStorage(firebaseApp);
connectStorageEmulator(storage, '127.0.0.1', 9199);
