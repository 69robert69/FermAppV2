import { mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';

process.env.DATABASE_URL ??= 'postgresql://docs:docs@localhost:5432/docs';
process.env.JWT_ACCESS_SECRET ??= 'docs-only-secret-docs-only-secret-1234';

const { createApp } = await import('../src/app');
const { buildOpenApiDocument } = await import('../src/lib/openapi');
const { openApiRegistry } = await import('../src/lib/route');

createApp();

const root = path.resolve(import.meta.dirname, '../../..');
const docs = path.join(root, 'docs');
mkdirSync(docs, { recursive: true });

writeFileSync(path.join(docs, 'openapi.json'), `${JSON.stringify(buildOpenApiDocument(), null, 2)}\n`);
const migrations = path.join(root, 'apps/api/prisma/migrations');
const schema = readdirSync(migrations, { withFileTypes: true })
  .filter((entry) => entry.isDirectory())
  .map((entry) => entry.name)
  .sort()
  .map((name) => `-- Migration: ${name}\n${readFileSync(path.join(migrations, name, 'migration.sql'), 'utf8')}`)
  .join('\n');
writeFileSync(path.join(docs, 'schema.sql'), schema);

const ROLE_SHORT: Record<string, string> = { ADMIN: 'Admin', FARM_MANAGER: 'Manager', OPERATOR: 'Operator', AGRONOMIST: 'Agronomist' };
const tags = [...new Set(openApiRegistry.map((route) => route.tag))];
const lines = [
  '# API endpoints',
  '',
  `File generated with \`npm run docs:api\`. The base path is \`/api/v1\`. There are ${openApiRegistry.length} endpoints.`,
  'Interactive Swagger documentation runs at `/api/docs`.',
  '',
];
for (const tag of tags) {
  lines.push(`## ${tag}`, '', '| Method | Path | Description | Access |', '|---|---|---|---|');
  for (const route of openApiRegistry.filter((item) => item.tag === tag)) {
    const access =
      route.roles === 'public' ? 'Public' : route.roles.length === 4 ? 'All roles' : route.roles.map((role) => ROLE_SHORT[role]).join(', ');
    lines.push(`| ${route.method.toUpperCase()} | \`${route.path}\` | ${route.summary} | ${access} |`);
  }
  lines.push('');
}
writeFileSync(path.join(docs, '02-api-endpoints.md'), lines.join('\n'));
console.log(`Documentation generated for ${openApiRegistry.length} endpoints.`);
process.exit(0);
