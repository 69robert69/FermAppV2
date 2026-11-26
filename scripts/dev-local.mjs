// Starts the local PostgreSQL database and then the Java API (Spring Boot), with the demo farm.
// The database keeps its data in apps/api/.tmp/pgdata, the same folder the TypeScript server used,
// so an existing local database is taken over: Flyway sees the Prisma tables and baselines them.
import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import path from 'node:path';
import EmbeddedPostgres from 'embedded-postgres';

const root = path.resolve(import.meta.dirname, '..');
const apiRoot = path.join(root, 'apps', 'api');
const databaseDir = path.join(apiRoot, '.tmp', 'pgdata');
const firstRun = !existsSync(path.join(databaseDir, 'PG_VERSION'));

const postgres = new EmbeddedPostgres({
  databaseDir,
  user: 'fermapp',
  password: 'fermapp',
  port: 54320,
  persistent: true,
  initdbFlags: ['--encoding=UTF8', '--locale=C'],
  onLog: () => undefined,
  onError: () => undefined,
});

console.log('Starting the database...');
if (firstRun) await postgres.initialise();
await postgres.start();
if (firstRun) await postgres.createDatabase('fermapp');

console.log('Starting the server (the first start downloads the Java dependencies)...');
const wrapper = process.platform === 'win32' ? 'mvnw.cmd' : './mvnw';
const server = spawn(wrapper, ['-q', 'spring-boot:run'], {
  cwd: apiRoot,
  stdio: 'inherit',
  shell: true,
  env: {
    ...process.env,
    DATABASE_URL: 'postgresql://fermapp:fermapp@localhost:54320/fermapp',
    SEED_DEMO_DATA: 'true',
  },
});

let stopping = false;
async function stop() {
  if (stopping) return;
  stopping = true;
  server.kill();
  await postgres.stop().catch(() => undefined);
  process.exit(0);
}

server.on('exit', () => void stop());
process.on('SIGINT', () => void stop());
process.on('SIGTERM', () => void stop());
