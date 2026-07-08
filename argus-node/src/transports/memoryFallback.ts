/**
 * In-memory fallback buffer.
 *
 * When a primary transport rejects an envelope (disk full, collector down,
 * degraded mode), the pipeline parks the serialized line here instead of
 * losing it. A bounded ring keeps memory flat; overflow increments the drop
 * counter so capacity issues are visible in health metrics. Buffered lines
 * are replayed to a transport when it recovers, and drained on shutdown.
 */

import { RingBuffer } from '../pipeline/ringBuffer';

export class FallbackBuffer {
  readonly #ring: RingBuffer<string>;

  constructor(capacity = 4096) {
    this.#ring = new RingBuffer<string>(capacity, 'drop-oldest');
  }

  get size(): number {
    return this.#ring.size;
  }

  get droppedCount(): number {
    return this.#ring.droppedCount;
  }

  park(line: string): void {
    this.#ring.push(line);
  }

  /** Remove up to `max` buffered lines for replay. */
  take(max: number): string[] {
    const out: string[] = [];
    this.#ring.drainInto(out, max);
    return out;
  }
}
