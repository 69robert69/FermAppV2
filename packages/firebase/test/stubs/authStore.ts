export type Session = Record<string, unknown>;
export const useAuth = { getState: () => ({ status: 'signedOut', signOut: async () => undefined }) };
