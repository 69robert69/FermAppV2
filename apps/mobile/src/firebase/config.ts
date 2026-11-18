export const firebaseConfig = {
  apiKey: process.env.EXPO_PUBLIC_FIREBASE_API_KEY ?? '',
  authDomain: 'fermapp-2e549.firebaseapp.com',
  projectId: 'fermapp-2e549',
  storageBucket: 'fermapp-2e549.firebasestorage.app',
  messagingSenderId: '550957017788',
  appId: '1:550957017788:web:bf761313663b2179f4beb9',
  measurementId: 'G-TKZ23NDDK7',
};

/** Fără cheie, aplicația folosește în continuare serverul actual. */
export const isFirebaseConfigured = firebaseConfig.apiKey.length > 0;

/** Aplicația trece pe Firebase doar când este pornită explicit în acest mod. */
export const usesFirebase = isFirebaseConfigured && process.env.EXPO_PUBLIC_BACKEND === 'firebase';
