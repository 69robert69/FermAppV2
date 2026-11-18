import NetInfo from '@react-native-community/netinfo';
import { FirebaseError } from 'firebase/app';
import { deleteDoc, doc, serverTimestamp, setDoc, updateDoc, writeBatch } from 'firebase/firestore';
import * as SQLite from 'expo-sqlite';
import { firestore } from './app';
import type { CollectionName } from './types';

export type WriteKind = 'set' | 'update' | 'delete' | 'once';

export interface PendingWrite {
  kind: WriteKind;
  collection: CollectionName;
  docId: string;
  data?: Record<string, unknown>;
}

interface StoredWrite extends PendingWrite {
  seq: number;
  batchId: string;
}

const SEND_TIMEOUT_MS = 15_000;
/** Erori definitive: reîncercarea nu poate schimba rezultatul. */
const REJECTED = new Set(['permission-denied', 'invalid-argument', 'not-found', 'failed-precondition']);

let database: Promise<SQLite.SQLiteDatabase> | null = null;

function db() {
  database ??= SQLite.openDatabaseAsync('fermapp-firebase.db').then(async (connection) => {
    await connection.execAsync(`
      PRAGMA journal_mode = WAL;
      CREATE TABLE IF NOT EXISTS writes (
        seq INTEGER PRIMARY KEY AUTOINCREMENT,
        batch_id TEXT NOT NULL,
        kind TEXT NOT NULL,
        collection TEXT NOT NULL,
        doc_id TEXT NOT NULL,
        data TEXT,
        error TEXT
      );
      CREATE TABLE IF NOT EXISTS snapshots (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL);
    `);
    return connection;
  });
  return database;
}

const listeners = new Set<() => void>();
let pendingCache: StoredWrite[] = [];
let onQueued: (writes: readonly PendingWrite[]) => void = () => undefined;
let onRejected: (code: string) => void = () => undefined;

/** Ecranele se reîmprospătează imediat, iar o scriere refuzată de server devine vizibilă. */
export function observeWrites(handlers: { queued: typeof onQueued; rejected: typeof onRejected }) {
  onQueued = handlers.queued;
  onRejected = handlers.rejected;
}

export const subscribeOutbox = (listener: () => void) => {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
};
export const pendingSnapshot = (): readonly PendingWrite[] => pendingCache;

async function refreshPending() {
  const connection = await db();
  const rows = await connection.getAllAsync<{ seq: number; batch_id: string; kind: WriteKind; collection: CollectionName; doc_id: string; data: string | null }>(
    'SELECT seq, batch_id, kind, collection, doc_id, data FROM writes WHERE error IS NULL ORDER BY seq ASC',
  );
  pendingCache = rows.map((row) => ({
    seq: row.seq,
    batchId: row.batch_id,
    kind: row.kind,
    collection: row.collection,
    docId: row.doc_id,
    data: row.data === null ? undefined : (JSON.parse(row.data) as Record<string, unknown>),
  }));
  listeners.forEach((listener) => listener());
}

/** Salvează scrierile pe telefon, apoi încearcă să le trimită. Un grup se aplică integral sau deloc. */
export async function queueWrites(writes: PendingWrite[]) {
  const connection = await db();
  const batchId = `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  await connection.withTransactionAsync(async () => {
    for (const write of writes) {
      await connection.runAsync(
        'INSERT INTO writes (batch_id, kind, collection, doc_id, data) VALUES (?, ?, ?, ?, ?)',
        batchId,
        write.kind,
        write.collection,
        write.docId,
        write.data === undefined ? null : JSON.stringify(write.data),
      );
    }
  });
  await refreshPending();
  onQueued(writes);
  void flushWrites();
}

const stamped = (write: PendingWrite) => ({ ...write.data, updatedAt: serverTimestamp() });

function sendGroup(group: StoredWrite[]) {
  const [first] = group;
  if (group.length === 1 && first) {
    const ref = doc(firestore, first.collection, first.docId);
    if (first.kind === 'delete') return deleteDoc(ref);
    if (first.kind === 'update') return updateDoc(ref, stamped(first));
    return setDoc(ref, stamped(first), { merge: true });
  }
  const batch = writeBatch(firestore);
  for (const write of group) {
    const ref = doc(firestore, write.collection, write.docId);
    if (write.kind === 'delete') batch.delete(ref);
    else if (write.kind === 'update') batch.update(ref, stamped(write));
    else batch.set(ref, stamped(write), { merge: true });
  }
  return batch.commit();
}

/** Firestore nu răspunde la scrieri cât timp este offline, deci așteptarea are o limită. */
function withTimeout(promise: Promise<void>) {
  return new Promise<void>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('TIMEOUT')), SEND_TIMEOUT_MS);
    promise.then(resolve, reject).finally(() => clearTimeout(timer));
  });
}

let flushing: Promise<void> | null = null;
let flushAgain = false;

/** Trimite scrierile în ordine și se oprește la prima problemă de conexiune. */
export function flushWrites(): Promise<void> {
  // Scrierile apărute în timpul unei trimiteri pleacă imediat după ea.
  if (flushing) flushAgain = true;
  flushing ??= (async () => {
    const network = await NetInfo.fetch();
    if (network.isConnected === false) return;
    const connection = await db();
    const groups = new Map<string, StoredWrite[]>();
    for (const write of pendingCache) groups.set(write.batchId, [...(groups.get(write.batchId) ?? []), write]);

    for (const [batchId, group] of groups) {
      try {
        await withTimeout(sendGroup(group));
        await connection.runAsync('DELETE FROM writes WHERE batch_id = ?', batchId);
      } catch (error) {
        const code = error instanceof FirebaseError ? error.code.replace('firestore/', '') : '';
        if (!REJECTED.has(code)) break;
        // O notificare trimisă deja de alt telefon nu este o eroare.
        if (group.every((write) => write.kind === 'once')) {
          await connection.runAsync('DELETE FROM writes WHERE batch_id = ?', batchId);
        } else {
          await connection.runAsync('UPDATE writes SET error = ? WHERE batch_id = ?', code, batchId);
          onRejected(code);
        }
      }
    }
    await refreshPending();
  })().finally(() => {
    flushing = null;
    if (flushAgain) {
      flushAgain = false;
      void flushWrites();
    }
  });
  return flushing;
}

export async function rejectedWrites() {
  const connection = await db();
  return connection.getAllAsync<{ seq: number; collection: string; doc_id: string; error: string }>(
    'SELECT seq, collection, doc_id, error FROM writes WHERE error IS NOT NULL ORDER BY seq ASC',
  );
}

export async function readSnapshot<T>(key: string): Promise<T | null> {
  const connection = await db();
  const row = await connection.getFirstAsync<{ value: string }>('SELECT value FROM snapshots WHERE key = ?', key);
  return row ? (JSON.parse(row.value) as T) : null;
}

export async function writeSnapshot(key: string, value: unknown) {
  const connection = await db();
  await connection.runAsync('INSERT OR REPLACE INTO snapshots (key, value) VALUES (?, ?)', key, JSON.stringify(value));
}

/** Datele contului se șterg de pe telefon la deconectare. */
export async function clearFirebaseLocalData() {
  const connection = await db();
  await connection.execAsync('DELETE FROM writes; DELETE FROM snapshots;');
  await refreshPending();
}

/** Sincronizează automat la pornire și la revenirea conexiunii. */
export function startOutboxSync() {
  void refreshPending().then(flushWrites);
  return NetInfo.addEventListener((state) => {
    if (state.isConnected !== false && state.isInternetReachable !== false) void flushWrites();
  });
}
