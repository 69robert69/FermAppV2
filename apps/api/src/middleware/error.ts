import { Prisma } from '@prisma/client';
import type { ErrorRequestHandler, RequestHandler } from 'express';
import multer from 'multer';
import { AppError } from '../lib/errors';
import { logger } from '../lib/logger';

export const notFoundHandler: RequestHandler = (req, res) => {
  res.status(404).json({ error: { code: 'NOT_FOUND', message: `Ruta ${req.method} ${req.path} nu există.` } });
};

export const errorHandler: ErrorRequestHandler = (error, req, res, _next) => {
  if (res.headersSent) return;

  if (error instanceof AppError) {
    res.status(error.status).json({
      error: { code: error.code, message: error.message, ...(error.details ? { details: error.details } : {}) },
    });
    return;
  }
  if (error instanceof multer.MulterError) {
    const message = error.code === 'LIMIT_FILE_SIZE' ? 'Fișierul depășește limita de 20 MB.' : 'Fișier invalid.';
    res.status(400).json({ error: { code: 'BAD_UPLOAD', message } });
    return;
  }
  if (error instanceof Prisma.PrismaClientKnownRequestError) {
    if (error.code === 'P2002') {
      res.status(409).json({ error: { code: 'CONFLICT', message: 'Există deja o înregistrare cu aceste date.' } });
      return;
    }
    if (error.code === 'P2025') {
      res.status(404).json({ error: { code: 'NOT_FOUND', message: 'Înregistrarea nu a fost găsită.' } });
      return;
    }
  }
  if ((error as { type?: string }).type === 'entity.parse.failed') {
    res.status(400).json({ error: { code: 'BAD_JSON', message: 'Corpul cererii nu este JSON valid.' } });
    return;
  }
  if ((error as { type?: string }).type === 'entity.too.large') {
    res.status(413).json({ error: { code: 'TOO_LARGE', message: 'Corpul cererii este prea mare.' } });
    return;
  }

  logger.error(`Eroare neașteptată la ${req.method} ${req.path}`, error);
  res.status(500).json({ error: { code: 'INTERNAL_ERROR', message: 'A apărut o eroare internă.' } });
};
