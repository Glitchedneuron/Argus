/**
 * Request-scoped context propagation built on AsyncLocalStorage.
 *
 * Context set via `withContext()` flows automatically through async/await,
 * Promise chains, timers, and I/O callbacks — no manual passing required.
 * Nested `withContext()` calls merge onto the parent context, so
 * service-to-service hops can layer their own attributes without losing the
 * inherited traceId/requestId.
 */

import { AsyncLocalStorage } from 'node:async_hooks';
import { newSpanId, newTraceId, normalizeSpanId, normalizeTraceId } from '../contract/ids';
import { ZERO_SPAN_ID, ZERO_TRACE_ID } from '../contract/schema';

export interface LogContext {
  traceId?: string;
  spanId?: string;
  requestId?: string;
  userId?: string | number;
  [key: string]: unknown;
}

const storage = new AsyncLocalStorage<Readonly<LogContext>>();

/** Providers registered via `registerContextProvider` are consulted on every log call. */
export type ContextProvider = () => LogContext | undefined;

const providers: ContextProvider[] = [];

/**
 * Register a custom context provider (e.g. an adapter that reads the active
 * OTel span). Providers only ADD attributes; they can never remove or
 * override values already present in the AsyncLocalStorage context, and they
 * can never touch record fields outside `context`/trace correlation.
 */
export function registerContextProvider(provider: ContextProvider): void {
  if (typeof provider !== 'function') {
    throw new TypeError('Context provider must be a function');
  }
  providers.push(provider);
}

/** Snapshot of the merged active context (providers first, ALS wins). */
export function activeContext(): Readonly<LogContext> {
  let merged: LogContext = {};
  for (const provider of providers) {
    try {
      const extra = provider();
      if (extra && typeof extra === 'object') merged = { ...merged, ...extra };
    } catch {
      // A misbehaving provider must never break logging.
    }
  }
  const scoped = storage.getStore();
  if (scoped) merged = { ...merged, ...scoped };
  return merged;
}

/**
 * Run `fn` with `context` merged onto any context inherited from the caller.
 * Returns whatever `fn` returns (sync value or Promise).
 */
export function withContext<T>(context: LogContext, fn: () => T): T {
  const parent = storage.getStore() ?? {};
  const merged = Object.freeze({ ...parent, ...context });
  return storage.run(merged, fn);
}

/**
 * Start a fresh request scope: generates requestId/traceId/spanId for any
 * that are missing, then runs `fn` inside that scope.
 */
export function withRequestScope<T>(seed: LogContext, fn: () => T): T {
  const traceId = normalizeTraceId(seed.traceId);
  const spanId = normalizeSpanId(seed.spanId);
  const scope: LogContext = {
    ...seed,
    traceId: traceId === ZERO_TRACE_ID ? newTraceId() : traceId,
    spanId: spanId === ZERO_SPAN_ID ? newSpanId() : spanId,
    requestId:
      typeof seed.requestId === 'string' && seed.requestId.length > 0
        ? seed.requestId
        : newSpanId(),
  };
  return withContext(scope, fn);
}

/** The raw AsyncLocalStorage store for advanced integrations (read-only). */
export function currentStore(): Readonly<LogContext> | undefined {
  return storage.getStore();
}
