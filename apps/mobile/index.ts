import 'react-native-gesture-handler';
import { registerRootComponent } from 'expo';
import App from './App';
import { isExpoGo } from './src/lib/runtime';

// Sarcina de fundal trebuie definită la încărcarea aplicației.
if (!isExpoGo) require('./src/offline/background');

registerRootComponent(App);
