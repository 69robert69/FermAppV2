export class AppError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly details?: unknown,
  ) {
    super(message);
  }
}

export const badRequest = (message: string, details?: unknown) =>
  new AppError(400, 'BAD_REQUEST', message, details);
export const unauthorized = (message = 'Autentificare necesară.') =>
  new AppError(401, 'UNAUTHORIZED', message);
export const forbidden = (message = 'Nu ai permisiunea pentru această acțiune.') =>
  new AppError(403, 'FORBIDDEN', message);
export const notFound = (what = 'Resursa') => new AppError(404, 'NOT_FOUND', `${what} nu a fost găsită.`);
export const conflict = (message: string) => new AppError(409, 'CONFLICT', message);
export const notConfigured = (service: string) =>
  new AppError(503, 'SERVICE_NOT_CONFIGURED', `${service} nu este configurat pe server.`);
export const upstreamFailed = (service: string) =>
  new AppError(502, 'UPSTREAM_FAILED', `${service} nu răspunde momentan. Încearcă din nou mai târziu.`);
