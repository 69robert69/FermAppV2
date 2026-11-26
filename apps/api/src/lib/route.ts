import { Prisma, type Role } from '@prisma/client';
import { Router, type Request, type RequestHandler, type Response } from 'express';
import multer from 'multer';
import { z, type ZodType } from 'zod';
import { AppError, badRequest, conflict, forbidden, unauthorized } from './errors';
import { prisma } from './prisma';
import { verifyAccessToken, type AuthContext } from './tokens';

export const ROLES = {
  all: ['ADMIN', 'FARM_MANAGER', 'OPERATOR', 'AGRONOMIST'],
  managers: ['ADMIN', 'FARM_MANAGER'],
  planners: ['ADMIN', 'FARM_MANAGER', 'AGRONOMIST'],
  field: ['ADMIN', 'FARM_MANAGER', 'OPERATOR'],
  admin: ['ADMIN'],
} as const satisfies Record<string, readonly Role[]>;

export const MAX_UPLOAD_BYTES = 20 * 1024 * 1024;
const STALE_RESERVATION_MS = 2 * 60_000;
const upload = multer({ storage: multer.memoryStorage(), limits: { fileSize: MAX_UPLOAD_BYTES, files: 1 } });

export class FileResult {
  constructor(
    readonly buffer: Buffer,
    readonly contentType: string,
    readonly filename: string,
    readonly inline = false,
  ) {}
}

interface Spec<B extends ZodType, Q extends ZodType, P extends ZodType> {
  summary: string;
  /** `public` leaves the route without JWT authentication. */
  roles: readonly Role[] | 'public';
  body?: B;
  query?: Q;
  params?: P;
  status?: number;
  file?: boolean;
  produces?: string;
  /** Rate limiter applied only to this route. */
  limiter?: RequestHandler;
}

export interface Ctx<B, Q, P> {
  body: B;
  query: Q;
  params: P;
  auth: AuthContext;
  file?: Express.Multer.File;
  req: Request;
}

type Infer<T> = T extends ZodType ? z.output<T> : undefined;

interface OpenApiEntry {
  method: string;
  path: string;
  tag: string;
  summary: string;
  roles: readonly Role[] | 'public';
  body?: ZodType;
  query?: ZodType;
  params?: ZodType;
  status: number;
  file: boolean;
  produces?: string;
}

export const openApiRegistry: OpenApiEntry[] = [];

export const idParams = z.object({ id: z.uuid() });

function parse<T extends ZodType>(schema: T, value: unknown, where: string): z.output<T> {
  const result = schema.safeParse(value);
  if (result.success) return result.data;
  throw badRequest(
    `Invalid data in ${where}.`,
    result.error.issues.map((issue) => ({ field: issue.path.join('.'), message: issue.message })),
  );
}

function authenticate(req: Request): AuthContext {
  const header = req.headers.authorization;
  if (!header?.startsWith('Bearer ')) throw unauthorized();
  return verifyAccessToken(header.slice(7));
}

/** Replays the saved response when the app resends an operation from the offline queue. */
async function reserveIdempotency(req: Request, res: Response, auth: AuthContext): Promise<string | null | 'replayed'> {
  const key = req.header('idempotency-key');
  if (!key) return null;
  if (key.length > 128) throw badRequest('The idempotency key is too long.');
  const route = `${req.method} ${req.originalUrl.split('?')[0]}`;
  try {
    const record = await prisma.idempotencyRecord.create({ data: { userId: auth.userId, key, route } });
    return record.id;
  } catch (error) {
    if (!(error instanceof Prisma.PrismaClientKnownRequestError) || error.code !== 'P2002') throw error;
  }
  const existing = await prisma.idempotencyRecord.findUnique({
    where: { userId_key: { userId: auth.userId, key } },
  });
  if (existing && existing.route !== route) throw conflict('The idempotency key was used for a different operation.');
  if (!existing || existing.statusCode === 0) {
    // A stale reservation means the server stopped during processing, so the operation is retried.
    if (existing && Date.now() - existing.createdAt.getTime() > STALE_RESERVATION_MS) {
      await prisma.idempotencyRecord.update({ where: { id: existing.id }, data: { createdAt: new Date() } });
      return existing.id;
    }
    throw conflict('The operation is already being processed.');
  }
  res.setHeader('Idempotency-Replayed', 'true');
  if (existing.statusCode === 204) res.status(204).end();
  else res.status(existing.statusCode).json(existing.response);
  return 'replayed';
}

