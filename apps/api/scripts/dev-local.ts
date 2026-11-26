// Starts a local PostgreSQL database, applies migrations, loads demo data and starts the API.
import { execSync, spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import path from 'node:path';
import EmbeddedPostgres from 'embedded-postgres';

const apiRoot = path.resolve(import.meta.dirname, '..');
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

const run = (command: string) => execSync(command, { cwd: apiRoot, stdio: 'inherit', env: process.env });

console.log('Starting the database...');
if (firstRun) await postgres.initialise();
await postgres.start();
if (firstRun) await postgres.createDatabase('fermapp');

console.log('Preparing the data...');
run('npx prisma generate');
run('npx prisma migrate deploy');
run('npx tsx --env-file=.env prisma/seed.ts');

console.log('Starting the server...');
const server = spawn('npx tsx --env-file=.env src/server.ts', { cwd: apiRoot, stdio: 'inherit', shell: true });

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
