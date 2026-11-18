import * as BackgroundTask from 'expo-background-task';
import * as TaskManager from 'expo-task-manager';
import { useAuth } from '../store/auth';
import { flushOutbox } from './sync';

const SYNC_TASK = 'fermapp-background-sync';
const INTERVAL_MINUTES = 15;

TaskManager.defineTask(SYNC_TASK, async () => {
  try {
    await useAuth.getState().restore();
    await flushOutbox();
    return BackgroundTask.BackgroundTaskResult.Success;
  } catch {
    return BackgroundTask.BackgroundTaskResult.Failed;
  }
});

export async function registerBackgroundSync() {
  const status = await BackgroundTask.getStatusAsync();
  if (status !== BackgroundTask.BackgroundTaskStatus.Available) return;
  if (!(await TaskManager.isTaskRegisteredAsync(SYNC_TASK))) {
    await BackgroundTask.registerTaskAsync(SYNC_TASK, { minimumInterval: INTERVAL_MINUTES });
  }
}
