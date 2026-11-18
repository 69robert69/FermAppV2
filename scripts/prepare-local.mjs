// Pregătește fișierele .env pentru rularea locală și afișează adresa calculatorului în rețea.
import { randomBytes } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { networkInterfaces } from 'node:os';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');
const VIRTUAL = /vethernet|virtualbox|vmware|wsl|hyper-v|loopback|bluetooth|tailscale|zerotier/i;

function findLanAddress() {
  const candidates = Object.entries(networkInterfaces())
    .filter(([name]) => !VIRTUAL.test(name))
    .flatMap(([, addresses]) => addresses ?? [])
    .filter((address) => address.family === 'IPv4' && !address.internal)
    .map((address) => address.address);
  return (
    candidates.find((ip) => ip.startsWith('192.168.')) ??
    candidates.find((ip) => ip.startsWith('10.')) ??
    candidates[0] ??
    '127.0.0.1'
  );
}

const apiEnv = path.join(root, 'apps/api/.env');
if (!existsSync(apiEnv)) {
  const example = readFileSync(path.join(root, 'apps/api/.env.example'), 'utf8');
  const content = example
    .replace(/^DATABASE_URL=.*$/m, 'DATABASE_URL=postgresql://fermapp:fermapp@localhost:54320/fermapp')
    .replace(/^REDIS_URL=.*$/m, 'REDIS_URL=')
    .replace(/^JWT_ACCESS_SECRET=.*$/m, `JWT_ACCESS_SECRET=${randomBytes(48).toString('base64url')}`);
  writeFileSync(apiEnv, content);
}

const ip = findLanAddress();
// Se actualizează doar adresa serverului, iar celelalte setări rămân neschimbate.
const mobileEnv = path.join(root, 'apps/mobile/.env');
const otherSettings = existsSync(mobileEnv)
  ? readFileSync(mobileEnv, 'utf8').split(/\r?\n/).filter((line) => line.trim() && !line.startsWith('EXPO_PUBLIC_API_URL='))
  : [];
writeFileSync(mobileEnv, [`EXPO_PUBLIC_API_URL=http://${ip}:4000`, ...otherSettings, ''].join('\n'));
console.log(ip);
