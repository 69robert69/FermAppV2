import { askCopilot, type TaskChange, type TaskProposal } from '@fermapp/domain';
import { applyTaskChange, buildFarmSnapshot, createTaskFromProposal, undoAction, type CopilotAction } from '../../firebase/copilot';
import { useSync } from '../../offline/sync';
import { canPlan, useAuth } from '../../store/auth';
import { GeminiQuotaError, geminiConfigured, phraseWithGemini } from './gemini';
import { newMessageId, useCopilot, type AutomationMode, type ChatMessage } from './store';

/** Operators cannot create or change tasks, so for them the copilot only recommends. */
export function effectiveMode(): AutomationMode {
  return canPlan(useAuth.getState().role) ? useCopilot.getState().mode : 'RECOMMEND';
}

const errorText = (error: unknown) => (error instanceof Error ? error.message : 'The action failed.');

export async function createProposal(messageId: string, proposal: TaskProposal, auto = false) {
  const store = useCopilot.getState();
  try {
    const action = await createTaskFromProposal(proposal, auto);
    store.log(action);
    store.setItem(messageId, proposal.key, { state: 'done', actionId: action.id, taskId: action.taskId });
    return action;
  } catch (error) {
    store.setItem(messageId, proposal.key, { state: 'failed', error: errorText(error) });
    return null;
  }
}

export async function applyChange(messageId: string, change: TaskChange, auto = false) {
  const store = useCopilot.getState();
  try {
    const action = await applyTaskChange(change, auto);
    store.log(action);
    store.setItem(messageId, change.key, { state: 'done', actionId: action.id });
    return action;
  } catch (error) {
    store.setItem(messageId, change.key, { state: 'failed', error: errorText(error) });
    return null;
  }
}

export async function undo(action: CopilotAction) {
  await undoAction(action);
  useCopilot.getState().markUndone(action.id);
}

/** Answers from the farm data; Gemini only rewords it when configured and online. */
export async function askQuestion(question: string) {
  const store = useCopilot.getState();
  const history = store.messages;
  store.push({ id: newMessageId(), role: 'user', at: new Date().toISOString(), text: question, items: {} });
  const id = newMessageId();
  try {
    const snapshot = await buildFarmSnapshot();
    const turn = askCopilot(question, snapshot, store.memory);
    let answer = turn.answer;
    let source: ChatMessage['source'] = 'local';
    let note: string | undefined;
    if (geminiConfigured && useSync.getState().online) {
      try {
        answer = await phraseWithGemini(question, snapshot, turn.answer, history);
        source = 'gemini';
      } catch (error) {
        note = error instanceof GeminiQuotaError ? 'The free Gemini quota is used up for now; this answer was computed on the phone.' : undefined;
      }
    }
    useCopilot.getState().setMemory(turn.memory);
    useCopilot.getState().push({ id, role: 'assistant', at: new Date().toISOString(), answer, source, note, items: {} });

    const mode = effectiveMode();
    if (answer.execute && answer.proposals.length) {
      // "Add them to To-Do": the request is the approval, so the tasks are created now (logged and reversible).
      if (mode === 'RECOMMEND') {
        useCopilot.getState().push({ id: newMessageId(), role: 'assistant', at: new Date().toISOString(), text: canPlan(useAuth.getState().role) ? 'AI Task Automation is set to “Recommendation only”, so I did not create the tasks. Change it in Settings, or add them from the To-Do tab.' : 'Only managers and agronomists can create tasks; ask one of them to add these.', items: {} });
      } else {
        for (const proposal of answer.proposals) await createProposal(id, proposal, false);
      }
    } else if (mode === 'AUTO') {
      // Fully automatic: create the suggested tasks and apply the changes right away. Each one is logged and reversible.
      for (const proposal of answer.proposals) await createProposal(id, proposal, true);
      for (const change of answer.changes) await applyChange(id, change, true);
    }
  } catch (error) {
    useCopilot.getState().push({ id, role: 'assistant', at: new Date().toISOString(), text: `I could not read the farm data: ${errorText(error)}`, items: {} });
  }
}
