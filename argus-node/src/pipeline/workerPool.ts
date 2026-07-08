/**
 * Worker-thread pool that offloads masking + validation + serialization.
 *
 * Batches are dispatched round-robin; results are consumed strictly in
 * submission order (the pipeline awaits batch promises FIFO), so log
 * ordering is preserved while CPU work runs in parallel off the main
 * thread. Enable via `workers: { enabled: true }` for high-throughput
 * services — the sync path is faster below ~5k msg/s.
 */

import { Worker } from 'node:worker_threads';
import { cpus } from 'node:os';
import { join } from 'node:path';
import type { SerializedMaskingOptions } from '../masking/serializeOptions';
import type {
  RawCarrier,
  WorkerBatchRequest,
  WorkerBatchResponse,
  WorkerItemResult,
} from './maskWorker';

export interface WorkerPoolOptions {
  poolSize?: number;
  maskingOptions: SerializedMaskingOptions;
}

interface PendingBatch {
  resolve: (results: WorkerItemResult[]) => void;
  reject: (err: Error) => void;
}

export class WorkerPool {
  readonly #workers: Worker[] = [];
  readonly #pending = new Map<number, PendingBatch>();
  #nextBatchId = 1;
  #nextWorker = 0;
  #closed = false;

  constructor(options: WorkerPoolOptions) {
    const size = Math.max(1, Math.min(options.poolSize ?? cpus().length - 1, 8));
    // Compiled worker lives next to this file in dist/.
    const workerPath = join(__dirname, 'maskWorker.js');
    for (let i = 0; i < size; i++) {
      const worker = new Worker(workerPath, { workerData: options.maskingOptions });
      worker.unref();
      worker.on('message', (response: WorkerBatchResponse) => {
        const pending = this.#pending.get(response.batchId);
        if (pending) {
          this.#pending.delete(response.batchId);
          pending.resolve(response.results);
        }
      });
      worker.on('error', (err) => {
        // Fail all batches routed to this worker; the logger falls back to
        // the synchronous path for them.
        for (const [id, pending] of this.#pending) {
          this.#pending.delete(id);
          pending.reject(err);
        }
      });
      this.#workers.push(worker);
    }
  }

  get size(): number {
    return this.#workers.length;
  }

  get pendingBatches(): number {
    return this.#pending.size;
  }

  process(carriers: RawCarrier[]): Promise<WorkerItemResult[]> {
    if (this.#closed || this.#workers.length === 0) {
      return Promise.reject(new Error('worker pool is closed'));
    }
    const batchId = this.#nextBatchId++;
    const worker = this.#workers[this.#nextWorker] as Worker;
    this.#nextWorker = (this.#nextWorker + 1) % this.#workers.length;
    return new Promise<WorkerItemResult[]>((resolve, reject) => {
      this.#pending.set(batchId, { resolve, reject });
      worker.postMessage({ batchId, carriers } satisfies WorkerBatchRequest);
    });
  }

  async close(): Promise<void> {
    this.#closed = true;
    await Promise.allSettled(this.#workers.map((worker) => worker.terminate()));
    this.#workers.length = 0;
    for (const [id, pending] of this.#pending) {
      this.#pending.delete(id);
      pending.reject(new Error('worker pool closed'));
    }
  }
}
