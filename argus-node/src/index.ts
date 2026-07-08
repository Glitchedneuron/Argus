/**
 * argus-logging-node — public API surface.
 *
 * Deliberately absent from this file (enforcement by omission):
 *   - the serializer / formatting layer (`pipeline/serializer`)
 *   - the pipeline, ring buffer, worker pool internals
 *   - any way to register formatters or change the level at runtime
 */

// Construction
export { createLogger, ArgusLoggerFactory } from './factory';
export { ArgusLogger, type LogExtras } from './logger';
export { type ArgusConfigInput, type BeforeLogHook, type LogDraft, ConfigurationError } from './config';

// Contract (types + validation errors; the schema itself is read-only)
export {
  type LogRecord,
  type AttributeBag,
  type ErrorEnvelope,
  type Environment,
  ContractViolationError,
  ENVIRONMENTS,
} from './contract/schema';
export { LOG_LEVELS, SEVERITY_NUMBER, type LogLevel } from './contract/levels';
export { newTraceId, newSpanId, parseTraceparent } from './contract/ids';

// Context propagation
export {
  withContext,
  withRequestScope,
  registerContextProvider,
  activeContext,
  type LogContext,
  type ContextProvider,
} from './context/manager';

// Transports (plugin surface: subclass Transport)
export {
  Transport,
  type LogEnvelope,
  type TransportOptions,
} from './transports/base';
export { StdoutTransport, type StdoutTransportOptions } from './transports/stdout';
export { FileTransport, type FileTransportOptions } from './transports/file';
export { HttpTransport, type HttpTransportOptions } from './transports/http';

// PII masking (options types only; the engine is internal)
export {
  type MaskingOptions,
  type CustomPattern,
} from './masking/engine';

// Middleware
export { expressMiddleware, type HttpMiddlewareOptions } from './middleware/express';
export { argusFastifyPlugin } from './middleware/fastify';
export { koaMiddleware } from './middleware/koa';

// Enforcement utilities
export { enforceConsoleContract } from './enforce/console';

// Observability
export { HealthRegistry, type HealthSnapshot } from './health';
