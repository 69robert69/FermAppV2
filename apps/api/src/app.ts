import compression from 'compression';
import cors from 'cors';
import express, { Router } from 'express';
import { rateLimit } from 'express-rate-limit';
import helmet from 'helmet';
import swaggerUi from 'swagger-ui-express';
import { env } from './config/env';
import { buildOpenApiDocument } from './lib/openapi';
import { prisma } from './lib/prisma';
import { errorHandler, notFoundHandler } from './middleware/error';
import { aiRoutes } from './modules/ai/ai.routes';
import { authRoutes } from './modules/auth/auth.routes';
import { costRoutes } from './modules/costs/costs.routes';
import { cropRoutes } from './modules/crops/crops.routes';
import { dashboardRoutes } from './modules/dashboard/dashboard.routes';
import { economicsRoutes } from './modules/economics/economics.routes';
import { intelligenceRoutes } from './modules/intelligence/intelligence.routes';
import { iotRoutes } from './modules/iot/iot.routes';
import { irrigationRoutes } from './modules/irrigation/irrigation.routes';
import { machineRoutes } from './modules/machines/machines.routes';
import { mapRoutes } from './modules/map/map.routes';
import { notificationRoutes } from './modules/notifications/notifications.routes';
import { parcelRoutes } from './modules/parcels/parcels.routes';
import { planRoutes } from './modules/plans/plan.routes';
import { reportRoutes } from './modules/reports/reports.routes';
import { satelliteRoutes } from './modules/satellite/satellite.routes';
import { soilRoutes } from './modules/soil/soil.routes';
import { taskRoutes } from './modules/tasks/tasks.routes';
import { teamRoutes } from './modules/team/team.routes';
import { weatherRoutes } from './modules/weather/weather.routes';
import { workRoutes } from './modules/work/work.routes';

const modules = [
  { base: '/auth', routes: authRoutes },
  { base: '/team', routes: teamRoutes },
  { base: '', routes: dashboardRoutes },
  // Fixed parcel routes are registered before the :id routes of other modules.
  { base: '/parcels', routes: planRoutes },
  { base: '/parcels', routes: parcelRoutes },
  { base: '/map', routes: mapRoutes },
  { base: '/parcels/:id/soil', routes: soilRoutes },
  { base: '/parcels/:id/satellite', routes: satelliteRoutes },
  { base: '/parcels/:id', routes: intelligenceRoutes },
  { base: '', routes: cropRoutes },
  { base: '/machines', routes: machineRoutes },
  { base: '/work', routes: workRoutes },
  { base: '/tasks', routes: taskRoutes },
  { base: '', routes: irrigationRoutes },
  { base: '', routes: weatherRoutes },
  { base: '', routes: costRoutes },
  { base: '', routes: economicsRoutes },
  { base: '/reports', routes: reportRoutes },
  { base: '/ai', routes: aiRoutes },
  { base: '/iot', routes: iotRoutes },
  { base: '/notifications', routes: notificationRoutes },
];

export function createApp() {
  const app = express();
  app.set('trust proxy', 1);
  app.disable('x-powered-by');

  app.use(helmet({ contentSecurityPolicy: false }));
  app.use(cors({ origin: env.CORS_ORIGINS.split(',').map((origin) => origin.trim()) }));
  app.use(compression());
  app.use(express.json({ limit: '2mb' }));

  app.get('/health', async (_req, res) => {
    try {
      await prisma.$queryRaw`SELECT 1`;
      res.json({ status: 'ok' });
    } catch {
      res.status(503).json({ status: 'degraded' });
    }
  });

  const api = Router({ mergeParams: true });
  api.use(
    rateLimit({
      windowMs: 60_000,
      limit: env.NODE_ENV === 'test' ? 100_000 : 600,
      standardHeaders: 'draft-7',
      legacyHeaders: false,
      message: { error: { code: 'RATE_LIMITED', message: 'Too many requests. Try again shortly.' } },
    }),
  );
  for (const { base, routes } of modules) api.use(base || '/', routes.router);
  app.use('/api/v1', api);

  const document = buildOpenApiDocument();
  app.get('/api/docs.json', (_req, res) => res.json(document));
  app.use('/api/docs', swaggerUi.serve, swaggerUi.setup(document));

  app.use(notFoundHandler);
  app.use(errorHandler);
  return app;
}
