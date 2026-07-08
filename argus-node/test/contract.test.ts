import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  validateAndSeal,
  ContractViolationError,
  ZERO_SPAN_ID,
  ZERO_TRACE_ID,
} from '../src/contract/schema';
import { isDeepFrozen } from '../src/contract/freeze';

const valid = () => ({
  timestamp: new Date().toISOString(),
  level: 'INFO',
  severityNumber: 9,
  traceId: ZERO_TRACE_ID,
  spanId: ZERO_SPAN_ID,
  service: 'test-service',
  environment: 'test',
  message: 'hello',
  context: { userId: 42, nested: { a: [1, 2, 3] } },
  metadata: {},
  maskedFields: [],
});

describe('log contract', () => {
  it('accepts a conforming record and deep-freezes it', () => {
    const record = validateAndSeal(valid());
    assert.equal(record.level, 'INFO');
    assert.ok(isDeepFrozen(record));
    assert.throws(() => {
      (record as { message: string }).message = 'tampered';
    }, TypeError);
    assert.throws(() => {
      (record.context as Record<string, unknown>)['injected'] = true;
    }, TypeError);
  });

  it('rejects unknown levels', () => {
    assert.throws(() => validateAndSeal({ ...valid(), level: 'TRACE' }), ContractViolationError);
  });

  it('rejects missing mandatory fields', () => {
    const candidate: Record<string, unknown> = valid();
    delete candidate['service'];
    assert.throws(() => validateAndSeal(candidate), ContractViolationError);
  });

  it('rejects unknown top-level keys (no contract smuggling)', () => {
    assert.throws(
      () => validateAndSeal({ ...valid(), customFormatter: 'x' }),
      ContractViolationError,
    );
  });

  it('rejects malformed trace ids', () => {
    assert.throws(() => validateAndSeal({ ...valid(), traceId: 'not-hex' }), ContractViolationError);
  });

  it('rejects non-JSON-safe attribute values', () => {
    assert.throws(
      () => validateAndSeal({ ...valid(), context: { fn: () => 1 } }),
      ContractViolationError,
    );
  });

  it('rejects timestamps that are not ISO 8601', () => {
    assert.throws(
      () => validateAndSeal({ ...valid(), timestamp: '2025/01/15 10:00' }),
      ContractViolationError,
    );
  });

  it('rejects excessive attribute nesting', () => {
    let deep: Record<string, unknown> = { leaf: 1 };
    for (let i = 0; i < 12; i++) deep = { nested: deep };
    assert.throws(() => validateAndSeal({ ...valid(), context: deep }), ContractViolationError);
  });
});
