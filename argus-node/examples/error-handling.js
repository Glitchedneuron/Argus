/**
 * Example: error handling and resilience.
 *
 * Demonstrates:
 *   - structured error envelopes (exception.type/message/stacktrace)
 *   - contract rejection diagnostics (bad input never crashes, never emits garbage)
 *   - transport failure -> fallback buffer -> replay
 *
 * Run: npm run example:errors
 */

'use strict';

const {
  createLogger,
  Transport,
  StdoutTransport,
} = require('../dist/src/index');

/** A custom transport plugin that fails on demand (e.g. network flake). */
class FlakyTransport extends Transport {
  constructor() {
    super('flaky');
    this.healthy = false;
    this.delivered = [];
  }

  writeEnvelope(envelope) {
    if (!this.healthy) throw new Error('collector unreachable');
    this.delivered.push(envelope.line);
  }
}

const flaky = new FlakyTransport();
const logger = createLogger({
  service: 'error-demo',
  environment: 'development',
  transports: [new StdoutTransport({ pretty: false }), flaky],
  selfMetrics: { enabled: false },
});

async function main() {
  // 1. Structured errors: pass the Error directly.
  try {
    JSON.parse('{broken');
  } catch (err) {
    logger.error('Failed to parse payload', err);
  }

  // 2. Error with extra context and metadata.
  logger.error(
    'Payment gateway rejected charge',
    { orderId: 'o-1', amountCents: 4999 },
    { error: new Error('card declined'), metadata: { retryable: true } },
  );

  // 3. Contract rejection: a function is not a legal attribute value.
  //    The record is rejected; a diagnostic ERROR record is emitted instead.
  logger.info('bad attributes', { callback: () => {} });

  // 4. Transport failure: flaky is down, deliveries park in the fallback buffer.
  logger.info('while collector is down 1');
  logger.info('while collector is down 2');
  await logger.flush();

  // 5. Recovery: replay the fallback buffer through the recovered transport.
  flaky.healthy = true;
  const replayed = logger.pipeline.replayFallback(flaky);
  await logger.flush();

  const health = logger.health.snapshot();
  logger.info('Resilience summary', {
    replayed,
    transportErrors: health.transportErrors,
    rejected: health.rejected,
    fallbackBuffered: health.fallbackBuffered,
  });

  await logger.shutdown();
}

main().catch((err) => {
  process.stderr.write(String(err && err.stack) + '\n');
  process.exitCode = 1;
});
