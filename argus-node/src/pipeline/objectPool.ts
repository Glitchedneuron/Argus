/**
 * Minimal object pool for the hot path.
 *
 * The emit path builds one "carrier" object per log call before validation.
 * Pooling those carriers (and the batch arrays used by the drain loop)
 * keeps steady-state allocation near zero and GC pauses short at high
 * throughput. Frozen, validated records are NEVER pooled — immutability is
 * part of the contract.
 */

export class ObjectPool<T> {
  readonly #factory: () => T;
  readonly #reset: (item: T) => void;
  readonly #free: T[] = [];
  readonly #maxIdle: number;
  #created = 0;

  constructor(factory: () => T, reset: (item: T) => void, maxIdle = 1024) {
    this.#factory = factory;
    this.#reset = reset;
    this.#maxIdle = maxIdle;
  }

  get createdCount(): number {
    return this.#created;
  }

  get idleCount(): number {
    return this.#free.length;
  }

  acquire(): T {
    const item = this.#free.pop();
    if (item !== undefined) return item;
    this.#created++;
    return this.#factory();
  }

  release(item: T): void {
    if (this.#free.length >= this.#maxIdle) return; // let GC take it
    this.#reset(item);
    this.#free.push(item);
  }
}
