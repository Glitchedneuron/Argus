/**
 * Example: microservice with nested service-to-service context, worker
 * threads, file + OTLP transports, and child loggers.
 *
 * Run: npm run example:micro
 */

'use strict';

const {
  createLogger,
  withRequestScope,
  withContext,
  FileTransport,
  StdoutTransport,
} = require('../dist/src/index');

const logger = createLogger({
  service: 'order-service',
  environment: 'development',
  level: 'DEBUG',
  transports: [
    new StdoutTransport({ pretty: false }),
    new FileTransport({ path: 'logs/order-service.log', maxBytes: 10 * 1024 * 1024, maxFiles: 3 }),
    // For a real collector, add:
    // new HttpTransport({ url: 'http://otel-collector:4318/v1/logs' }),
  ],
  workers: { enabled: true, poolSize: 2, batchSize: 32 },
  masking: {
    allowList: ['context.ip'], // ops decided client IP is fine to keep
    customPatterns: [{ name: 'order-ref', pattern: /ORD-\d{8}/ }],
  },
  selfMetrics: { enabled: false },
});

const paymentLog = logger.child({ component: 'payments' });

async function chargeCustomer(orderId) {
  // Nested scope: inherits requestId/traceId, adds a hop marker.
  return withContext({ hop: 'payment-gateway' }, async () => {
    paymentLog.info('Charging card', {
      orderId,
      cardNumber: '4532 0151 1283 0366', // masked: Luhn-valid PAN
      customerEmail: 'bob@example.com', // masked: email
    });
    await new Promise((resolve) => setTimeout(resolve, 25));
    paymentLog.info('Charge accepted', { orderId });
  });
}

async function handleOrder(orderId) {
  return withRequestScope({ userId: 'u-77' }, async () => {
    logger.info('Order received', { orderId, ref: `ORD-${String(orderId).padStart(8, '0')}` });
    await chargeCustomer(orderId);
    logger.info('Order completed', { orderId });
  });
}

async function main() {
  await Promise.all([handleOrder(1), handleOrder(2), handleOrder(3)]);
  const health = logger.health.snapshot();
  logger.info('Logger health', {
    emitted: health.emitted,
    written: health.written,
    masked: health.maskingActions,
  });
  await logger.shutdown();
}

main().catch((err) => {
  process.stderr.write(String(err && err.stack) + '\n');
  process.exitCode = 1;
});
