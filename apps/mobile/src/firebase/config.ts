export const firebaseConfig = {
  apiKey: process.env.EXPO_PUBLIC_FIREBASE_API_KEY ?? '',
  authDomain: 'fermapp-2e549.firebaseapp.com',
  projectId: 'fermapp-2e549',
  storageBucket: 'fermapp-2e549.firebasestorage.app',
  messagingSenderId: '550957017788',
  appId: '1:550957017788:web:bf761313663b2179f4beb9',
  measurementId: 'G-TKZ23NDDK7',
};

/** Without a key, the app keeps using the current server. */
export const isFirebaseConfigured = firebaseConfig.apiKey.length > 0;

/** The app switches to Firebase only when it is explicitly started in this mode. */
export const usesFirebase = isFirebaseConfigured && process.env.EXPO_PUBLIC_BACKEND === 'firebase';
