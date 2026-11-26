import AsyncStorage from '@react-native-async-storage/async-storage';
import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';

export type CalendarView = 'DAY' | 'WEEK' | 'MONTH' | 'SEASON';

interface CalendarPrefs {
  view: CalendarView;
  /** AI suggestions the user rejected: parcel, type and day. */
  rejected: string[];
  setView: (view: CalendarView) => void;
  reject: (key: string) => void;
}

export const suggestionKey = (item: { parcelId: string | null; type: string; dueDate: string }) => `${item.parcelId ?? 'farm'}|${item.type}|${item.dueDate}`;

export const useCalendarPrefs = create<CalendarPrefs>()(
  persist(
    (set) => ({
      view: 'WEEK',
      rejected: [],
      setView: (view) => set({ view }),
      reject: (key) => set((state) => ({ rejected: [...state.rejected.slice(-199), key] })),
    }),
    { name: 'fermapp.calendar-prefs', storage: createJSONStorage(() => AsyncStorage) },
  ),
);
