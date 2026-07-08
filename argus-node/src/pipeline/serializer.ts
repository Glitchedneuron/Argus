/**
 * The private formatting layer.
 *
 * This module is intentionally NOT exported from the package index. The
 * output shape is fixed: one OTel-aligned JSON object per line (NDJSON),
 * deterministic key order. There is no serializer registry, no replacer
 * hook, no `format` option — transports receive the finished line and the
 * frozen record, nothing else.
 */

import type { LogRecord } from '../contract/schema';

/**
 * Serialize a validated, frozen record with a deterministic top-level key
 * order. JSON.stringify with an explicit key list both fixes the order and
 * guarantees no foreign keys can be smuggled into the output.
 */
const KEY_ORDER: readonly (keyof LogRecord)[] = [
  'timestamp',
  'level',
  'severityNumber',
  'traceId',
  'spanId',
  'service',
  'environment',
  'message',
  'context',
  'metadata',
  'maskedFields',
  'error',
] as const;

export function serializeRecord(record: Readonly<LogRecord>): string {
  let out = '{';
  let first = true;
  for (const key of KEY_ORDER) {
    const value = record[key];
    if (value === undefined) continue;
    if (!first) out += ',';
    first = false;
    out += JSON.stringify(key) + ':' + JSON.stringify(value);
  }
  return out + '}';
}

/**
 * Map a record to the OTLP/HTTP JSON logs payload shape
 * (https://opentelemetry.io/docs/specs/otlp/). Used by the HTTP transport
 * when `otlp: true`.
 */
export function toOtlpLogRecord(record: Readonly<LogRecord>): Record<string, unknown> {
  const attributes: { key: string; value: { stringValue: string } }[] = [];
  const push = (key: string, value: unknown): void => {
    attributes.push({
      key,
      value: { stringValue: typeof value === 'string' ? value : JSON.stringify(value) },
    });
  };
  for (const [key, value] of Object.entries(record.context)) push(`context.${key}`, value);
  for (const [key, value] of Object.entries(record.metadata)) push(`metadata.${key}`, value);
  if (record.maskedFields.length > 0) push('argus.masked_fields', record.maskedFields);
  if (record.error) {
    push('exception.type', record.error.type);
    push('exception.message', record.error.message);
    if (record.error.stacktrace) push('exception.stacktrace', record.error.stacktrace);
  }
  return {
    timeUnixNano: String(Date.parse(record.timestamp) * 1_000_000),
    severityNumber: record.severityNumber,
    severityText: record.level,
    body: { stringValue: record.message },
    traceId: record.traceId,
    spanId: record.spanId,
    attributes,
  };
}

export function toOtlpPayload(records: readonly Readonly<LogRecord>[]): string {
  if (records.length === 0) return '';
  const first = records[0] as Readonly<LogRecord>;
  return JSON.stringify({
    resourceLogs: [
      {
        resource: {
          attributes: [
            { key: 'service.name', value: { stringValue: first.service } },
            { key: 'deployment.environment.name', value: { stringValue: first.environment } },
          ],
        },
        scopeLogs: [
          {
            scope: { name: 'argus-logging-node', version: '1.0.0' },
            logRecords: records.map(toOtlpLogRecord),
          },
        ],
      },
    ],
  });
}
