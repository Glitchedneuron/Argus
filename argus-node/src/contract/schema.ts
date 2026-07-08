/**
 * The Argus log contract.
 *
 * Every record emitted by the library MUST satisfy this schema. Validation
 * happens at compile time (TypeScript types below) and again at runtime
 * (zod). Records that fail validation are rejected — they are never written
 * to any transport. Validated records are deep-frozen.
 */

import { z } from 'zod';
import { LOG_LEVELS, type LogLevel } from './levels';
import { deepFreeze } from './freeze';

export const ENVIRONMENTS = ['development', 'staging', 'production', 'test'] as const;
export type Environment = (typeof ENVIRONMENTS)[number];

/** W3C trace context formats. All-zero IDs mean "no active trace". */
export const TRACE_ID_PATTERN = /^[0-9a-f]{32}$/;
export const SPAN_ID_PATTERN = /^[0-9a-f]{16}$/;
export const ZERO_TRACE_ID = '0'.repeat(32);
export const ZERO_SPAN_ID = '0'.repeat(16);

const MAX_MESSAGE_LENGTH = 8192;
const MAX_ATTRIBUTE_DEPTH = 8;
const MAX_ATTRIBUTE_KEYS = 256;

/** Structured error envelope, aligned with OTel `exception.*` semantics. */
export const errorEnvelopeSchema = z
  .object({
    type: z.string().min(1).max(256),
    message: z.string().max(MAX_MESSAGE_LENGTH),
    stacktrace: z.string().max(65536).optional(),
  })
  .strict();

export type ErrorEnvelope = z.infer<typeof errorEnvelopeSchema>;

/**
 * Attribute bags (`context`, `metadata`) must be JSON-serializable plain
 * data. Functions, symbols, class instances etc. are rejected up front so
 * nothing surprising ever reaches a transport.
 */
const attributeValueSchema: z.ZodType<unknown> = z.lazy(() =>
  z.union([
    z.string().max(MAX_MESSAGE_LENGTH),
    z.number().finite(),
    z.boolean(),
    z.null(),
    z.array(attributeValueSchema).max(MAX_ATTRIBUTE_KEYS),
    z.record(z.string().max(512), attributeValueSchema),
  ]),
);

export const attributeBagSchema = z.record(z.string().min(1).max(512), attributeValueSchema);
export type AttributeBag = Record<string, unknown>;

/** The full, mandatory log record contract. `strict()` rejects unknown keys. */
export const logRecordSchema = z
  .object({
    /** ISO 8601 timestamp with millisecond precision. */
    timestamp: z.string().datetime({ offset: true }),
    /** Contract severity level. */
    level: z.enum(LOG_LEVELS),
    /** OTel severity number derived from `level`. */
    severityNumber: z.number().int().min(1).max(24),
    /** W3C trace id (32 lowercase hex chars; all zeros when untraced). */
    traceId: z.string().regex(TRACE_ID_PATTERN),
    /** W3C span id (16 lowercase hex chars; all zeros when untraced). */
    spanId: z.string().regex(SPAN_ID_PATTERN),
    /** Emitting service name (OTel `service.name`). */
    service: z.string().min(1).max(256),
    /** Deployment environment (OTel `deployment.environment.name`). */
    environment: z.enum(ENVIRONMENTS),
    /** Human-readable summary (OTel body). */
    message: z.string().min(1).max(MAX_MESSAGE_LENGTH),
    /** Request/operation-scoped structured attributes. */
    context: attributeBagSchema,
    /** Free-form key/value metadata. */
    metadata: attributeBagSchema,
    /** Dot-paths of fields the PII engine masked in this record. */
    maskedFields: z.array(z.string()).max(1024),
    /** Optional structured error details. */
    error: errorEnvelopeSchema.optional(),
  })
  .strict();

export type LogRecord = z.infer<typeof logRecordSchema> & {
  level: LogLevel;
  environment: Environment;
};

export class ContractViolationError extends Error {
  public readonly issues: readonly string[];

  constructor(issues: readonly string[]) {
    super(`Log record violates the Argus contract: ${issues.join('; ')}`);
    this.name = 'ContractViolationError';
    this.issues = issues;
  }
}

function checkDepth(value: unknown, depth: number): string | null {
  if (depth > MAX_ATTRIBUTE_DEPTH) return `attribute nesting exceeds ${MAX_ATTRIBUTE_DEPTH} levels`;
  if (value === null || typeof value !== 'object') return null;
  for (const child of Object.values(value as Record<string, unknown>)) {
    const problem = checkDepth(child, depth + 1);
    if (problem) return problem;
  }
  return null;
}

// ---------------------------------------------------------------------------
// Hot-path validation.
//
// zod gives precise diagnostics but costs ~50µs/record. The pipeline
// validates every record with `fastConforms` — a hand-rolled structural
// check enforcing the SAME constraints (or stricter: it also applies the
// depth limit) — and only falls back to zod when the fast check fails, to
// produce human-readable violation issues. Behavior is identical; only the
// cost of the happy path changes.
// ---------------------------------------------------------------------------

const FAST_TIMESTAMP = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/;
const FAST_LEVELS: readonly string[] = ['DEBUG', 'INFO', 'WARN', 'ERROR', 'FATAL'];
const FAST_ENVS: readonly string[] = ENVIRONMENTS;

