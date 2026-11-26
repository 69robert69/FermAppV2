import { Platform, Vibration } from 'react-native';

/**
 * Short haptic cues for the wheel. Android honours short durations; on iOS the
 * Vibration API only plays a long buzz, so it stays silent there. Swap these
 * bodies for `expo-haptics` (impactAsync / selectionAsync) once it is installed.
 */
export const haptics = {
  /** The wheel appeared. */
  open() {
    if (Platform.OS === 'android') Vibration.vibrate(18);
  },
  /** The focused slice changed while dragging. */
  tick() {
    if (Platform.OS === 'android') Vibration.vibrate(6);
  },
  /** An action was chosen. */
  confirm() {
    if (Platform.OS === 'android') Vibration.vibrate(12);
  },
};
