const baseUrl = (process.env.EXPO_PUBLIC_API_URL ?? 'http://10.0.2.2:4000').replace(/\/$/, '');

export const API_ORIGIN = baseUrl;
export const API_URL = `${baseUrl}/api/v1`;
