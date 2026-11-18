import { randomUUID } from 'node:crypto';
import { mkdir, readFile, unlink, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { env } from '../config/env';

const root = path.resolve(env.UPLOAD_DIR);

/** Tipul real al imaginii, citit din conținut, nu din numele fișierului. */
export function detectImageType(buffer: Buffer): { contentType: string; extension: string } | null {
  if (buffer.length > 3 && buffer[0] === 0xff && buffer[1] === 0xd8 && buffer[2] === 0xff) {
    return { contentType: 'image/jpeg', extension: 'jpg' };
  }
  if (buffer.length > 8 && buffer.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) {
    return { contentType: 'image/png', extension: 'png' };
  }
  if (buffer.length > 12 && buffer.toString('latin1', 0, 4) === 'RIFF' && buffer.toString('latin1', 8, 12) === 'WEBP') {
    return { contentType: 'image/webp', extension: 'webp' };
  }
  if (buffer.length > 12 && buffer.toString('latin1', 4, 8) === 'ftyp' && /^(heic|heix|mif1|msf1)/.test(buffer.toString('latin1', 8, 12))) {
    return { contentType: 'image/heic', extension: 'heic' };
  }
  return null;
}

export async function saveFile(folder: string, extension: string, buffer: Buffer): Promise<string> {
  const key = `${folder}/${randomUUID()}.${extension}`;
  const target = path.join(root, key);
  await mkdir(path.dirname(target), { recursive: true });
  await writeFile(target, buffer);
  return key;
}

export const loadFile = (key: string) => readFile(path.join(root, key));

export const removeFile = (key: string) => unlink(path.join(root, key)).catch(() => undefined);
