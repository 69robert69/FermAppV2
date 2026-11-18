import { File, Paths } from 'expo-file-system';
import { API_URL } from '../config';
import { usesFirebase } from '../firebase/config';
import { enqueue, readCache, writeCache } from '../offline/db';
import { useAuth } from '../store/auth';

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly details?: { field: string; message: string }[],
  ) {
    super(message);
  }
}

/** Rezultatul unei modificări salvate local până revine conexiunea. */
export interface QueuedResult {
  queued: true;
}

export const isQueued = (value: unknown): value is QueuedResult =>
  typeof value === 'object' && value !== null && (value as QueuedResult).queued === true;

export const newOperationId = () =>
  'xxxxxxxx-xxxx-4xxx-8xxx-xxxxxxxxxxxx'.replace(/x/g, () => Math.floor(Math.random() * 16).toString(16));

const firebaseGateway = () => import('../firebase/gateway');

let refreshing: Promise<string | null> | null = null;

/** Reînnoiește sesiunea o singură dată, chiar dacă mai multe cereri expiră simultan. */
export function refreshAccessToken(): Promise<string | null> {
  refreshing ??= (async () => {
    const { refreshToken } = useAuth.getState();
    if (!refreshToken) return null;
    const response = await fetch(`${API_URL}/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken }),
    });
    if (response.status === 401) {
      await useAuth.getState().signOut();
      return null;
    }
    if (!response.ok) return null;
    const { data } = (await response.json()) as { data: { accessToken: string; refreshToken: string } };
    await useAuth.getState().updateTokens(data.accessToken, data.refreshToken);
    return data.accessToken;
  })().finally(() => {
    refreshing = null;
  });
  return refreshing;
}

async function request(path: string, init: RequestInit = {}, canRefresh = true): Promise<Response> {
  const { accessToken } = useAuth.getState();
  const response = await fetch(`${API_URL}${path}`, {
    ...init,
    headers: {
      Accept: 'application/json',
      ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
      ...init.headers,
    },
  });
  if (response.status === 401 && canRefresh && accessToken) {
    const token = await refreshAccessToken();
    if (token) return request(path, init, false);
  }
  return response;
}

async function unwrap<T>(response: Response): Promise<T> {
  if (response.status === 204) return undefined as T;
  const payload = (await response.json().catch(() => null)) as {
    data?: T;
    error?: { code: string; message: string; details?: { field: string; message: string }[] };
  } | null;
  if (!response.ok) {
    throw new ApiError(
      response.status,
      payload?.error?.code ?? 'UNKNOWN',
      payload?.error?.message ?? 'Serverul nu a putut procesa cererea.',
      payload?.error?.details,
    );
  }
  return payload?.data as T;
}

const toQuery = (query?: Record<string, string | number | undefined>) => {
  const params = Object.entries(query ?? {}).filter(([, value]) => value !== undefined);
  return params.length ? `?${params.map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`).join('&')}` : '';
};

/** Citește de pe server, iar fără conexiune returnează ultima versiune salvată. */
export async function apiGet<T>(path: string, query?: Record<string, string | number | undefined>): Promise<T> {
  if (usesFirebase) return (await firebaseGateway()).firebaseGet<T>(path, query);
  const url = `${path}${toQuery(query)}`;
  const scope = `${useAuth.getState().farm?.id ?? 'anon'}:${url}`;
  try {
    const data = await unwrap<T>(await request(url));
    await writeCache(scope, data);
    return data;
  } catch (error) {
    if (error instanceof ApiError) throw error;
    const cachedValue = await readCache<T>(scope);
    if (cachedValue !== null) return cachedValue;
    throw new ApiError(0, 'OFFLINE', 'Nu există conexiune, iar aceste date nu au fost salvate pe telefon.');
  }
}

interface SendOptions {
  /** Permite păstrarea operațiunii în coada offline. */
  offline?: boolean;
  operationId?: string;
  /** Mesajele frecvente, cum este poziția GPS, nu au nevoie de protecție la retrimitere. */
  idempotent?: boolean;
}

export async function apiSend<T>(
  method: 'POST' | 'PATCH' | 'DELETE',
  path: string,
  body?: unknown,
  options: SendOptions = {},
): Promise<T | QueuedResult> {
  if (usesFirebase) return (await firebaseGateway()).firebaseSend<T>(method, path, body as Record<string, unknown> | undefined);
  const operationId = options.operationId ?? newOperationId();
  try {
    const response = await request(path, {
      method,
      headers: {
        'Content-Type': 'application/json',
        ...(options.idempotent === false ? {} : { 'Idempotency-Key': operationId }),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    return await unwrap<T>(response);
  } catch (error) {
    if (error instanceof ApiError) throw error;
    if (options.offline === false) {
      throw new ApiError(0, 'OFFLINE', 'Această acțiune are nevoie de conexiune la internet.');
    }
    await enqueue({ id: operationId, method, path, body });
    return { queued: true };
  }
}

export async function apiUpload<T>(
  path: string,
  file: { uri: string; name: string; mimeType?: string },
  fields?: Record<string, string>,
): Promise<T> {
  if (usesFirebase) return (await firebaseGateway()).firebaseUpload<T>(path, file, fields);
  const form = new FormData();
  for (const [name, value] of Object.entries(fields ?? {})) form.append(name, value);
  form.append('file', { uri: file.uri, name: file.name, type: file.mimeType ?? 'application/octet-stream' } as never);
  try {
    return await unwrap<T>(await request(path, { method: 'POST', body: form }));
  } catch (error) {
    if (error instanceof ApiError) throw error;
    throw new ApiError(0, 'OFFLINE', 'Încărcarea are nevoie de conexiune la internet.');
  }
}

/** Descarcă un fișier generat de server și returnează adresa lui locală. */
export async function apiDownload(path: string, filename: string): Promise<string> {
  if (usesFirebase) return (await firebaseGateway()).firebaseDownload(path, filename);
  const destination = new File(Paths.cache, filename);
  if (destination.exists) destination.delete();
  const download = (token: string | null) =>
    File.downloadFileAsync(`${API_URL}${path}`, destination, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    });
  try {
    return (await download(useAuth.getState().accessToken ?? null)).uri;
  } catch {
    const token = await refreshAccessToken().catch(() => null);
    if (!token) throw new ApiError(0, 'OFFLINE', 'Fișierul nu a putut fi descărcat. Verifică conexiunea.');
    if (destination.exists) destination.delete();
    return (await download(token)).uri;
  }
}

export { API_URL };
