import mqtt, { type MqttClient } from 'mqtt';
import { env } from '../../config/env';
import { logger } from '../../lib/logger';
import { authenticateDeviceKey, ingestTelemetry, telemetrySchema } from './iot.service';

const TOPIC = 'fermapp/telemetry';

let client: MqttClient | null = null;

/** The MQTT message has the form { "deviceKey": "...", "readings": [...] }. */
export function startMqttBridge() {
  if (!env.MQTT_URL) return;
  client = mqtt.connect(env.MQTT_URL, { username: env.MQTT_USERNAME, password: env.MQTT_PASSWORD });
  client.on('connect', () => client?.subscribe(TOPIC, { qos: 1 }));
  client.on('error', (error) => logger.warn('MQTT bridge', error.message));
  client.on('message', async (_topic, payload) => {
    try {
      const message = JSON.parse(payload.toString()) as { deviceKey?: string };
      const device = await authenticateDeviceKey(String(message.deviceKey ?? ''));
      const telemetry = telemetrySchema.safeParse(message);
      if (device && telemetry.success) await ingestTelemetry(device, telemetry.data);
      else logger.warn('MQTT message rejected');
    } catch (error) {
      logger.warn('Invalid MQTT message', (error as Error).message);
    }
  });
}

export const stopMqttBridge = () => client?.end();
