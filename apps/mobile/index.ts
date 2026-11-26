import 'react-native-gesture-handler';
import { registerRootComponent } from 'expo';
import App from './App';
import { isExpoGo } from './src/lib/runtime';

// The background task must be defined when the app loads.
if (!isExpoGo) require('./src/offline/background');

registerRootComponent(App);
