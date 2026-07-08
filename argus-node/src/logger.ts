/**
 * ArgusLogger — the only way to emit logs.
 *
 * The emit path:
 *
 *   level gate → carrier build (pooled) → beforeLog hooks (context/metadata
 *   only) → PII masking → contract validation (zod) → deep-freeze →
 *   canonical serialization → ring buffer → async transport drain
 *
 * Enforcement properties:
 *   - the instance is frozen; all state lives in true #private fields
 *   - level, format, and masking rules are fixed at construction
 *   - records failing the contract are REJECTED (counted + diagnostic), never written
 *   - transports receive only frozen records and finished lines
 *   - a logging failure never throws into application code
 */

import {
  LEVEL_RANK,
  SEVERITY_NUMBER,
  type LogLevel,
} from './contract/levels';
import {
  ZERO_SPAN_ID,
  ZERO_TRACE_ID,
  sealCandidate,
  type AttributeBag,
  type ErrorEnvelope,
  type LogRecord,
} from './contract/schema';
import { deepFreeze } from './contract/freeze';
import { normalizeSpanId, normalizeTraceId } from './contract/ids';
import { activeContext } from './context/manager';
import { PiiMaskingEngine } from './masking/engine';
import { serializeMaskingOptions } from './masking/serializeOptions';
import { serializeRecord } from './pipeline/serializer';
import { Pipeline } from './pipeline/pipeline';
import { WorkerPool } from './pipeline/workerPool';
import { ObjectPool } from './pipeline/objectPool';
import type { RawCarrier } from './pipeline/maskWorker';
import { HealthRegistry } from './health';
import type { ResolvedConfig } from './config';
import type { Transport } from './transports/base';

export interface LogExtras {
  metadata?: AttributeBag;
  error?: Error | ErrorEnvelope;
}

function toErrorEnvelope(error: Error | ErrorEnvelope): ErrorEnvelope {
  if (error instanceof Error) {
    const envelope: ErrorEnvelope = {
      type: error.name || 'Error',
      message: error.message ?? '',
    };
    if (typeof error.stack === 'string') envelope.stacktrace = error.stack;
    return envelope;
  }
  return error;
}

function newCarrier(): RawCarrier {
  return {
    timestamp: '',
    level: 'INFO',
    severityNumber: 9,
    traceId: ZERO_TRACE_ID,
    spanId: ZERO_SPAN_ID,
    service: '',
    environment: 'development',
    message: '',
    context: {},
    metadata: {},
    maskedFields: [],
  };
}

function resetCarrier(carrier: RawCarrier): void {
  carrier.timestamp = '';
  carrier.message = '';
  carrier.context = {};
  carrier.metadata = {};
  carrier.maskedFields = [];
  delete carrier.error;
}

export class ArgusLogger {
  readonly #config: ResolvedConfig;
  readonly #pipeline: Pipeline;
  readonly #masking: PiiMaskingEngine;
  readonly #health: HealthRegistry;
  readonly #carrierPool: ObjectPool<RawCarrier>;
  readonly #minRank: number;
  readonly #boundContext: Readonly<AttributeBag>;
  readonly #workerPool: WorkerPool | null;
  readonly #workerBatchSize: number;
  #workerBatch: RawCarrier[] = [];
  #workerFlushScheduled = false;
  /** Chained tail preserving submission order of worker batches. */
  #workerTail: Promise<void> = Promise.resolve();
  #selfMetricsTimer: NodeJS.Timeout | null = null;
  #shutdownStarted = false;
  #rejectionGuard = false;