/** Carrier shape produced by the logger before validation. */
export interface CandidateRecord {
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

function fastValueOk(value: unknown, depth: number): boolean {
  if (value === null) return true;
  switch (typeof value) {
    case 'string':
      return value.length <= MAX_MESSAGE_LENGTH;
    case 'number':
      return Number.isFinite(value);
    case 'boolean':
      return true;
    case 'object':
      break;
    default:
      return false; // functions, symbols, bigints
  }
  if (depth > MAX_ATTRIBUTE_DEPTH) return false;
  if (Array.isArray(value)) {
    if (value.length > MAX_ATTRIBUTE_KEYS) return false;
    for (const item of value) {
      if (item === undefined || !fastValueOk(item, depth + 1)) return false;
    }
    return true;
  }
  for (const key of Object.keys(value)) {
    if (key.length > 512) return false;
    const child = (value as Record<string, unknown>)[key];
    if (child !== undefined && !fastValueOk(child, depth + 1)) return false;
  }
  return true;
}

function fastBagOk(bag: unknown, depth: number): boolean {
  if (bag === null || typeof bag !== 'object' || Array.isArray(bag)) return false;
  for (const key of Object.keys(bag)) {
    if (key.length < 1 || key.length > 512) return false;
    const child = (bag as Record<string, unknown>)[key];
    if (child === undefined) return false;
    if (!fastValueOk(child, depth + 1)) return false;
  }
  return true;
}

function fastConforms(c: CandidateRecord): boolean {
  return (
    typeof c.timestamp === 'string' &&
    FAST_TIMESTAMP.test(c.timestamp) &&
    FAST_LEVELS.includes(c.level) &&
    Number.isInteger(c.severityNumber) &&
    c.severityNumber >= 1 &&
    c.severityNumber <= 24 &&
    typeof c.traceId === 'string' &&
    TRACE_ID_PATTERN.test(c.traceId) &&
    typeof c.spanId === 'string' &&
    SPAN_ID_PATTERN.test(c.spanId) &&
    typeof c.service === 'string' &&
    c.service.length >= 1 &&
    c.service.length <= 256 &&
    FAST_ENVS.includes(c.environment) &&
    typeof c.message === 'string' &&
    c.message.length >= 1 &&
    c.message.length <= MAX_MESSAGE_LENGTH &&
    fastBagOk(c.context, 1) &&
    fastBagOk(c.metadata, 1) &&
    Array.isArray(c.maskedFields) &&
    c.maskedFields.length <= 1024 &&
    c.maskedFields.every((f) => typeof f === 'string') &&
    (c.error === undefined ||
      (typeof c.error === 'object' &&
        c.error !== null &&
        typeof c.error.type === 'string' &&
        c.error.type.length >= 1 &&
        c.error.type.length <= 256 &&
        typeof c.error.message === 'string' &&
        c.error.message.length <= MAX_MESSAGE_LENGTH &&
        (c.error.stacktrace === undefined ||
          (typeof c.error.stacktrace === 'string' && c.error.stacktrace.length <= 65536))))
  );
}

export type SealResult =
  | { ok: true; record: Readonly<LogRecord> }
  | { ok: false; issues: string[] };

/**
 * Validate a candidate carrier and produce the sealed (deep-frozen) record.
 * Fast structural check first; zod fallback for detailed issue messages.
 * The record is rebuilt from known contract fields only, so unknown keys on
 * the carrier can never leak into output.
 */
export function sealCandidate(candidate: CandidateRecord): SealResult {
  if (fastConforms(candidate)) {
    const record: LogRecord = {
      timestamp: candidate.timestamp,
      level: candidate.level as LogRecord['level'],
      severityNumber: candidate.severityNumber,
      traceId: candidate.traceId,
      spanId: candidate.spanId,
      service: candidate.service,
      environment: candidate.environment as LogRecord['environment'],
      message: candidate.message,
      context: candidate.context,
      metadata: candidate.metadata,
      maskedFields: candidate.maskedFields,
      ...(candidate.error
        ? {
            error: {
              type: candidate.error.type,
              message: candidate.error.message,
              ...(candidate.error.stacktrace !== undefined
                ? { stacktrace: candidate.error.stacktrace }
                : {}),
            },
          }
        : {}),
    };
    return { ok: true, record: deepFreeze(record) };
  }
  const parsed = logRecordSchema.safeParse(candidate);
  if (!parsed.success) {
    return {
      ok: false,
      issues: parsed.error.issues.map(
        (issue) => `${issue.path.join('.') || '(root)'}: ${issue.message}`,
      ),
    };
  }
  const depthProblem = checkDepth(parsed.data.context, 1) ?? checkDepth(parsed.data.metadata, 1);
  if (depthProblem) return { ok: false, issues: [depthProblem] };
  return { ok: true, record: deepFreeze(parsed.data as LogRecord) };
}

/**
 * Validate a candidate record against the contract and deep-freeze it.
 *
 * @throws {ContractViolationError} when the candidate does not conform.
 */
export function validateAndSeal(candidate: unknown): Readonly<LogRecord> {
  const parsed = logRecordSchema.safeParse(candidate);
  if (!parsed.success) {
    throw new ContractViolationError(
      parsed.error.issues.map((issue) => `${issue.path.join('.') || '(root)'}: ${issue.message}`),
    );
  }
  const depthProblem =
    checkDepth(parsed.data.context, 1) ?? checkDepth(parsed.data.metadata, 1);
  if (depthProblem) {
    throw new ContractViolationError([depthProblem]);
  }
  return deepFreeze(parsed.data as LogRecord);
}
