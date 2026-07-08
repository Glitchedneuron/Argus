/**
 * Worker-thread entry point.
 *
 * Receives batches of raw log carriers, runs the expensive part of the
 * pipeline off the main thread — PII masking, contract validation, and
 * serialization — and returns finished lines plus the validated plain
 * record (the main thread deep-freezes it before any transport sees it).
 */

import { parentPort, workerData } from 'node:worker_threads';
import { PiiMaskingEngine } from '../masking/engine';
import { reviveMaskingOptions, type SerializedMaskingOptions } from '../masking/serializeOptions';
import { sealCandidate, type LogRecord } from '../contract/schema';
import { serializeRecord } from './serializer';

export interface RawCarrier {
  timestamp: string;
  level: string;
  severityNumber: number;
  traceId: string;
  spanId: string;
  service: string;
  environment: string;
  message: string;
  context: Record<string, unknown>;
  metadata: Record<string, unknown>;
  maskedFields: string[];
  error?: { type: string; message: string; stacktrace?: string | undefined };
}

export interface WorkerBatchRequest {
  batchId: number;
  carriers: RawCarrier[];
}

export type WorkerItemResult =
  | { ok: true; record: LogRecord; line: string; maskedCount: number }
  | { ok: false; issues: string[] };

export interface WorkerBatchResponse {
  batchId: number;
  results: WorkerItemResult[];
}

if (parentPort) {
  const engine = new PiiMaskingEngine(
    reviveMaskingOptions(workerData as SerializedMaskingOptions),
  );

  parentPort.on('message', (request: WorkerBatchRequest) => {
    const results: WorkerItemResult[] = request.carriers.map((carrier) => {
      try {
        const { maskedFields } = engine.maskRecord(carrier);
        carrier.maskedFields = maskedFields;
        const sealed = sealCandidate(carrier);
        if (!sealed.ok) {
          return { ok: false as const, issues: sealed.issues };
        }
        return {
          ok: true as const,
          record: sealed.record as LogRecord,
          line: serializeRecord(sealed.record),
          maskedCount: maskedFields.length,
        };
      } catch (err) {
        return { ok: false as const, issues: [err instanceof Error ? err.message : String(err)] };
      }
    });
    parentPort?.postMessage({ batchId: request.batchId, results } satisfies WorkerBatchResponse);
  });
}
