/**
 * The async delivery pipeline.
 *
 * `enqueue()` is synchronous and allocation-light: the finished envelope
 * goes into a preallocated ring buffer and the caller returns immediately —
 * the application thread NEVER waits on I/O. A drain loop (scheduled with
 * setImmediate) fans envelopes out to every transport, respecting Writable
 * backpressure per transport. Failed deliveries are parked in the in-memory
 * fallback buffer and can be replayed when a transport recovers.
 */

import { once } from 'node:events';
import { RingBuffer, type OverflowPolicy } from './ringBuffer';
import { FallbackBuffer } from '../transports/memoryFallback';
import type { LogEnvelope, Transport } from '../transports/base';
import type { HealthRegistry } from '../health';

export interface PipelineOptions {
  capacity: number;
  overflowPolicy: OverflowPolicy;
  fallbackCapacity: number;
  /** Envelopes moved per drain tick. */
  drainBatchSize?: number;
}

export class Pipeline {
  readonly #queue: RingBuffer<LogEnvelope>;
  readonly #transports: readonly Transport[];
  readonly #fallback: FallbackBuffer;
  readonly #drainBatchSize: number;
  #health: HealthRegistry | null = null;
  #draining = false;
  #scheduled = false;
  #closed = false;
  #idleResolvers: (() => void)[] = [];

  constructor(transports: readonly Transport[], options: PipelineOptions) {
    this.#queue = new RingBuffer<LogEnvelope>(options.capacity, options.overflowPolicy);
    this.#transports = transports;
    this.#fallback = new FallbackBuffer(options.fallbackCapacity);
    this.#drainBatchSize = options.drainBatchSize ?? 256;
    for (const transport of transports) {
      transport.onDeliveryFailure((envelope) => {
        this.#health?.countTransportError();
        this.#fallback.park(envelope.line);
      });
    }
  }

  bindHealth(health: HealthRegistry): void {
    this.#health = health;
  }

  get queueDepth(): number {
    return this.#queue.size;
  }

  get queueCapacity(): number {
    return this.#queue.capacity;
  }

  get droppedCount(): number {
    return this.#queue.droppedCount;
  }

  get fallback(): FallbackBuffer {
    return this.#fallback;
  }

  /** Non-blocking: queue the envelope and schedule a drain tick. */
  enqueue(envelope: LogEnvelope): boolean {
    if (this.#closed) return false;
    const accepted = this.#queue.push(envelope);
    this.#schedule();
    return accepted;
  }

  #schedule(): void {
    if (this.#scheduled || this.#draining) return;
    this.#scheduled = true;
    setImmediate(() => {
      this.#scheduled = false;
      void this.#drain();
    });
  }

  async #drain(): Promise<void> {
    if (this.#draining) return;
    this.#draining = true;
    try {
      const batch: LogEnvelope[] = [];
      while (this.#queue.size > 0) {
        batch.length = 0;
        this.#queue.drainInto(batch, this.#drainBatchSize);
        for (const envelope of batch) {
          await this.#deliver(envelope);
        }
      }
    } finally {
      this.#draining = false;
      if (this.#queue.size > 0) {
        this.#schedule();
      } else {
        const resolvers = this.#idleResolvers;
        this.#idleResolvers = [];
        for (const resolve of resolvers) resolve();
      }
    }
  }

  async #deliver(envelope: LogEnvelope): Promise<void> {
    for (const transport of this.#transports) {
      if (transport.writableEnded || transport.destroyed) continue;
      const accepted = transport.write(envelope);
      if (!accepted) {
        // Backpressure: wait for this transport before feeding it more.
        await once(transport, 'drain').catch(() => undefined);
      }
    }
    this.#health?.countWritten();
  }

  /**
   * Replay fallback-buffered lines through a recovered transport. Returns
   * the number of lines handed to the transport.
   */
  replayFallback(transport: Transport, max = 1000): number {
    const lines = this.#fallback.take(max);
    let replayed = 0;
    for (const line of lines) {
      try {
        transport.write({ line, record: JSON.parse(line) });
        replayed++;
      } catch {
        this.#fallback.park(line);
        break;
      }
    }
    return replayed;
  }

  /** Resolve once the queue is fully drained. */
  async awaitIdle(): Promise<void> {
    if (this.#queue.size === 0 && !this.#draining) return;
    await new Promise<void>((resolve) => {
      this.#idleResolvers.push(resolve);
      this.#schedule();
    });
  }

  /** Drain everything, then flush and close every transport. */
  async shutdown(): Promise<void> {
    if (this.#closed) return;
    await this.awaitIdle();
    this.#closed = true;
    for (const transport of this.#transports) {
      try {
        await transport.flush();
        await transport.close();
      } catch {
        this.#health?.countTransportError();
      }
    }
  }
}
