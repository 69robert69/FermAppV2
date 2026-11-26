import { EMPTY_MEMORY, type CopilotAnswer, type CopilotMemory } from '@fermapp/domain';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';
import type { CopilotAction } from '../../firebase/copilot';

/** "AI Task Automation" in Settings. */
export type AutomationMode = 'RECOMMEND' | 'ASK' | 'AUTO';

export const AUTOMATION_OPTIONS: { value: AutomationMode; label: string; hint: string }[] = [
  { value: 'RECOMMEND', label: 'Recommendation only', hint: 'The copilot suggests tasks; you create them yourself in To-Do.' },
  { value: 'ASK', label: 'Ask before creating', hint: 'Each suggested task or change waits for your approval.' },
  { value: 'AUTO', label: 'Fully automatic', hint: 'The copilot creates, moves and reprioritizes tasks itself. Every action is logged and can be undone.' },
];

/** What happened to a suggested task or change shown in the chat. */
export interface ItemState {
  state: 'done' | 'undone' | 'failed';
  actionId?: string;
  /** The created task, for follow-up planning. */
  taskId?: string;
  error?: string;
}

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  at: string;
  text?: string;
  answer?: CopilotAnswer;
  /** Where the wording came from: computed on the phone, or phrased by Gemini over the same data. */
  source?: 'local' | 'gemini';
  note?: string;
  /** Keyed by proposal or change key. */
  items: Record<string, ItemState>;
}

const MAX_MESSAGES = 40;
const MAX_ACTIONS = 200;

interface CopilotState {
  mode: AutomationMode;
  messages: ChatMessage[];
  memory: CopilotMemory;
  actions: CopilotAction[];
  setMode: (mode: AutomationMode) => void;
  push: (message: ChatMessage) => void;
  setItem: (messageId: string, key: string, item: ItemState) => void;
  setMemory: (memory: CopilotMemory) => void;
  log: (action: CopilotAction) => void;
  markUndone: (actionId: string) => void;
  clear: () => void;
}

export const useCopilot = create<CopilotState>()(
  persist(
    (set) => ({
      mode: 'ASK',
      messages: [],
      memory: EMPTY_MEMORY,
      actions: [],
      setMode: (mode) => set({ mode }),
      push: (message) => set((state) => ({ messages: [...state.messages, message].slice(-MAX_MESSAGES) })),
      setItem: (messageId, key, item) =>
        set((state) => ({ messages: state.messages.map((message) => (message.id === messageId ? { ...message, items: { ...message.items, [key]: item } } : message)) })),
      setMemory: (memory) => set({ memory }),
      log: (action) => set((state) => ({ actions: [action, ...state.actions].slice(0, MAX_ACTIONS) })),
      markUndone: (actionId) =>
        set((state) => ({
          actions: state.actions.map((action) => (action.id === actionId ? { ...action, undoneAt: new Date().toISOString() } : action)),
          messages: state.messages.map((message) => ({
            ...message,
            items: Object.fromEntries(Object.entries(message.items).map(([key, item]) => [key, item.actionId === actionId ? { ...item, state: 'undone' as const } : item])),
          })),
        })),
      clear: () => set({ messages: [], memory: EMPTY_MEMORY }),
    }),
    { name: 'fermapp.copilot', storage: createJSONStorage(() => AsyncStorage), version: 1 },
  ),
);

export const newMessageId = () => `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 7)}`;
