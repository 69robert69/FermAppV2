import * as SecureStore from 'expo-secure-store';
import { create } from 'zustand';
import type { Role } from '../api/types';
import { clearLocalData } from '../offline/db';

const STORAGE_KEY = 'fermapp.session';

interface FarmRef {
  id: string;
  name: string;
  role?: Role;
}

export interface Session {
  user: { id: string; email: string; fullName: string };
  farm: FarmRef;
  role: Role;
  farms?: FarmRef[];
  accessToken: string;
  refreshToken: string;
}

interface AuthState extends Partial<Session> {
  status: 'loading' | 'signedOut' | 'signedIn';
  restore: () => Promise<void>;
  setSession: (session: Session) => Promise<void>;
  updateTokens: (accessToken: string, refreshToken: string) => Promise<void>;
  signOut: () => Promise<void>;
}

const EMPTY = {
  user: undefined,
  farm: undefined,
  role: undefined,
  farms: undefined,
  accessToken: undefined,
  refreshToken: undefined,
};

function persist(state: Session) {
  const { user, farm, role, farms, accessToken, refreshToken } = state;
  return SecureStore.setItemAsync(STORAGE_KEY, JSON.stringify({ user, farm, role, farms, accessToken, refreshToken }));
}

export const useAuth = create<AuthState>((set, get) => ({
  status: 'loading',

  restore: async () => {
    if (get().status === 'signedIn') return;
    const stored = await SecureStore.getItemAsync(STORAGE_KEY).catch(() => null);
    if (!stored) {
      set({ status: 'signedOut' });
      return;
    }
    set({ ...(JSON.parse(stored) as Session), status: 'signedIn' });
  },

  setSession: async (session) => {
    await persist(session);
    set({ ...session, status: 'signedIn' });
  },

  updateTokens: async (accessToken, refreshToken) => {
    set({ accessToken, refreshToken });
    const { user, farm, role, farms } = get();
    if (user && farm && role) await persist({ user, farm, role, farms, accessToken, refreshToken });
  },

  signOut: async () => {
    set({ ...EMPTY, status: 'signedOut' });
    await SecureStore.deleteItemAsync(STORAGE_KEY).catch(() => undefined);
    await clearLocalData().catch(() => undefined);
    const { queryClient } = await import('../api/queryClient');
    queryClient.clear();
    // The copilot conversation and its action log hold farm data.
    const { useCopilot } = await import('../features/copilot/store');
    useCopilot.setState({ messages: [], actions: [] });
    useCopilot.getState().clear();
  },
}));

export const canManage = (role?: Role) => role === 'ADMIN' || role === 'FARM_MANAGER';
export const canPlan = (role?: Role) => canManage(role) || role === 'AGRONOMIST';
export const canOperate = (role?: Role) => canManage(role) || role === 'OPERATOR';
