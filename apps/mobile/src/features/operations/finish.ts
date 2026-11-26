import { fieldStatus, taskForFinishedWork } from '@fermapp/domain';
import { apiSend } from '../../api/client';
import { queryClient } from '../../api/queryClient';
import type { Parcel, Task, TimedRun } from '../../api/types';
import { WORK_TYPES } from '../../lib/format';
import { runProgressAt } from '../../lib/hooks';
import { mapOverviewKey } from '../map/api';
import { announceCompletion } from './store';

/** A run is active only while it is running or paused and has not reached 100%. */
export const isRunActive = (run: TimedRun, now = new Date()) =>
  (run.status === 'RUNNING' || run.status === 'PAUSED') && !runProgressAt(run, now).finished;

const requested = new Set<string>();

/**
 * The machine reached 100%: the run is closed right away instead of waiting for
 * the server's next pass, so the tractor is free again at once. The message
 * says whether the parcel still has work planned.
 */
export function finishOperation(work: TimedRun & { type: string; machine?: { name: string } | null }, parcel: Pick<Parcel, 'id' | 'name'>) {
  if (requested.has(work.id)) return;
  requested.add(work.id);

  const tasks = (queryClient.getQueryData<Task[]>(['tasks']) ?? []).filter((task) => task.parcel?.id === parcel.id);
  const linked = taskForFinishedWork(tasks, work.type, new Date());
  const rest = fieldStatus({
    tasks: tasks.filter((task) => task.id !== linked?.id),
    activeWork: 0,
  });
  const done = `${WORK_TYPES[work.type] ?? 'The operation'} of parcel ${parcel.name} has been completed.`;
  announceCompletion(
    work.id,
    rest.pending > 0 ? `${done} ${rest.pending} ${rest.pending === 1 ? 'activity is' : 'activities are'} still scheduled.` : `${done} All planned work is complete.`,
  );

  // Operators close their own runs and managers any run; others leave it to the server.
  void apiSend('POST', `/work/${work.id}/complete`)
    .catch(() => undefined)
    .finally(() => {
      for (const queryKey of [['work'], ['tasks'], ['dashboard'], ['machines'], ['economics'], ['intelligence'], mapOverviewKey]) {
        void queryClient.invalidateQueries({ queryKey });
      }
    });
}
