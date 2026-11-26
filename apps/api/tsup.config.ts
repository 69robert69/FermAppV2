import { defineConfig } from 'tsup';

export default defineConfig({
  entry: ['src/server.ts'],
  format: ['esm'],
  target: 'node22',
  platform: 'node',
  sourcemap: true,
  clean: true,
  // The domain package is TypeScript source, so it is bundled into the final build.
  noExternal: ['@fermapp/domain'],
});
