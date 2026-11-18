import { defineConfig } from 'tsup';

export default defineConfig({
  entry: ['src/server.ts'],
  format: ['esm'],
  target: 'node22',
  platform: 'node',
  sourcemap: true,
  clean: true,
  // Domeniul este sursă TypeScript, deci se include în pachetul final.
  noExternal: ['@fermapp/domain'],
});
