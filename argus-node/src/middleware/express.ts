/**
 * Express middleware.
 *
 * Opens a request-scoped AsyncLocalStorage context for every request:
 * traceId/spanId from the W3C `traceparent` header (or freshly generated),
 * requestId from `x-request-id` (or generated). Every log emitted anywhere
 * in the request's async call graph carries these automatically. Optionally
 * emits an access log on response finish.
 */

import type { IncomingMessage, ServerResponse } from 'node:http';
import { parseTraceparent } from '../contract/ids';
import { withRequestScope, type LogContext } from '../context/manager';
import type { ArgusLogger } from '../logger';

export interface HttpMiddlewareOptions {
  /** Emit an INFO access log when the response finishes. Default true. */
  accessLog?: boolean;
  /** Additional context derived from the request. */
  contextFromRequest?: (req: IncomingMessage) => LogContext;
}

type NextFunction = (err?: unknown) => void;

export function expressMiddleware(
  logger: ArgusLogger,
  options: HttpMiddlewareOptions = {},
): (req: IncomingMessage, res: ServerResponse, next: NextFunction) => void {
  const accessLog = options.accessLog ?? true;
  return (req, res, next) => {
    const trace = parseTraceparent(req.headers['traceparent']);
    const requestIdHeader = req.headers['x-request-id'];
    const seed: LogContext = {
      ...(trace ?? {}),
      ...(typeof requestIdHeader === 'string' ? { requestId: requestIdHeader } : {}),
      httpMethod: req.method ?? 'GET',
      httpPath: (req.url ?? '/').split('?')[0],
      ...(options.contextFromRequest ? options.contextFromRequest(req) : {}),
    };
    withRequestScope(seed, () => {
      if (accessLog) {
        const start = process.hrtime.bigint();
        res.on('finish', () => {
          const durationMs = Number(process.hrtime.bigint() - start) / 1e6;
          logger.info('http.request.completed', {
            statusCode: res.statusCode,
            durationMs: Math.round(durationMs * 100) / 100,
          });
        });
      }
      next();
    });
  };
}