export class ApiRouter {
  readonly router = Router({ mergeParams: true });

  constructor(
    private readonly base: string,
    private readonly tag: string,
  ) {}

  private add<B extends ZodType, Q extends ZodType, P extends ZodType>(
    method: 'get' | 'post' | 'patch' | 'put' | 'delete',
    path: string,
    spec: Spec<B, Q, P>,
    handler: (ctx: Ctx<Infer<B>, Infer<Q>, Infer<P>>) => Promise<unknown>,
  ) {
    const fullPath = `${this.base}${path === '/' ? '' : path}`;
    openApiRegistry.push({
      method,
      path: fullPath,
      tag: this.tag,
      summary: spec.summary,
      roles: spec.roles,
      body: spec.body,
      query: spec.query,
      params: spec.params,
      status: spec.status ?? 200,
      file: spec.file ?? false,
      produces: spec.produces,
    });

    const middlewares: RequestHandler[] = spec.limiter ? [spec.limiter] : [];

    // Authentication runs before the upload, so anonymous files never reach memory.
    const guard: RequestHandler = (req, res, next) => {
      if (spec.roles !== 'public') {
        const auth = authenticate(req);
        if (!spec.roles.includes(auth.role)) throw forbidden();
        res.locals.auth = auth;
      }
      next();
    };
    middlewares.push(guard);
    if (spec.file) middlewares.push(upload.single('file'));

    this.router[method](path, ...middlewares, async (req: Request, res: Response) => {
      const auth = res.locals.auth as AuthContext;
      const ctx = {
        body: spec.body ? parse(spec.body, req.body, 'the request body') : undefined,
        query: spec.query ? parse(spec.query, req.query, 'the query parameters') : undefined,
        params: spec.params ? parse(spec.params, req.params, 'the URL') : undefined,
        auth,
        file: req.file,
        req,
      } as Ctx<Infer<B>, Infer<Q>, Infer<P>>;

      const mutates = method !== 'get' && spec.roles !== 'public';
      const reservation = mutates ? await reserveIdempotency(req, res, auth) : null;
      if (reservation === 'replayed') return;

      let result: unknown;
      try {
        result = await handler(ctx);
      } catch (error) {
        if (reservation) await prisma.idempotencyRecord.delete({ where: { id: reservation } }).catch(() => {});
        throw error;
      }

      if (result instanceof FileResult) {
        const disposition = result.inline ? 'inline' : 'attachment';
        res.setHeader('Content-Type', result.contentType);
        res.setHeader(
          'Content-Disposition',
          `${disposition}; filename*=UTF-8''${encodeURIComponent(result.filename)}`,
        );
        res.send(result.buffer);
        return;
      }

      const status = result === undefined ? 204 : (spec.status ?? 200);
      const payload = result === undefined ? null : { data: result };
      if (reservation) {
        await prisma.idempotencyRecord.update({
          where: { id: reservation },
          data: { statusCode: status, response: (payload ?? Prisma.JsonNull) as Prisma.InputJsonValue },
        });
      }
      if (status === 204) res.status(204).end();
      else res.status(status).json(payload);
    });
  }

  get<Q extends ZodType, P extends ZodType>(
    path: string,
    spec: Spec<ZodType<undefined>, Q, P>,
    handler: (ctx: Ctx<undefined, Infer<Q>, Infer<P>>) => Promise<unknown>,
  ) {
    this.add('get', path, spec, handler as never);
  }

  post<B extends ZodType, Q extends ZodType, P extends ZodType>(
    path: string,
    spec: Spec<B, Q, P>,
    handler: (ctx: Ctx<Infer<B>, Infer<Q>, Infer<P>>) => Promise<unknown>,
  ) {
    this.add('post', path, { status: 201, ...spec }, handler);
  }

  patch<B extends ZodType, Q extends ZodType, P extends ZodType>(
    path: string,
    spec: Spec<B, Q, P>,
    handler: (ctx: Ctx<Infer<B>, Infer<Q>, Infer<P>>) => Promise<unknown>,
  ) {
    this.add('patch', path, spec, handler);
  }

  delete<Q extends ZodType, P extends ZodType>(
    path: string,
    spec: Spec<ZodType<undefined>, Q, P>,
    handler: (ctx: Ctx<undefined, Infer<Q>, Infer<P>>) => Promise<unknown>,
  ) {
    this.add('delete', path, spec, handler as never);
  }
}

export { AppError };
