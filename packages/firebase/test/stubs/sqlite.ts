/** Bază de date minimă, suficientă pentru cozile și copiile locale ale aplicației. */
interface Row { seq: number; batch_id: string; kind: string; collection: string; doc_id: string; data: string | null; error: string | null }

const writes: Row[] = [];
const snapshots = new Map<string, string>();
let seq = 0;

const connection = {
  execAsync: async (sql: string) => {
    if (sql.includes('DELETE FROM writes')) {
      writes.length = 0;
      snapshots.clear();
    }
  },
  withTransactionAsync: async (task: () => Promise<void>) => task(),
  runAsync: async (sql: string, ...args: (string | null)[]) => {
    if (sql.startsWith('INSERT INTO writes')) {
      const [batch_id, kind, collection, doc_id, data] = args as [string, string, string, string, string | null];
      writes.push({ seq: (seq += 1), batch_id, kind, collection, doc_id, data, error: null });
    } else if (sql.startsWith('DELETE FROM writes')) {
      for (let i = writes.length - 1; i >= 0; i -= 1) if (writes[i]!.batch_id === args[0]) writes.splice(i, 1);
    } else if (sql.startsWith('UPDATE writes')) {
      for (const row of writes) if (row.batch_id === args[1]) row.error = args[0] as string;
    } else if (sql.startsWith('INSERT OR REPLACE INTO snapshots')) {
      snapshots.set(args[0] as string, args[1] as string);
    }
  },
  getAllAsync: async (sql: string) => (sql.includes('error IS NULL') ? writes.filter((row) => row.error === null) : writes.filter((row) => row.error !== null)),
  getFirstAsync: async (_sql: string, key: string) => (snapshots.has(key) ? { value: snapshots.get(key) } : null),
};

export const openDatabaseAsync = async () => connection;
export type SQLiteDatabase = typeof connection;
export const rejected = () => writes.filter((row) => row.error !== null);
