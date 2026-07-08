/**
 * HTTP transport for OTel collectors (OTLP/HTTP JSON) or any NDJSON sink.
 *
 * - Batches records and flushes on size or interval.
 * - Retries with exponential backoff + jitter.
 * - After `maxConsecutiveFailures` the transport enters DEGRADED mode: it
 *   stops issuing requests for `degradedCooldownMs` and reports failures to
 *   the pipeline, which reroutes envelopes to the in-memory fallback buffer.
 */

import { setTimeout as sleep } from 'node:timers/promises';
import { Transport, type LogEnvelope, type TransportOptions } from './base';
import { toOtlpPayload } from '../pipeline/serializer';
import type { LogRecord } from '../contract/schema';

export interface HttpTransportOptions extends TransportOptions {
  /** Collector endpoint, e.g. http://otel-collector:4318/v1/logs */
  url: string;
  /** Extra request headers (e.g. auth). */
  headers?: Record<string, string>;
  /** Send OTLP/HTTP JSON (true, default) or raw NDJSON (false). */
  otlp?: boolean;
  /** Max records per request. Default 100. */
  batchSize?: number;
  /** Max time a record waits in the batch. Default 2000 ms. */
  flushIntervalMs?: number;
  /** Retry attempts per batch. Default 3. */
  maxRetries?: number;
  /** Base backoff delay. Default 250 ms (250, 500, 1000, ...). */
  backoffBaseMs?: number;
  /** Consecutive failed batches before degrading. Default 3. */
  maxConsecutiveFailures?: number;
  /** How long to stay degraded before probing again. Default 30_000 ms. */
  degradedCooldownMs?: number;
  /** Per-request timeout. Default 5000 ms. */
  requestTimeoutMs?: number;
}

export class HttpTransport extends Transport {
  readonly #options: Required<Omit<HttpTransportOptions, 'highWaterMark' | 'headers'>> & {
    headers: Record<string, string>;
  };
  #batch: Readonly<LogRecord>[] = [];
  #timer: NodeJS.Timeout | null = null;
  #consecutiveFailures = 0;
  #degradedUntil = 0;
  #inflight: Promise<void> = Promise.resolve();

  constructor(options: HttpTransportOptions) {
    super('http', options);
    if (!options.url) throw new TypeError('HttpTransport requires a `url`');
    this.#options = {
      url: options.url,
      headers: options.headers ?? {},
      otlp: options.otlp ?? true,
      batchSize: options.batchSize ?? 100,
      flushIntervalMs: options.flushIntervalMs ?? 2000,
      maxRetries: options.maxRetries ?? 3,
      backoffBaseMs: options.backoffBaseMs ?? 250,
      maxConsecutiveFailures: options.maxConsecutiveFailures ?? 3,
      degradedCooldownMs: options.degradedCooldownMs ?? 30_000,
      requestTimeoutMs: options.requestTimeoutMs ?? 5000,
    };
  }

  get isDegraded(): boolean {
    return Date.now() < this.#degradedUntil;
  }

  protected writeEnvelope(envelope: LogEnvelope): Promise<void> {
    if (this.isDegraded) {
      // Fail fast so the pipeline redirects to the fallback buffer.
      return Promise.reject(new Error(`http transport degraded until ${new Date(this.#degradedUntil).toISOString()}`));
    }
    this.#batch.push(envelope.record);
    if (this.#batch.length >= this.#options.batchSize) {
      return this.#flushBatch();
    }
    this.#timer ??= setTimeout(() => {
      this.#timer = null;
      void this.#flushBatch().catch(() => undefined);
    }, this.#options.flushIntervalMs);
    this.#timer.unref?.();
    return Promise.resolve();
  }

  async #flushBatch(): Promise<void> {
    if (this.#timer) {
      clearTimeout(this.#timer);
      this.#timer = null;
    }
    const records = this.#batch;
    if (records.length === 0) return;
    this.#batch = [];
    // Serialize sends to preserve ordering at the collector.
    const send = this.#inflight.then(() => this.#send(records));
    this.#inflight = send.catch(() => undefined);
    return send;
  }

  async #send(records: readonly Readonly<LogRecord>[]): Promise<void> {
    const body = this.#options.otlp
      ? toOtlpPayload(records)
      : records.map((r) => JSON.stringify(r)).join('\n') + '\n';
    const contentType = this.#options.otlp ? 'application/json' : 'application/x-ndjson';

    let lastError: Error | null = null;
    for (let attempt = 0; attempt <= this.#options.maxRetries; attempt++) {
      if (attempt > 0) {
        const backoff = this.#options.backoffBaseMs * 2 ** (attempt - 1);
        await sleep(backoff + Math.floor(Math.random() * backoff * 0.25));
      }
      try {
        const response = await fetch(this.#options.url, {
          method: 'POST',
          headers: { 'content-type': contentType, ...this.#options.headers },
          body,
          signal: AbortSignal.timeout(this.#options.requestTimeoutMs),
        });
        if (response.ok) {
          this.#consecutiveFailures = 0;
          return;
        }
        lastError = new Error(`collector responded ${response.status}`);
        // 4xx (except 408/429) will not succeed on retry.
        if (response.status >= 400 && response.status < 500 && response.status !== 408 && response.status !== 429) {
          break;
        }
      } catch (err) {
        lastError = err instanceof Error ? err : new Error(String(err));
      }
    }

    this.#consecutiveFailures++;
    if (this.#consecutiveFailures >= this.#options.maxConsecutiveFailures) {
      this.#degradedUntil = Date.now() + this.#options.degradedCooldownMs;
      this.#consecutiveFailures = 0;
    }
    throw lastError ?? new Error('http transport delivery failed');
  }

  override async flush(): Promise<void> {
    await this.#flushBatch().catch(() => undefined);
    await this.#inflight;
  }
}
