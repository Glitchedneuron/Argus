/**
 * Transport plugin contract.
 *
 * Transports are the ONLY extension point that touches output, and they are
 * handed a finished, immutable envelope:
 *
 *   - `record` — the validated, deep-frozen LogRecord (read-only by construction)
 *   - `line`   — the canonical NDJSON serialization produced by the private
 *                formatting layer
 *
 * A transport decides WHERE bytes go, never WHAT they look like. There is no
 * formatter hook, no serializer option, and mutating `record` throws in
 * strict mode because it is frozen.
 *
 * Transports extend `stream.Writable` (object mode) so the pipeline gets
 * real backpressure: `write()` returning false pauses the drain loop until
 * 'drain' fires. Delivery failures never error the stream (that would
 * destroy it) — they are absorbed, counted, and reported to the pipeline's
 * failure handler, which parks the line in the fallback buffer.
 */

import { Writable } from 'node:stream';
import { once } from 'node:events';
import type { LogRecord } from '../contract/schema';

export interface LogEnvelope {
  readonly record: Readonly<LogRecord>;
  readonly line: string;
}

export type DeliveryFailureHandler = (envelope: LogEnvelope, error: Error) => void;

export interface TransportOptions {
  /** Object-mode high-water mark before backpressure kicks in. */
  highWaterMark?: number;
}

export abstract class Transport extends Writable {
  public readonly name: string;
  #errorCount = 0;
  #failureHandler: DeliveryFailureHandler | null = null;

  protected constructor(name: string, options: TransportOptions = {}) {
    super({ objectMode: true, highWaterMark: options.highWaterMark ?? 1024 });
    this.name = name;
  }

  get errorCount(): number {
    return this.#errorCount;
  }

  /** Installed by the pipeline; not part of the public plugin surface. */
  onDeliveryFailure(handler: DeliveryFailureHandler): void {
    this.#failureHandler = handler;
  }

  override _write(
    envelope: LogEnvelope,
    _encoding: BufferEncoding,
    callback: (error?: Error | null) => void,
  ): void {
    const fail = (err: unknown): void => {
      this.#errorCount++;
      const error = err instanceof Error ? err : new Error(String(err));
      try {
        this.#failureHandler?.(envelope, error);
      } catch {
        // The failure handler itself must never break the stream.
      }
      callback(); // never propagate: an errored Writable is a destroyed Writable
    };
    try {
      const result = this.writeEnvelope(envelope);
      if (result instanceof Promise) {
        result.then(() => callback(), fail);
      } else {
        callback();
      }
    } catch (err) {
      fail(err);
    }
  }

  /** Deliver one envelope. Throw / reject to signal delivery failure. */
  protected abstract writeEnvelope(envelope: LogEnvelope): Promise<void> | void;

  /** Push any internal batch/buffer to the destination. */
  async flush(): Promise<void> {
    // default: nothing buffered
  }

  /** Flush and release resources. Called exactly once during shutdown. */
  async close(): Promise<void> {
    await this.flush();
    if (!this.writableEnded) {
      this.end();
      await once(this, 'finish').catch(() => undefined);
    }
  }
}

/** Helper for transports writing to a byte stream with backpressure. */
export async function writeWithBackpressure(
  stream: NodeJS.WritableStream,
  chunk: string,
): Promise<void> {
  if (!stream.write(chunk)) {
    await once(stream as unknown as NodeJS.EventEmitter, 'drain');
  }
}