  /** @internal — construct via createLogger()/ArgusLoggerFactory only. */
  constructor(
    config: ResolvedConfig,
    transports: readonly Transport[],
    boundContext: Readonly<AttributeBag> = Object.freeze({}),
    shared?: {
      pipeline: Pipeline;
      masking: PiiMaskingEngine;
      health: HealthRegistry;
      workerPool: WorkerPool | null;
    },
  ) {
    this.#config = config;
    this.#minRank = LEVEL_RANK[config.level];
    this.#boundContext = boundContext;
    this.#workerBatchSize = config.workers.batchSize;

    if (shared) {
      this.#pipeline = shared.pipeline;
      this.#masking = shared.masking;
      this.#health = shared.health;
      this.#workerPool = shared.workerPool;
    } else {
      this.#pipeline = new Pipeline(transports, {
        capacity: config.buffer.capacity,
        overflowPolicy: config.buffer.overflowPolicy,
        fallbackCapacity: config.buffer.fallbackCapacity,
      });
      this.#masking = new PiiMaskingEngine(config.masking);
      const pipeline = this.#pipeline;
      const masking = this.#masking;
      this.#workerPool = config.workers.enabled
        ? new WorkerPool({
            ...(config.workers.poolSize !== undefined
              ? { poolSize: config.workers.poolSize }
              : {}),
            maskingOptions: serializeMaskingOptions(config.masking),
          })
        : null;
      const workerPool = this.#workerPool;
      this.#health = new HealthRegistry({
        queueDepth: () => pipeline.queueDepth,
        queueCapacity: () => pipeline.queueCapacity,
        queueDropped: () => pipeline.droppedCount,
        maskingActions: () => masking.totalMasked,
        fallbackBuffered: () => pipeline.fallback.size,
        fallbackDropped: () => pipeline.fallback.droppedCount,
        workerPendingBatches: () => workerPool?.pendingBatches ?? 0,
      });
      this.#pipeline.bindHealth(this.#health);
      if (config.selfMetrics.port !== undefined) {
        this.#health.startMetricsServer(config.selfMetrics.port, config.selfMetrics.host);
      }
      if (config.selfMetrics.enabled) {
        this.#selfMetricsTimer = setInterval(
          () => this.#emitSelfMetrics(),
          config.selfMetrics.intervalMs,
        );
        this.#selfMetricsTimer.unref();
      }
    }

    this.#carrierPool = new ObjectPool<RawCarrier>(newCarrier, resetCarrier);
    Object.freeze(this);
  }

  // ---------------------------------------------------------------- levels

  get level(): LogLevel {
    return this.#config.level;
  }

  get service(): string {
    return this.#config.service;
  }

  get health(): HealthRegistry {
    return this.#health;
  }

  isLevelEnabled(level: LogLevel): boolean {
    return LEVEL_RANK[level] >= this.#minRank;
  }

  debug(message: string, context?: AttributeBag, extras?: LogExtras): void {
    this.#emit('DEBUG', message, context, extras);
  }

  info(message: string, context?: AttributeBag, extras?: LogExtras): void {
    this.#emit('INFO', message, context, extras);
  }

  warn(message: string, context?: AttributeBag, extras?: LogExtras): void {
    this.#emit('WARN', message, context, extras);
  }

  error(
    message: string,
    contextOrError?: AttributeBag | Error,
    extras?: LogExtras,
  ): void {
    if (contextOrError instanceof Error) {
      this.#emit('ERROR', message, undefined, { ...extras, error: contextOrError });
    } else {
      this.#emit('ERROR', message, contextOrError, extras);
    }
  }

  fatal(
    message: string,
    contextOrError?: AttributeBag | Error,
    extras?: LogExtras,
  ): void {
    if (contextOrError instanceof Error) {
      this.#emit('FATAL', message, undefined, { ...extras, error: contextOrError });
    } else {
      this.#emit('FATAL', message, contextOrError, extras);
    }
  }

  /**
   * Derive a child logger with additional bound context (merged under both
   * ambient AsyncLocalStorage context and per-call context). Shares the
   * pipeline, masking engine, and health registry with its parent.
   */
  child(boundContext: AttributeBag): ArgusLogger {
    return new ArgusLogger(
      this.#config,
      [],
      Object.freeze({ ...this.#boundContext, ...boundContext }),
      {
        pipeline: this.#pipeline,
        masking: this.#masking,
        health: this.#health,
        workerPool: this.#workerPool,
      },
    );
  }

  // ------------------------------------------------------------- emit path

  #emit(
    level: LogLevel,
    message: string,
    context: AttributeBag | undefined,
    extras: LogExtras | undefined,
  ): void {
    try {
      if (LEVEL_RANK[level] < this.#minRank) {
        this.#health.countSuppressed();
        return;
      }
      this.#health.countEmitted();

      const ambient = activeContext();
      const carrier = this.#carrierPool.acquire();
      carrier.timestamp = new Date().toISOString();
      carrier.level = level;
      carrier.severityNumber = SEVERITY_NUMBER[level];
      carrier.traceId = normalizeTraceId(ambient['traceId']);
      carrier.spanId = normalizeSpanId(ambient['spanId']);
      carrier.service = this.#config.service;
      carrier.environment = this.#config.environment;
      carrier.message = typeof message === 'string' ? message : String(message);

      // Ambient context attributes (traceId/spanId promoted to top level).
      // undefined values are skipped — they'd be dropped by JSON anyway and
      // must not fail the whole record.
      const mergedContext: AttributeBag = { ...this.#boundContext };
      for (const [key, value] of Object.entries(ambient)) {
        if (key === 'traceId' || key === 'spanId' || value === undefined) continue;
        mergedContext[key] = value;
      }
      if (context) {
        for (const [key, value] of Object.entries(context)) {
          if (value !== undefined) mergedContext[key] = value;
        }
      }
      carrier.context = mergedContext;
      const metadata: AttributeBag = {};
      if (extras?.metadata) {
        for (const [key, value] of Object.entries(extras.metadata)) {
          if (value !== undefined) metadata[key] = value;
        }
      }
      carrier.metadata = metadata;
      carrier.maskedFields = [];
      if (extras?.error) {
        carrier.error = toErrorEnvelope(extras.error);
      } else {
        delete carrier.error;
      }

      this.#applyHooks(carrier);

      if (this.#workerPool) {
        this.#enqueueForWorkers(carrier);
      } else {
        this.#finishSync(carrier);
      }
    } catch {
      // A logging failure must never crash the application.
      this.#health.countRejected();
    }
  }

  /** Hooks may transform message/context/metadata — nothing else. */
  #applyHooks(carrier: RawCarrier): void {
    for (const hook of this.#config.hooks.beforeLog) {
      try {
        const draft = {
          message: carrier.message,
          context: carrier.context,
          metadata: carrier.metadata,
          level: carrier.level as LogLevel,
          service: carrier.service,
          environment: carrier.environment as LogRecord['environment'],
        };
        const patch = hook(draft);
        carrier.message = typeof draft.message === 'string' ? draft.message : carrier.message;
        carrier.context = draft.context;
        carrier.metadata = draft.metadata;
        if (patch && typeof patch === 'object') {
          if (typeof patch.message === 'string') carrier.message = patch.message;
          if (patch.context && typeof patch.context === 'object') carrier.context = patch.context;
          if (patch.metadata && typeof patch.metadata === 'object') carrier.metadata = patch.metadata;
        }
      } catch {
        // A misbehaving hook must never block the record.
      }
    }
  }

  #finishSync(carrier: RawCarrier): void {
    const { maskedFields } = this.#masking.maskRecord(carrier);
    carrier.maskedFields = maskedFields;
    const sealed = sealCandidate(carrier);
    this.#carrierPool.release(carrier);
    if (!sealed.ok) {
      this.#health.countRejected();
      this.#emitRejectionDiagnostic(sealed.issues);
      return;
    }
    this.#pipeline.enqueue({ record: sealed.record, line: serializeRecord(sealed.record) });
  }

  #enqueueForWorkers(carrier: RawCarrier): void {
    // Carriers headed to a worker are structured-cloned; do not pool them.
    this.#workerBatch.push(carrier);
    if (this.#workerBatch.length >= this.#workerBatchSize) {
      this.#flushWorkerBatch();
    } else if (!this.#workerFlushScheduled) {
      this.#workerFlushScheduled = true;
      setImmediate(() => {
        this.#workerFlushScheduled = false;
        this.#flushWorkerBatch();
      });
    }
  }

  #flushWorkerBatch(): void {
    if (this.#workerBatch.length === 0 || !this.#workerPool) return;
    const batch = this.#workerBatch;
    this.#workerBatch = [];
    const promise = this.#workerPool.process(batch);
    // Chain onto the tail so batches are enqueued in submission order.
    this.#workerTail = this.#workerTail.then(async () => {
      try {
        const results = await promise;
        for (const result of results) {
          if (result.ok) {
            const record = deepFreeze(result.record);
            this.#pipeline.enqueue({ record, line: result.line });
          } else {
            this.#health.countRejected();
            this.#emitRejectionDiagnostic(result.issues);
          }
        }
      } catch {
        // Worker failure: fall back to the synchronous path for this batch.
        for (const carrier of batch) {
          this.#finishSync(carrier);
        }
      }
    });
  }

  /** Report a contract rejection as a valid ERROR record (recursion-guarded). */
  #emitRejectionDiagnostic(issues: readonly string[]): void {
    if (this.#rejectionGuard) return;
    this.#rejectionGuard = true;
    try {
      const carrier = this.#carrierPool.acquire();
      carrier.timestamp = new Date().toISOString();
      carrier.level = 'ERROR';
      carrier.severityNumber = SEVERITY_NUMBER.ERROR;
      carrier.traceId = ZERO_TRACE_ID;
      carrier.spanId = ZERO_SPAN_ID;
      carrier.service = this.#config.service;
      carrier.environment = this.#config.environment;
      carrier.message = 'Log record rejected: contract violation';
      carrier.context = {};
      carrier.metadata = { 'argus.rejection.issues': issues.slice(0, 16) as string[] };
      carrier.maskedFields = [];
      delete carrier.error;
      this.#finishSync(carrier);
    } catch {
      // Diagnostics are best-effort.
    } finally {
      this.#rejectionGuard = false;
    }
  }

  #emitSelfMetrics(): void {
    const snap = this.#health.snapshot();
    this.#emit('DEBUG', 'argus.logger.health', undefined, {
      metadata: { 'argus.health': { ...snap } },
    });
  }

  // ------------------------------------------------------------- lifecycle

  /** Wait until every queued record has been handed to every transport. */
  async flush(): Promise<void> {
    this.#flushWorkerBatch();
    await this.#workerTail;
    await this.#pipeline.awaitIdle();
  }

  /** Flush everything, close transports, stop workers and timers. */
  async shutdown(): Promise<void> {
    if (this.#shutdownStarted) {
      await this.#pipeline.awaitIdle();
      return;
    }
    this.#shutdownStarted = true;
    if (this.#selfMetricsTimer) clearInterval(this.#selfMetricsTimer);
    this.#flushWorkerBatch();
    await this.#workerTail;
    await this.#pipeline.shutdown();
    await this.#workerPool?.close();
    await this.#health.stopMetricsServer();
  }

  /** @internal test/observability access to the pipeline. */
  get pipeline(): Pipeline {
    return this.#pipeline;
  }
}
