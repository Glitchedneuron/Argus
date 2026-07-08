/**
 * W3C trace-context id helpers.
 */

import { randomBytes } from 'node:crypto';
import { TRACE_ID_PATTERN, SPAN_ID_PATTERN, ZERO_TRACE_ID, ZERO_SPAN_ID } from './schema';

export function newTraceId(): string {
  return randomBytes(16).toString('hex');
}

export function newSpanId(): string {
  return randomBytes(8).toString('hex');
}

export function normalizeTraceId(value: unknown): string {
  if (typeof value === 'string' && TRACE_ID_PATTERN.test(value)) return value;
  return ZERO_TRACE_ID;
}

export function normalizeSpanId(value: unknown): string {
  if (typeof value === 'string' && SPAN_ID_PATTERN.test(value)) return value;
  return ZERO_SPAN_ID;
}

/**
 * Parse a W3C `traceparent` header: `00-<32 hex>-<16 hex>-<2 hex>`.
 * Returns null when the header is absent or malformed.
 */
export function parseTraceparent(
  header: string | string[] | undefined,
): { traceId: string; spanId: string } | null {
  const raw = Array.isArray(header) ? header[0] : header;
  if (!raw) return null;
  const parts = raw.trim().toLowerCase().split('-');
  if (parts.length < 4) return null;
  const [, traceId, spanId] = parts;
  if (
    traceId === undefined ||
    spanId === undefined ||
    !TRACE_ID_PATTERN.test(traceId) ||
    !SPAN_ID_PATTERN.test(spanId) ||
    traceId === ZERO_TRACE_ID ||
    spanId === ZERO_SPAN_ID
  ) {
    return null;
  }
  return { traceId, spanId };
}
