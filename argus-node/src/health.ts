/**
 * Logger self-observability.
 *
 * Counters for everything that can go wrong (drops, rejections, transport
 * failures) plus queue and memory gauges. Exposed three ways:
 *   1. `logger.health.snapshot()` — programmatic access
 *   2. Periodic self-log records (metadata under `argus.health`)
 *   3. Optional HTTP metrics endpoint (JSON + Prometheus text format)
 */

import { createServer, type Server } from 'node:http';

export interface HealthSnapshot {
  emitted: number;
  written: number;
  rejected: number;
  dropped: number;
  suppressedBelowLevel: number;
  transportErrors: number;
  maskingActions: number;
  fallbackBuffered: number;
  fallbackDropped: number;
  queueDepth: number;
  queueCapacity: number;
  workerPendingBatches: number;
  rssBytes: number;
  heapUsedBytes: number;
  uptimeSeconds: number;
}

export class HealthRegistry {
  #emitted = 0;
  #written = 0;
  #rejected = 0;
  #suppressed = 0;
  #transportErrors = 0;
  #gauges: {
    queueDepth: () => number;
    queueCapacity: () => number;
    queueDropped: () => number;
    maskingActions: () => number;
    fallbackBuffered: () => number;
    fallbackDropped: () => number;
    workerPendingBatches: () => number;
  };
  #server: Server | null = null;

  constructor(gauges: HealthRegistry['gauges']) {
    this.#gauges = gauges;
  }

  /** Late-bound gauge sources (queue, masking engine, fallback buffer). */
  get gauges(): {
    queueDepth: () => number;
    queueCapacity: () => number;
    queueDropped: () => number;
    maskingActions: () => number;
    fallbackBuffered: () => number;
    fallbackDropped: () => number;
    workerPendingBatches: () => number;
  } {
    return this.#gauges;
  }

  countEmitted(): void {
    this.#emitted++;
  }

  countWritten(count = 1): void {
    this.#written += count;
  }

  countRejected(): void {
    this.#rejected++;
  }

  countSuppressed(): void {
    this.#suppressed++;
  }

  countTransportError(): void {
    this.#transportErrors++;
  }

  snapshot(): HealthSnapshot {
    const memory = process.memoryUsage();
    return {
      emitted: this.#emitted,
      written: this.#written,
      rejected: this.#rejected,
      dropped: this.#gauges.queueDropped(),
      suppressedBelowLevel: this.#suppressed,
      transportErrors: this.#transportErrors,
      maskingActions: this.#gauges.maskingActions(),
      fallbackBuffered: this.#gauges.fallbackBuffered(),
      fallbackDropped: this.#gauges.fallbackDropped(),
      queueDepth: this.#gauges.queueDepth(),
      queueCapacity: this.#gauges.queueCapacity(),
      workerPendingBatches: this.#gauges.workerPendingBatches(),
      rssBytes: memory.rss,
      heapUsedBytes: memory.heapUsed,
      uptimeSeconds: Math.round(process.uptime()),
    };
  }

  toPrometheus(): string {
    const snap = this.snapshot();
    const lines: string[] = [];
    for (const [key, value] of Object.entries(snap)) {
      const metric = `argus_logger_${key.replace(/([A-Z])/g, '_$1').toLowerCase()}`;
      lines.push(`# TYPE ${metric} gauge`, `${metric} ${value}`);
    }
    return lines.join('\n') + '\n';
  }

  /** Start a tiny HTTP server exposing GET /metrics (JSON) and /metrics/prometheus. */
  startMetricsServer(port: number, host = '127.0.0.1'): Server {
    if (this.#server) return this.#server;
    this.#server = createServer((req, res) => {
      if (req.method === 'GET' && req.url === '/metrics') {
        res.writeHead(200, { 'content-type': 'application/json' });
        res.end(JSON.stringify(this.snapshot()));
      } else if (req.method === 'GET' && req.url === '/metrics/prometheus') {
        res.writeHead(200, { 'content-type': 'text/plain; version=0.0.4' });
        res.end(this.toPrometheus());
      } else {
        res.writeHead(404, { 'content-type': 'application/json' });
        res.end('{"error":"not found"}');
      }
    });
    this.#server.unref();
    this.#server.listen(port, host);
    return this.#server;
  }

  async stopMetricsServer(): Promise<void> {
    const server = this.#server;
    this.#server = null;
    if (server) {
      await new Promise<void>((resolve) => server.close(() => resolve()));
    }
  }
}
