/**
 * Fixed-capacity ring buffer used to absorb log bursts between the
 * synchronous emit path and the async transport drain loop.
 *
 * Preallocated slots — no per-push allocation, no unbounded memory growth.
 * On overflow the configured policy applies:
 *   - "drop-oldest" (default): evict the oldest queued entry (newest data wins)
 *   - "drop-newest": reject the incoming entry (oldest data wins)
 * Dropped counts are tracked so capacity problems are observable.
 */

export type OverflowPolicy = 'drop-oldest' | 'drop-newest';

export class RingBuffer<T> {
  readonly #slots: (T | undefined)[];
  readonly #capacity: number;
  readonly #policy: OverflowPolicy;
  #head = 0; // next read position
  #size = 0;
  #dropped = 0;

  constructor(capacity: number, policy: OverflowPolicy = 'drop-oldest') {
    if (!Number.isInteger(capacity) || capacity < 1) {
      throw new RangeError(`Ring buffer capacity must be a positive integer, got ${capacity}`);
    }
    this.#capacity = capacity;
    this.#policy = policy;
    this.#slots = new Array<T | undefined>(capacity).fill(undefined);
  }

  get size(): number {
    return this.#size;
  }

  get capacity(): number {
    return this.#capacity;
  }

  get droppedCount(): number {
    return this.#dropped;
  }

  get isFull(): boolean {
    return this.#size === this.#capacity;
  }

  /** Returns true when the item was queued, false when it was dropped. */
  push(item: T): boolean {
    if (this.#size === this.#capacity) {
      this.#dropped++;
      if (this.#policy === 'drop-newest') return false;
      // drop-oldest: advance head over the evicted slot.
      this.#slots[this.#head] = undefined;
      this.#head = (this.#head + 1) % this.#capacity;
      this.#size--;
    }
    const tail = (this.#head + this.#size) % this.#capacity;
    this.#slots[tail] = item;
    this.#size++;
    return true;
  }

  /** Remove and return the oldest item, or undefined when empty. */
  shift(): T | undefined {
    if (this.#size === 0) return undefined;
    const item = this.#slots[this.#head];
    this.#slots[this.#head] = undefined;
    this.#head = (this.#head + 1) % this.#capacity;
    this.#size--;
    return item;
  }

  /** Drain up to `max` items into `out`; returns the number drained. */
  drainInto(out: T[], max: number): number {
    let count = 0;
    while (count < max && this.#size > 0) {
      out.push(this.shift() as T);
      count++;
    }
    return count;
  }
}
