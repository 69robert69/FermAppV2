import { z } from 'zod';

const optional = z
  .string()
  .optional()
  .transform((value) => (value && value.trim() !== '' ? value.trim() : undefined));

const schema = z.object({
  NODE_ENV: z.enum(['development', 'test', 'production']).default('development'),
  PORT: z.coerce.number().int().positive().default(4000),
  CORS_ORIGINS: z.string().default('http://localhost:8081'),
  DATABASE_URL: z.string().min(1),
  REDIS_URL: optional,
  JWT_ACCESS_SECRET: z.string().min(32, 'JWT_ACCESS_SECRET trebuie să aibă minimum 32 de caractere.'),
  JWT_ACCESS_TTL_SECONDS: z.coerce.number().int().positive().default(900),
  REFRESH_TOKEN_TTL_DAYS: z.coerce.number().int().positive().default(30),
  OPENWEATHER_API_KEY: optional,
  SENTINEL_CLIENT_ID: optional,
  SENTINEL_CLIENT_SECRET: optional,
  SENTINEL_TOKEN_URL: z
    .string()
    .url()
    .default('https://identity.dataspace.copernicus.eu/auth/realms/CDSE/protocol/openid-connect/token'),
  SENTINEL_API_URL: z.string().url().default('https://sh.dataspace.copernicus.eu'),
  ANTHROPIC_API_KEY: optional,
  ANTHROPIC_MODEL: z.string().default('claude-opus-5-5'),
  MQTT_URL: optional,
  MQTT_USERNAME: optional,
  MQTT_PASSWORD: optional,
  EXPO_ACCESS_TOKEN: optional,
  UPLOAD_DIR: z.string().default('.tmp/uploads'),
  JOBS_ENABLED: z
    .string()
    .default('true')
    .transform((value) => value === 'true'),
});

const parsed = schema.safeParse(process.env);
if (!parsed.success) {
  const details = parsed.error.issues.map((i) => `${i.path.join('.')}: ${i.message}`).join('\n');
  throw new Error(`Configurație invalidă:\n${details}`);
}

export const env = parsed.data;
