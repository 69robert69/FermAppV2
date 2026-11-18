import Constants, { ExecutionEnvironment } from 'expo-constants';

/** Expo Go nu include notificările push și sarcinile de fundal. */
export const isExpoGo = Constants.executionEnvironment === ExecutionEnvironment.StoreClient;
