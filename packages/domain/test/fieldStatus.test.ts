import { describe, expect, it } from 'vitest';
import { fieldStatus, summarizeFieldStatus, taskForFinishedWork, type FieldTask } from '../src';

const now = new Date(2026, 4, 12, 10, 0);
const day = (offset: number) => new Date(2026, 4, 12 + offset, 12).toISOString();
const task = (id: string, offset: number, status: FieldTask['status'] = 'TODO', type = 'FERTILIZATION'): FieldTask => ({ id, title: id, type, status, dueDate: day(offset) });

describe('field status', () => {
  it('does not turn green after one operation while more work is scheduled', () => {
    const status = fieldStatus({ tasks: [task('fertilising', 0, 'DONE'), task('herbicide', 1), task('monitoring', 2)], activeWork: 0, now });
    expect(status.state).toBe('SCHEDULED');
    expect(status.badge).toBe('2 scheduled activities');
    expect(status.completionScore).toBe(33);
  });

  it('turns green when everything planned is done', () => {
    const status = fieldStatus({ tasks: [task('a', -1, 'DONE'), task('b', 0, 'DONE'), task('c', 1, 'DONE')], activeWork: 0, now });
    expect(status.state).toBe('COMPLETE');
    expect(status.badge).toBe('All work completed');
    expect(status.completionScore).toBe(100);
  });

  it('is green with nothing planned in the active period', () => {
    const status = fieldStatus({ tasks: [task('far', 30)], activeWork: 0, now });
    expect(status.state).toBe('COMPLETE');
    expect(status.later).toBe(1);
    expect(fieldStatus({ tasks: [], activeWork: 0, now }).completionScore).toBeNull();
  });

  it('ranks overdue, active, due today and scheduled', () => {
    expect(fieldStatus({ tasks: [task('late', -2), task('today', 0)], activeWork: 1, now }).state).toBe('OVERDUE');
    expect(fieldStatus({ tasks: [task('today', 0)], activeWork: 1, now }).state).toBe('ACTIVE');
    expect(fieldStatus({ tasks: [task('today', 0)], activeWork: 0, now }).state).toBe('DUE_TODAY');
    expect(fieldStatus({ tasks: [task('tomorrow', 1)], activeWork: 0, now }).badge).toBe('Work scheduled tomorrow');
  });

  it('summarises the farm', () => {
    const summary = summarizeFieldStatus([
      { parcelId: 'a', name: 'North', status: fieldStatus({ tasks: [task('x', 0, 'DONE')], activeWork: 0, now }) },
      { parcelId: 'b', name: 'South', status: fieldStatus({ tasks: [task('y', -1), task('z', 3)], activeWork: 0, now }) },
    ]);
    expect(summary.complete).toBe(1);
    expect(summary.overdue).toBe(1);
    expect(summary.next7Days).toBe(1);
    expect(summary.averageScore).toBe(50);
    expect(summary.mostAdvanced[0]!.name).toBe('North');
    expect(summary.urgent[0]!.name).toBe('South');
  });

  it('links a finished operation to the task it carries out', () => {
    const tasks = [
      { id: 'later', status: 'TODO', type: 'FERTILIZATION', dueDate: day(10) },
      { id: 'soon', status: 'TODO', type: 'FERTILIZATION', dueDate: day(2) },
      { id: 'other', status: 'TODO', type: 'HARVEST', dueDate: day(0) },
    ];
    expect(taskForFinishedWork(tasks, 'FERTILIZING', now)?.id).toBe('soon');
    expect(taskForFinishedWork(tasks, 'SOWING', now)).toBeNull();
  });
});
