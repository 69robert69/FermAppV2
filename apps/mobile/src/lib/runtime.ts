import Constants, { ExecutionEnvironment } from 'expo-constants';

/** Expo Go does not include push notifications and background tasks. */
export const isExpoGo = Constants.executionEnvironment === ExecutionEnvironment.StoreClient;
