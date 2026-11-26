// Runs the Maven wrapper of apps/api with the given arguments, on Windows and on Unix.
import { spawnSync } from 'node:child_process';
import path from 'node:path';

const apiRoot = path.resolve(import.meta.dirname, '..', 'apps', 'api');
const wrapper = process.platform === 'win32' ? 'mvnw.cmd' : './mvnw';
const result = spawnSync(wrapper, process.argv.slice(2), { cwd: apiRoot, stdio: 'inherit', shell: true });
process.exit(result.status ?? 1);
