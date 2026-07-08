/**
 * Fastify plugin (callback style, no fastify-plugin dependency).
 *
 * Register with: `app.register(argusFastifyPlugin(logger))` — or wrap with
 * fastify-plugin if you need the context to escape the encapsulation scope.
 */

import { parseTraceparent } from '../contract/ids';
import { withRequestScope, type LogContext } from '../context/manager';
import type { ArgusLogger } from '../logger';
import type { HttpMiddlewareOptions } from './express';

interface MinimalFastifyRequest {
  method: string;
  url: string;
  headers: Record<string, string | string[] | undefined>;
}

interface MinimalFastifyReply {
  statusCode: number;
  elapsedTime?: number;
}

interface MinimalFastifyInstance {
  addHook(
    name: 'onRequest',
    hook: (req: MinimalFastifyRequest, reply: MinimalFastifyReply, done: () => void) => void,
  ): void;
  addHook(
    name: 'onResponse',
    hook: (req: MinimalFastifyRequest, reply: MinimalFastifyReply, done: () => void) => void,
  ): void;
}

export function argusFastifyPlugin(logger: ArgusLogger, options: HttpMiddlewareOptions = {}) {
  const accessLog = options.accessLog ?? true;
  return function argusContext(
    instance: MinimalFastifyInstance,
    _opts: unknown,
    done: () => void,
  ): void {
    instance.addHook('onRequest', (req, _reply, hookDone) => {
      const trace = parseTraceparent(req.headers['traceparent']);
      const requestIdHeader = req.headers['x-request-id'];
      const seed: LogContext = {
        ...(trace ?? {}),
        ...(typeof requestIdHeader === 'string' ? { requestId: requestIdHeader } : {}),
        httpMethod: req.method,
        httpPath: req.url.split('?')[0],
      };
      // withRequestScope binds the ALS store to this async subtree; calling
      // done() inside keeps the rest of the lifecycle in scope.
      withRequestScope(seed, () => hookDone());
    });
    if (accessLog) {
      instance.addHook('onResponse', (_req, reply, hookDone) => {
        logger.info('http.request.completed', {
          statusCode: reply.statusCode,
          ...(reply.elapsedTime !== undefined
            ? { durationMs: Math.round(reply.elapsedTime * 100) / 100 }
            : {}),
        });
        hookDone();
      });
    }
    done();
  };
}
