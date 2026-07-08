/**
 * Example: HTTP web server with automatic request-scoped context.
 *
 * Run: npm run example:web
 * Then: curl -H 'x-request-id: demo-1' http://127.0.0.1:8080/login
 *
 * Every log line automatically carries traceId/spanId/requestId — the
 * handler never passes them around.
 */

'use strict';

const http = require('node:http');
const {
  createLogger,
  expressMiddleware, // works for plain node:http too — same (req, res, next) shape
  StdoutTransport,
} = require('../dist/src/index');

const logger = createLogger({
  service: 'example-web',
  environment: 'development',
  transports: [new StdoutTransport({ pretty: false })],
  selfMetrics: { enabled: true, intervalMs: 30_000, port: 9464 },
});

const withRequestContext = expressMiddleware(logger);

const server = http.createServer((req, res) => {
  withRequestContext(req, res, () => {
    // Simulated auth handler: note the PII — it will be masked automatically.
    logger.info('User login attempt', {
      userId: 123,
      email: 'alice@example.com',
      ip: req.socket.remoteAddress ?? '127.0.0.1',
    });

    setTimeout(() => {
      // Still inside the same async context — requestId/traceId flow through timers.
      logger.debug('Session established', { sessionTtlSeconds: 3600 });
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end('{"ok":true}');
    }, 10);
  });
});

server.listen(8080, () => {
  logger.info('Server listening', { port: 8080, metricsPort: 9464 });
});

// Graceful shutdown: flush every queued record before exiting.
process.on('SIGINT', async () => {
  logger.info('Shutting down');
  server.close();
  await logger.shutdown();
  process.exit(0);
});
