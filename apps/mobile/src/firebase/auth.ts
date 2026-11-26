import { FirebaseError } from 'firebase/app';
import {
  createUserWithEmailAndPassword,
  onAuthStateChanged,
  sendEmailVerification,
  sendPasswordResetEmail,
  signInWithEmailAndPassword,
  signOut,
  updateProfile,
  type User,
} from 'firebase/auth';
import { collection, deleteDoc, doc, getDoc, onSnapshot, serverTimestamp, setDoc } from 'firebase/firestore';
import { create } from 'zustand';
import { auth, firestore } from './app';
import { clearFirebaseLocalData, readSnapshot, writeSnapshot } from './outbox';
import type { FarmRole, UserProfile } from './types';

const PROFILE_KEY = 'session:profile';

interface FirebaseSession {
  status: 'loading' | 'signedOut' | 'needsFarm' | 'signedIn';
  user: User | null;
  profile: UserProfile | null;
}

export const useFirebaseSession = create<FirebaseSession>(() => ({ status: 'loading', user: null, profile: null }));

const MESSAGES: Record<string, string> = {
  'auth/email-already-in-use': 'An account with this email already exists.',
  'auth/invalid-email': 'The email address is not valid.',
  'auth/weak-password': 'The password must be at least 6 characters long.',
  'auth/invalid-credential': 'The email or password is incorrect.',
  'auth/too-many-requests': 'Too many attempts. Try again in a few minutes.',
  'auth/network-request-failed': 'Signing in needs an internet connection.',
  'permission-denied': 'Your account does not have access to this action.',
};

export const authErrorMessage = (error: unknown) =>
  (error instanceof FirebaseError ? MESSAGES[error.code] : undefined) ?? 'Sign-in failed. Try again.';

/** Starts session tracking. Without a connection, the last saved profile is used. */
export function startAuthListener() {
  let stopProfile: (() => void) | null = null;
  const stopAuth = onAuthStateChanged(auth, (user) => {
    stopProfile?.();
    stopProfile = null;
    if (!user) {
      useFirebaseSession.setState({ status: 'signedOut', user: null, profile: null });
      return;
    }
    useFirebaseSession.setState({ user });
    void readSnapshot<UserProfile>(PROFILE_KEY).then((saved) => {
      if (saved?.uid === user.uid && !useFirebaseSession.getState().profile) {
        useFirebaseSession.setState({ status: 'signedIn', profile: saved });
      }
    });
    stopProfile = onSnapshot(doc(firestore, 'users', user.uid), (snapshot) => {
      if (!snapshot.exists()) {
        // Without a connection there is no way to know whether the profile exists, so the one saved on the phone remains.
        if (snapshot.metadata.fromCache) return;
        useFirebaseSession.setState({ status: 'needsFarm', profile: null });
        return;
      }
      const profile = snapshot.data() as UserProfile;
      useFirebaseSession.setState({ status: 'signedIn', profile });
      void writeSnapshot(PROFILE_KEY, profile);
    });
  });
  return () => {
    stopProfile?.();
    stopAuth();
  };
}

/** Creates the account, the farm and the administrator profile. */
export async function registerFarm(input: { name: string; email: string; password: string; farmName: string }) {
  const { user } = await createUserWithEmailAndPassword(auth, input.email.trim(), input.password);
  await updateProfile(user, { displayName: input.name });
  const farm = doc(collection(firestore, 'farms'));
  await setDoc(farm, { ownerId: user.uid, name: input.farmName, createdAt: serverTimestamp() });
  const profile: UserProfile = {
    uid: user.uid,
    name: input.name,
    email: user.email ?? input.email.trim(),
    role: 'ADMIN',
    farmId: farm.id,
    farmName: input.farmName,
    createdAt: new Date().toISOString(),
  };
  await setDoc(doc(firestore, 'users', user.uid), profile);
}

/** Account of an invited member. Joining the farm requires confirming the email address. */
export async function registerInvited(input: { name: string; email: string; password: string }) {
  const { user } = await createUserWithEmailAndPassword(auth, input.email.trim(), input.password);
  await updateProfile(user, { displayName: input.name });
  await sendEmailVerification(user);
}

/** Called after the user has confirmed the email. */
export async function joinInvitedFarm() {
  const user = auth.currentUser;
  if (!user?.email) throw new Error('Sign in before accepting the invitation.');
  await user.reload();
  await user.getIdToken(true);
  const inviteRef = doc(firestore, 'invites', user.email.toLowerCase());
  const invite = await getDoc(inviteRef);
  if (!invite.exists()) throw new Error('There is no invitation for this address.');
  const { farmId, farmName, role } = invite.data() as { farmId: string; farmName: string; role: FarmRole };
  const profile: UserProfile = {
    uid: user.uid,
    name: user.displayName ?? user.email,
    email: user.email,
    role,
    farmId,
    farmName,
    createdAt: new Date().toISOString(),
  };
  await setDoc(doc(firestore, 'users', user.uid), profile);
  await deleteDoc(inviteRef);
}

export async function inviteMember(email: string, role: FarmRole) {
  const profile = useFirebaseSession.getState().profile;
  if (!profile) throw new Error('The session is not active.');
  await setDoc(doc(firestore, 'invites', email.trim().toLowerCase()), {
    farmId: profile.farmId,
    farmName: profile.farmName,
    role,
    createdAt: new Date().toISOString(),
  });
}

export const signInWithEmail = (email: string, password: string) => signInWithEmailAndPassword(auth, email.trim(), password);
export const resetPassword = (email: string) => sendPasswordResetEmail(auth, email.trim());

export async function signOutFirebase() {
  await signOut(auth);
  await clearFirebaseLocalData();
}
