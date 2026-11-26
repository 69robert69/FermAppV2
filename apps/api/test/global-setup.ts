import { execFileSync } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import EmbeddedPostgres from 'embedded-postgres';

const PORT = 54329;

export default async function setup() {
  const databaseDir = mkdtempSync(path.join(tmpdir(), 'fermapp-pg-'));
  const postgres = new EmbeddedPostgres({
    databaseDir,
    user: 'fermapp',
    password: 'fermapp',
    port: PORT,
    persistent: false,
    // On Windows, the default encoding does not accept Romanian diacritics.
    initdbFlags: ['--encoding=UTF8', '--locale=C'],
    onLog: () => undefined,
    onError: () => undefined,
  });
  await postgres.initialise();
  await postgres.start();
  await postgres.createDatabase('fermapp_test');

  const url = `postgresql://fermapp:fermapp@localhost:${PORT}/fermapp_test`;
  process.env.DATABASE_URL = url;
  process.env.NODE_ENV = 'test';
  process.env.JWT_ACCESS_SECRET = 'test-secret-test-secret-test-secret-123';
  process.env.JOBS_ENABLED = 'false';

  execFileSync('npx', ['prisma', 'migrate', 'deploy'], {
    cwd: path.resolve(import.meta.dirname, '..'),
    env: process.env,
    stdio: 'pipe',
    shell: true,
  });

  return async () => {
    await postgres.stop();
    rmSync(databaseDir, { recursive: true, force: true });
  };
}
