import { z, type ZodType } from 'zod';
import { openApiRegistry } from './route';

const toSchema = (schema: ZodType) =>
  z.toJSONSchema(schema, { io: 'input', unrepresentable: 'any', target: 'draft-2020-12' }) as Record<string, any>;

function parameters(schema: ZodType | undefined, where: 'query' | 'path') {
  if (!schema) return [];
  const json = toSchema(schema);
  const required = new Set<string>(json.required ?? []);
  return Object.entries<Record<string, unknown>>(json.properties ?? {}).map(([name, property]) => ({
    name,
    in: where,
    required: where === 'path' || required.has(name),
    schema: property,
  }));
}

const ROLE_NAMES: Record<string, string> = {
  ADMIN: 'Administrator',
  FARM_MANAGER: 'Farm Manager',
  OPERATOR: 'Machine Operator',
  AGRONOMIST: 'Agronomist Consultant',
};

export function buildOpenApiDocument() {
  const paths: Record<string, Record<string, unknown>> = {};

  for (const route of openApiRegistry) {
    const path = route.path.replace(/:(\w+)/g, '{$1}');
    const isPublic = route.roles === 'public';
    const access = isPublic
      ? 'Public'
      : `Roles: ${(route.roles as readonly string[]).map((role) => ROLE_NAMES[role]).join(', ')}`;

    const requestBody = route.file
      ? {
          required: true,
          content: {
            'multipart/form-data': {
              schema: { type: 'object', properties: { file: { type: 'string', format: 'binary' } }, required: ['file'] },
            },
          },
        }
      : route.body
        ? { required: true, content: { 'application/json': { schema: toSchema(route.body) } } }
        : undefined;

    (paths[path] ??= {})[route.method] = {
      tags: [route.tag],
      summary: route.summary,
      description: access,
      security: isPublic ? [] : [{ bearerAuth: [] }],
      parameters: [...parameters(route.params, 'path'), ...parameters(route.query, 'query')],
      ...(requestBody ? { requestBody } : {}),
      responses: {
        [route.status]: {
          description: 'Success',
          ...(route.produces ? { content: { [route.produces]: { schema: { type: 'string', format: 'binary' } } } } : {}),
        },
        400: { $ref: '#/components/responses/Error' },
        ...(isPublic ? {} : { 401: { $ref: '#/components/responses/Error' }, 403: { $ref: '#/components/responses/Error' } }),
      },
    };
  }

  return {
    openapi: '3.1.0',
    info: {
      title: 'FermApp API',
      version: '1.0.0',
      description: 'Farm management API. Successful responses have the form `{ "data": ... }`.',
    },
    servers: [{ url: '/api/v1' }],
    tags: [...new Set(openApiRegistry.map((route) => route.tag))].map((name) => ({ name })),
    paths,
    components: {
      securitySchemes: { bearerAuth: { type: 'http', scheme: 'bearer', bearerFormat: 'JWT' } },
      responses: {
        Error: {
          description: 'Error',
          content: {
            'application/json': {
              schema: {
                type: 'object',
                properties: {
                  error: {
                    type: 'object',
                    properties: {
                      code: { type: 'string' },
                      message: { type: 'string' },
                      details: {},
                    },
                    required: ['code', 'message'],
                  },
                },
              },
            },
          },
        },
      },
    },
  };
}
