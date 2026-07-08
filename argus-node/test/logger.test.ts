import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { createLogger } from '../src/factory';
import { withContext } from '../src/context/manager';
import { CaptureTransport } from './helpers';

function makeLogger(overrides: Record<string, unknown> = {}) {
  const capture = new CaptureTransport();
  const logger = createLogger({
    service: 'test-svc',
    environment: 'test',
    level: 'DEBUG',
    transports: [capture],
    selfMetrics: { enabled: false },
    installExitHooks: false,
    ...overrides,
  });
  return { logger, capture };
}

describe('ArgusLogger end-to-end', () => {
  it('emits OTel-shaped JSON with all mandatory fields', async () => {
    const { logger, capture } = makeLogger();
    logger.info('User login', { userId: 123 });
    await logger.flush();
    assert.equal(capture.lines.length, 1);
    const parsed = JSON.parse(capture.lines[0] as string);
    assert.equal(parsed.level, 'INFO');
    assert.equal(parsed.severityNumber, 9);
    assert.equal(parsed.service, 'test-svc');
    assert.equal(parsed.environment, 'test');
    assert.equal(parsed.message, 'User login');
    assert.equal(parsed.context.userId, 123);
    assert.match(parsed.timestamp, /^\d{4}-\d{2}-\d{2}T/);
    assert.match(parsed.traceId, /^[0-9a-f]{32}$/);
    assert.match(parsed.spanId, /^[0-9a-f]{16}$/);
    assert.deepEqual(parsed.maskedFields, []);
  });

  it('masks PII before the transport sees the record', async () => {
    const { logger, capture } = makeLogger();
    logger.info('signup', { email: 'alice@example.com', password: 'hunter2' });
    await logger.flush();
    const parsed = JSON.parse(capture.lines[0] as string);
    assert.equal(parsed.context.email, '[REDACTED:email]');
    assert.equal(parsed.context.password, '[REDACTED:sensitive-field]');
    assert.ok(parsed.maskedFields.includes('context.email'));
    assert.ok(parsed.maskedFields.includes('context.password'));
    // raw line must not contain the PII anywhere
    assert.ok(!(capture.lines[0] as string).includes('alice@example.com'));
    assert.ok(!(capture.lines[0] as string).includes('hunter2'));
  });

  it('propagates AsyncLocalStorage context into records automatically', async () => {
    const { logger, capture } = makeLogger();
    const traceId = 'f'.repeat(32);
    const spanId = '1'.repeat(16);
    await withContext({ traceId, spanId, requestId: 'r-9', userId: 'u-1' }, async () => {
      logger.info('inside scope');
    });
    await logger.flush();
    const parsed = JSON.parse(capture.lines[0] as string);
    assert.equal(parsed.traceId, traceId);
    assert.equal(parsed.spanId, spanId);
    assert.equal(parsed.context.requestId, 'r-9');
    assert.equal(parsed.context.userId, 'u-1');
  });

  it('suppresses below-level records and counts them', async () => {
    const { logger, capture } = makeLogger({ level: 'WARN' });
    logger.debug('nope');
    logger.info('nope');
    logger.warn('yes');
    await logger.flush();
    assert.equal(capture.lines.length, 1);
    assert.equal(logger.health.snapshot().suppressedBelowLevel, 2);
  });

  it('captures Error objects as structured error envelopes', async () => {
    const { logger, capture } = makeLogger();
    logger.error('boom', new TypeError('bad input'));
    await logger.flush();
    const parsed = JSON.parse(capture.lines[0] as string);
    assert.equal(parsed.error.type, 'TypeError');
    assert.equal(parsed.error.message, 'bad input');
    assert.ok(parsed.error.stacktrace.includes('TypeError'));
  });

  it('rejects non-conforming input and emits a diagnostic instead', async () => {
    const { logger, capture } = makeLogger();
    // function value violates the attribute contract
    logger.info('bad', { fn: (() => 1) as unknown as string });
    await logger.flush();
    assert.equal(logger.health.snapshot().rejected, 1);
    const diagnostic = capture.lines.find((l) => l.includes('contract violation'));
    assert.ok(diagnostic, 'expected a rejection diagnostic record');
  });

  it('child loggers inherit bound context', async () => {
    const { logger, capture } = makeLogger();
    const child = logger.child({ component: 'billing' });
    child.info('charged');
    await child.flush();
    const parsed = JSON.parse(capture.lines[0] as string);
    assert.equal(parsed.context.component, 'billing');
  });

  it('applies beforeLog hooks to context/metadata only', async () => {
    const { logger, capture } = makeLogger({
      hooks: {
        beforeLog: [
          (draft: { context: Record<string, unknown> }) => {
            draft.context['enriched'] = true;
          },
        ],
      },
    });
    logger.info('hooked');
    await logger.flush();
    const parsed = JSON.parse(capture.lines[0] as string);
    assert.equal(parsed.context.enriched, true);
  });

  it('reroutes failed deliveries to the fallback buffer', async () => {
    const { logger, capture } = makeLogger();
    capture.failNext = 2;
    logger.info('one');
    logger.info('two');
    logger.info('three');
    await logger.flush();
    assert.equal(capture.lines.length, 1);
    assert.equal(logger.pipeline.fallback.size, 2);
    assert.equal(logger.health.snapshot().transportErrors, 2);
    // recovery: replay the parked lines
    const replayed = logger.pipeline.replayFallback(capture);
    assert.equal(replayed, 2);
    await logger.flush();
    assert.equal(capture.lines.length, 3);
  });

  it('logger.error never throws even with hostile input', () => {
    const { logger } = makeLogger();
    const circular: Record<string, unknown> = {};
    circular['self'] = circular;
    assert.doesNotThrow(() => logger.info('circular', circular));
  });
});
