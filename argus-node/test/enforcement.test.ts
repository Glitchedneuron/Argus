import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { createLogger } from '../src/factory';
import { ConfigurationError } from '../src/config';
import { CaptureTransport } from './helpers';
import * as publicApi from '../src/index';

function makeLogger() {
  const capture = new CaptureTransport();
  const logger = createLogger({
    service: 'seal-test',
    environment: 'test',
    level: 'DEBUG',
    transports: [capture],
    selfMetrics: { enabled: false },
    installExitHooks: false,
  });
  return { logger, capture };
}

describe('format enforcement / tamper resistance', () => {
  it('logger instances are frozen — no method swapping', () => {
    const { logger } = makeLogger();
    assert.ok(Object.isFrozen(logger));
    assert.throws(() => {
      (logger as unknown as Record<string, unknown>)['info'] = () => 'bypassed';
    }, TypeError);
    assert.throws(() => {
      (logger as unknown as Record<string, unknown>)['format'] = 'text';
    }, TypeError);
  });

  it('there is no runtime level manipulation API', () => {
    const { logger } = makeLogger();
    assert.equal((logger as unknown as Record<string, unknown>)['setLevel'], undefined);
    assert.throws(() => {
      (logger as unknown as { level: string }).level = 'DEBUG';
    }, TypeError);
  });

  it('records delivered to transports are deep-frozen', async () => {
    const { logger, capture } = makeLogger();
    logger.info('sealed', { a: { b: 1 } });
    await logger.flush();
    const record = capture.records[0] as Record<string, unknown>;
    assert.ok(Object.isFrozen(record));
    assert.throws(() => {
      (record as { message: string }).message = 'tampered';
    }, TypeError);
    assert.throws(() => {
      ((record['context'] as Record<string, unknown>)['a'] as Record<string, unknown>)['b'] = 2;
    }, TypeError);
  });

  it('config rejects unknown options (no formatter/serializer smuggling)', () => {
    assert.throws(
      () =>
        createLogger({
          service: 'x',
          // @ts-expect-error — intentionally invalid
          formatter: () => 'my own format',
        }),
      ConfigurationError,
    );
    assert.throws(
      () =>
        createLogger({
          service: 'x',
          // @ts-expect-error — intentionally invalid
          serializers: {},
        }),
      ConfigurationError,
    );
  });

  it('the private serializer is not part of the public API', () => {
    const exportedNames = Object.keys(publicApi);
    assert.ok(!exportedNames.includes('serializeRecord'));
    assert.ok(!exportedNames.includes('Pipeline'));
    assert.ok(!exportedNames.includes('RingBuffer'));
    assert.ok(!exportedNames.includes('PiiMaskingEngine'));
  });

  it('transports only receive finished lines + frozen records', async () => {
    const { logger, capture } = makeLogger();
    logger.info('shape check');
    await logger.flush();
    const line = capture.lines[0] as string;
    const record = capture.records[0]!;
    // The line is the canonical serialization — a transport re-serializing
    // the frozen record cannot change what other transports already got.
    assert.equal(JSON.parse(line).message, record.message);
    assert.ok(Object.isFrozen(record));
  });

  it('output is always parseable single-line JSON (NDJSON)', async () => {
    const { logger, capture } = makeLogger();
    logger.info('line one\nwith newline attempt', { quote: '"}{' });
    await logger.flush();
    const line = capture.lines[0] as string;
    assert.ok(!line.includes('\n'));
    assert.doesNotThrow(() => JSON.parse(line));
  });
});
