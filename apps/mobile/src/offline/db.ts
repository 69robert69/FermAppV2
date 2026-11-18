import * as SQLite from 'expo-sqlite';

let database: Promise<SQLite.SQLiteDatabase> | null = null;

function db() {
  database ??= SQLite.openDatabaseAsync('fermapp.db').then(async (connection) => {
    await connection.execAsync(`
      PRAGMA journal_mode = WAL;
      CREATE TABLE IF NOT EXISTS cache (
        key TEXT PRIMARY KEY NOT NULL,
        value TEXT NOT NULL,
        updated_at INTEGER NOT NULL
      );
      CREATE TABLE IF NOT EXISTS outbox (
        id TEXT PRIMARY KEY NOT NULL,
        method TEXT NOT NULL,
        path TEXT NOT NULL,
        body TEXT,
        created_at INTEGER NOT NULL,
        error TEXT
      );
    `);
    return connection;
  });
  return database;
}

export async function writeCache(key: string, value: unknown) {
  const connection = await db();
  await connection.runAsync(
    'INSERT OR REPLACE INTO cache (key, value, updated_at) VALUES (?, ?, ?)',
    key,
    JSON.stringify(value),
    Date.now(),
  );
}

export async function readCache<T>(key: string): Promise<T | null> {
  const connection = await db();
  const row = await connection.getFirstAsync<{ value: string }>('SELECT value FROM cache WHERE key = ?', key);
  return row ? (JSON.parse(row.value) as T) : null;
}

export interface OutboxItem {
  id: string;
  method: 'POST' | 'PATCH' | 'DELETE';
  path: string;
  body?: unknown;
  error?: string | null;
}

export async function enqueue(item: OutboxItem) {
  const connection = await db();
  await connection.runAsync(
    'INSERT OR IGNORE INTO outbox (id, method, path, body, created_at) VALUES (?, ?, ?, ?, ?)',
    item.id,
    item.method,
    item.path,
    item.body === undefined ? null : JSON.stringify(item.body),
    Date.now(),
  );
}

/** Operațiunile care așteaptă trimiterea, în ordinea în care au fost făcute. */
export async function pendingOperations(): Promise<OutboxItem[]> {
  const connection = await db();
  const rows = await connection.getAllAsync<{ id: string; method: OutboxItem['method']; path: string; body: string | null }>(
    'SELECT id, method, path, body FROM outbox WHERE error IS NULL ORDER BY created_at ASC',
  );
  return rows.map((row) => ({ ...row, body: row.body === null ? undefined : JSON.parse(row.body) }));
}

export async function removeOperation(id: string) {
  const connection = await db();
  await connection.runAsync('DELETE FROM outbox WHERE id = ?', id);
}

export async function failOperation(id: string, error: string) {
  const connection = await db();
  await connection.runAsync('UPDATE outbox SET error = ? WHERE id = ?', error, id);
}

export async function outboxCounts() {
  const connection = await db();
  const row = await connection.getFirstAsync<{ pending: number; failed: number }>(
    `SELECT COALESCE(SUM(error IS NULL), 0) AS pending, COALESCE(SUM(error IS NOT NULL), 0) AS failed FROM outbox`,
  );
  return row ?? { pending: 0, failed: 0 };
}

export async function failedOperations() {
  const connection = await db();
  return connection.getAllAsync<{ id: string; path: string; error: string }>(
    'SELECT id, path, error FROM outbox WHERE error IS NOT NULL ORDER BY created_at ASC',
  );
}

/** Datele locale ale contului se șterg la deconectare. */
export async function clearLocalData() {
  const connection = await db();
  await connection.execAsync('DELETE FROM cache; DELETE FROM outbox;');
}
