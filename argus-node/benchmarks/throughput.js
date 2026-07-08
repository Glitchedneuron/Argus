/**
 * Throughput benchmark.
 *
 * Measures sustained log throughput and per-call latency on three paths:
 *   1. null transport (pure pipeline cost: mask + validate + freeze + serialize)
 *   2. file transport (real async disk I/O)
 *   3. worker-thread pipeline (masking/validation offloaded)
 *
 * Run: npm run bench
 */

'use strict';

const { rmSync, mkdtempSync } = require('node:fs');
const { tmpdir } = require('node:os');
const { join } = require('node:path');
const { createLogger, Transport, FileTransport } = require('../dist/src/index');

class NullTransport extends Transport {
  constructor() {
    super('null');
    this.count = 0;
  }

  writeEnvelope() {
    this.count++;
  }
}

async function bench(name, makeLogger, messages) {
  const { logger, cleanup } = await makeLogger();
  // Warmup
  for (let i = 0; i < 1000; i++) logger.info('warmup', { i });
  await logger.flush();

  const start = process.hrtime.bigint();
  for (let i = 0; i < messages; i++) {
    logger.info('benchmark message with some realistic length to serialize', {
      i,
      userId: 42,
      route: '/orders/{id}',
      email: i % 100 === 0 ? 'load@example.com' : 'no-pii-here',
    });
  }
  const emitDoneNs = process.hrtime.bigint();
  await logger.flush();
  const flushDoneNs = process.hrtime.bigint();
  await logger.shutdown();
  if (cleanup) cleanup();

  const emitMs = Number(emitDoneNs - start) / 1e6;
  const totalMs = Number(flushDoneNs - start) / 1e6;
  const throughput = Math.round((messages / totalMs) * 1000);
  const emitLatencyUs = (emitMs * 1000) / messages;
  console.log(
    `${name.padEnd(24)} ${String(messages).padStart(8)} msgs | ` +
      `emit ${emitMs.toFixed(0).padStart(6)} ms (${emitLatencyUs.toFixed(2)} µs/call) | ` +
      `end-to-end ${totalMs.toFixed(0).padStart(6)} ms | ` +
      `${String(throughput).padStart(7)} msg/s`,
  );
  return throughput;
}

const baseConfig = {
  service: 'bench',
  environment: 'test',
  level: 'INFO',
  selfMetrics: { enabled: false },
  installExitHooks: false,
  buffer: { capacity: 200_000 },
};

async function main() {
  const messages = Number(process.env.BENCH_MESSAGES ?? 100_000);
  console.log(`argus-logging-node throughput benchmark (${messages} messages/run, node ${process.version})\n`);

  await bench('null transport (sync)', async () => ({
    logger: createLogger({ ...baseConfig, transports: [new NullTransport()] }),
  }), messages);

  const dir = mkdtempSync(join(tmpdir(), 'argus-bench-'));
  await bench('file transport', async () => ({
    logger: createLogger({
      ...baseConfig,
      transports: [new FileTransport({ path: join(dir, 'bench.log'), maxBytes: 1024 * 1024 * 1024 })],
    }),
    cleanup: () => rmSync(dir, { recursive: true, force: true }),
  }), messages);

  await bench('null transport (workers)', async () => ({
    logger: createLogger({
      ...baseConfig,
      transports: [new NullTransport()],
      workers: { enabled: true, batchSize: 256 },
    }),
  }), messages);
}

main().catch((err) => {
  console.error(err);
  process.exitCode = 1;
});
