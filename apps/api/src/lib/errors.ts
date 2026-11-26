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
export const unauthorized = (message = 'Authentication required.') =>
  new AppError(401, 'UNAUTHORIZED', message);
export const forbidden = (message = 'You do not have permission for this action.') =>
  new AppError(403, 'FORBIDDEN', message);
export const notFound = (what = 'Resource') => new AppError(404, 'NOT_FOUND', `${what} was not found.`);
export const conflict = (message: string) => new AppError(409, 'CONFLICT', message);
export const notConfigured = (service: string) =>
  new AppError(503, 'SERVICE_NOT_CONFIGURED', `${service} is not configured on the server.`);
export const upstreamFailed = (service: string) =>
  new AppError(502, 'UPSTREAM_FAILED', `${service} is not responding right now. Try again later.`);
