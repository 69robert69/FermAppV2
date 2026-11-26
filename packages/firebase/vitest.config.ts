import path from 'node:path';
import { defineConfig } from 'vitest/config';

const mobile = path.resolve(import.meta.dirname, '../../apps/mobile/src');
const stub = (name: string) => path.resolve(import.meta.dirname, 'test/stubs', name);

// The app code runs in Node, with native modules replaced by test variants.
export default defineConfig({
  resolve: {
    alias: [
      { find: 'expo-sqlite', replacement: stub('sqlite.ts') },
      { find: 'expo-file-system', replacement: stub('fileSystem.ts') },
      { find: 'expo-image-manipulator', replacement: stub('imageManipulator.ts') },
      { find: 'expo-print', replacement: stub('print.ts') },
      { find: '@react-native-community/netinfo', replacement: stub('netinfo.ts') },
      { find: '@react-native-async-storage/async-storage', replacement: stub('asyncStorage.ts') },
      { find: /^.*\/api\/queryClient$/, replacement: stub('queryClient.ts') },
      { find: /^.*\/api\/client$/, replacement: stub('apiClient.ts') },
      { find: /^.*\/store\/auth$/, replacement: stub('authStore.ts') },
      { find: /^\.\/app$/, replacement: stub('firebaseApp.ts') },
      { find: '@mobile', replacement: mobile },
    ],
  },
  test: { testTimeout: 30_000, hookTimeout: 30_000, fileParallelism: false },
});
