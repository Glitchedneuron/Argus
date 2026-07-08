/**
 * Koa middleware.
 */

import { parseTraceparent } from '../contract/ids';
import { withRequestScope, type LogContext } from '../context/manager';
import type { ArgusLogger } from '../logger';
import type { HttpMiddlewareOptions } from './express';

interface MinimalKoaContext {
  method: string;
  path: string;
  status: number;
  headers: Record<string, string | string[] | undefined>;
}

export function koaMiddleware(
  logger: ArgusLogger,
  options: HttpMiddlewareOptions = {},
): (ctx: MinimalKoaContext, next: () => Promise<unknown>) => Promise<void> {
  const accessLog = options.accessLog ?? true;
  return async (ctx, next) => {
    const trace = parseTraceparent(ctx.headers['traceparent']);
    const requestIdHeader = ctx.headers['x-request-id'];
    const seed: LogContext = {
      ...(trace ?? {}),
      ...(typeof requestIdHeader === 'string' ? { requestId: requestIdHeader } : {}),
      httpMethod: ctx.method,
      httpPath: ctx.path,
    };
    await withRequestScope(seed, async () => {
      const start = process.hrtime.bigint();
      try {
        await next();
      } finally {
        if (accessLog) {
          const durationMs = Number(process.hrtime.bigint() - start) / 1e6;
          logger.info('http.request.completed', {
            statusCode: ctx.status,
            durationMs: Math.round(durationMs * 100) / 100,
          });
        }
      }
    });
  };
}
