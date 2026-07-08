import { describe, it, after } from 'node:test';
import assert from 'node:assert/strict';
import { readFile, readdir, rm, mkdtemp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createLogger } from '../src/factory';
import { FileTransport } from '../src/transports/file';
import { WorkerPool } from '../src/pipeline/workerPool';
import { serializeMaskingOptions } from '../src/masking/serializeOptions';
import { DEFAULT_MASKING_OPTIONS } from '../src/masking/engine';
import { ZERO_SPAN_ID, ZERO_TRACE_ID } from '../src/contract/schema';

const tempDirs: string[] = [];

after(async () => {
  for (const dir of tempDirs) await rm(dir, { recursive: true, force: true });
});

async function tempDir(): Promise<string> {
  const dir = await mkdtemp(join(tmpdir(), 'argus-test-'));
  tempDirs.push(dir);
  return dir;
}

describe('file transport', () => {
  it('writes NDJSON lines and flushes on shutdown', async () => {
    const dir = await tempDir();
    const path = join(dir, 'app.log');
    const logger = createLogger({
      service: 'file-test',
      environment: 'test',
      transports: [new FileTransport({ path })],
      selfMetrics: { enabled: false },
      installExitHooks: false,
    });
    for (let i = 0; i < 25; i++) logger.info(`message ${i}`, { i });
    await logger.shutdown();
    const content = await readFile(path, 'utf8');
    const lines = content.trim().split('\n');
    assert.equal(lines.length, 25);
    assert.equal(JSON.parse(lines[7] as string).context.i, 7);
  });

  it('rotates files when maxBytes is exceeded', async () => {
    const dir = await tempDir();
    const path = join(dir, 'rotate.log');
    const logger = createLogger({
      service: 'rotate-test',
      environment: 'test',
      transports: [new FileTransport({ path, maxBytes: 2048, maxFiles: 3 })],
      selfMetrics: { enabled: false },
      installExitHooks: false,
    });
    for (let i = 0; i < 60; i++) logger.info(`rotation test message number ${i}`, { i, pad: 'x'.repeat(64) });
    await logger.shutdown();
    const files = await readdir(dir);
    assert.ok(files.includes('rotate.log'));
    assert.ok(files.some((f) => f.startsWith('rotate.log.')), `expected rotated files, got ${files.join(',')}`);
    assert.ok(files.length <= 4); // active + maxFiles
  });
});

describe('worker pool', () => {
  it('masks, validates and serializes in worker threads', async () => {
    const pool = new WorkerPool({
      poolSize: 2,
      maskingOptions: serializeMaskingOptions(DEFAULT_MASKING_OPTIONS),
    });
    try {
      const carrier = {
        timestamp: new Date().toISOString(),
        level: 'INFO',
        severityNumber: 9,
        traceId: ZERO_TRACE_ID,
        spanId: ZERO_SPAN_ID,
        service: 'worker-test',
        environment: 'test',
        message: 'mail bob@example.com',
        context: { password: 'secret!' },
        metadata: {},
        maskedFields: [],
      };
      const [result] = await pool.process([carrier]);
      assert.ok(result?.ok);
      if (result.ok) {
        assert.equal(result.record.message, 'mail [REDACTED:email]');
        assert.equal(result.record.context['password'], '[REDACTED:sensitive-field]');
        assert.ok(result.line.includes('[REDACTED:email]'));
      }
    } finally {
      await pool.close();
    }
  });

  it('rejects contract violations in the worker too', async () => {
    const pool = new WorkerPool({
      poolSize: 1,
      maskingOptions: serializeMaskingOptions(DEFAULT_MASKING_OPTIONS),
    });
    try {
      const [result] = await pool.process([
        {
          timestamp: 'not-a-date',
          level: 'INFO',
          severityNumber: 9,
          traceId: ZERO_TRACE_ID,
          spanId: ZERO_SPAN_ID,
          service: 'worker-test',
          environment: 'test',
          message: 'x',
          context: {},
          metadata: {},
          maskedFields: [],
        },
      ]);
      assert.ok(result && !result.ok);
    } finally {
      await pool.close();
    }
  });
});

describe('worker-mode logger', () => {
  it('produces identical output through the worker pipeline', async () => {
    const dir = await tempDir();
    const path = join(dir, 'workers.log');
    const logger = createLogger({
      service: 'worker-e2e',
      environment: 'test',
      transports: [new FileTransport({ path })],
      workers: { enabled: true, poolSize: 2, batchSize: 8 },
      selfMetrics: { enabled: false },
      installExitHooks: false,
    });
    for (let i = 0; i < 40; i++) {
      logger.info(`worker message ${i}`, { i, email: 'w@example.com' });
    }
    await logger.shutdown();
    const lines = (await readFile(path, 'utf8')).trim().split('\n');
    assert.equal(lines.length, 40);
    for (const line of lines) {
      const parsed = JSON.parse(line);
      assert.equal(parsed.context.email, '[REDACTED:email]');
      assert.equal(parsed.service, 'worker-e2e');
    }
    // FIFO order preserved across worker batches
    const order = lines.map((l) => JSON.parse(l).context.i as number);
    assert.deepEqual(order, [...order].sort((a, b) => a - b));
  });
});
