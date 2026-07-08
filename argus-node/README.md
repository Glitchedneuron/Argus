# Argus for Node.js — `argus-logging-node`

Contract-enforced OpenTelemetry structured logging for Node.js. The Node.js
counterpart to [Argus for Java](../README.md): same philosophy (the contract
is the law), rebuilt on Node's async primitives — **AsyncLocalStorage**,
**Worker Threads**, and **backpressure-aware streams**.

It **replaces** winston / pino / bunyan and is designed so application teams
**cannot** bypass or override the log format.

```js
const { createLogger } = require('argus-logging-node');

const logger = createLogger({ service: 'auth' });

logger.info('User login', { userId: 123, ip: '203.0.113.7', email: 'a@b.com' });
```

Enforced output — always one OTel-aligned JSON object per line:

```json
{"timestamp":"2026-07-08T10:15:30.042Z","level":"INFO","severityNumber":9,
 "traceId":"4bf92f3577b34da6a3ce929d0e0e4736","spanId":"00f067aa0ba902b7",
 "service":"auth","environment":"production","message":"User login",
 "context":{"userId":123,"ip":"[REDACTED:ipv4]","email":"[REDACTED:email]"},
 "metadata":{},"maskedFields":["context.ip","context.email"]}
```

---

## Why you can't bypass it

| Attack | Defense |
|---|---|
| `logger.info = myFn` | Logger instances are `Object.freeze`d; state lives in true `#private` fields |
| Mutate a record in a transport/hook | Records are **deep-frozen** after validation; writes throw in strict mode |
| `createLogger({ formatter: ... })` | Config schema is `strict()` — unknown keys are a `ConfigurationError` |
| Change level/format at runtime | There is no `setLevel`, no format option; config is deep-frozen at init |
| Custom serializers | The serializer is a private module, not exported; transports receive the **finished line** |
| Smuggle extra top-level fields | Contract schema is `strict()`; unknown keys are rejected |
| Non-conforming records | zod validation on **every** record — rejects, counts, and emits a diagnostic |
| Raw `console.log` | Opt-in `enforceConsoleContract(logger)` rewires + freezes `console` |

Extensibility that *is* allowed: custom **transports** (they choose where
bytes go, never what they look like), **pre-serialization hooks** (may touch
`message`/`context`/`metadata` only), custom **PII patterns**, and custom
**context providers**.

## The contract

Every record carries all mandatory fields, validated by zod at runtime and
typed at compile time (`LogRecord`):

`timestamp` (ISO 8601) · `level` (DEBUG/INFO/WARN/ERROR/FATAL) ·
`severityNumber` (OTel) · `traceId` / `spanId` (W3C) · `service` ·
`environment` (development/staging/production/test) · `message` ·
`context` · `metadata` · `maskedFields` · `error?` (type/message/stacktrace)

## Architecture

```
logger.info(msg, ctx)                          ── never blocks, never throws
   │  level gate
   ▼
carrier (pooled object; ~zero alloc)
   │  beforeLog hooks        (context/metadata/message only)
   │  PII masking            (before serialization — PII never reaches I/O)
   │  zod contract validation (reject + diagnostic on violation)
   │  deep-freeze            (immutability is the contract)
   │  canonical serializer   (private module, fixed key order, NDJSON)
   ▼                                     ┌── optional: Worker Thread pool
ring buffer (preallocated, drop policy)  ◄┘   (masking+validation+serialization
   │  setImmediate drain loop                  off the main thread, FIFO kept)
   ▼
transports (stream.Writable, per-transport backpressure)
   ├─ StdoutTransport   structured NDJSON only
   ├─ FileTransport     async append + size rotation
   ├─ HttpTransport     OTLP/HTTP JSON, batching, retry w/ backoff, degraded mode
   └─ your Transport subclass
        │ on failure
        ▼
in-memory fallback buffer ── replay on recovery; drops are counted
```

## Quick start

Zero config — service/level/environment resolve from
`OTEL_SERVICE_NAME`, `LOG_LEVEL`/`ARGUS_LOG_LEVEL`, and
`DEPLOYMENT_ENVIRONMENT`/`APP_ENV`/`NODE_ENV`:

```js
const { createLogger } = require('argus-logging-node');
const logger = createLogger();          // stdout NDJSON transport by default
```

Full configuration (all keys optional, everything validated then frozen):

```ts
import {
  createLogger, FileTransport, HttpTransport, StdoutTransport,
} from 'argus-logging-node';

const logger = createLogger({
  service: 'order-service',
  environment: 'production',
  level: 'INFO',
  transports: [
    new StdoutTransport(),
    new FileTransport({ path: '/var/log/app/app.log', maxBytes: 50e6, maxFiles: 5 }),
    new HttpTransport({ url: 'http://otel-collector:4318/v1/logs' }), // OTLP JSON
  ],
  buffer: { capacity: 8192, overflowPolicy: 'drop-oldest', fallbackCapacity: 4096 },
  masking: {
    allowList: ['context.ip'],                                  // never mask these paths
    customPatterns: [{ name: 'employee-id', pattern: /EMP-\d{6}/ }],
    customFieldMatchers: [/internal[_-]?code/i],
  },
  workers: { enabled: true, poolSize: 2, batchSize: 64 },       // worker-thread offload
  hooks: { beforeLog: [(draft) => { draft.context.region = 'eu-1'; }] },
  selfMetrics: { enabled: true, intervalMs: 60_000, port: 9464 },
});
```

Java-style builder (familiar to Argus for Java users):

```ts
const logger = ArgusLoggerFactory.builder()
  .serviceName('order-service')
  .environment('production')
  .transports(new StdoutTransport())
  .build();   // single-use; build() seals everything
```

## Context propagation (AsyncLocalStorage)

```ts
import { withRequestScope, withContext } from 'argus-logging-node';

// HTTP middleware does this for you (express/fastify/koa adapters included):
app.use(expressMiddleware(logger));

// Manual scoping:
await withRequestScope({ userId: 'u-1' }, async () => {
  logger.info('start');                       // carries traceId/spanId/requestId/userId
  await withContext({ hop: 'inventory' }, async () => {
    logger.info('nested');                    // inherits everything, adds hop
  });
});
```

Trace correlation is automatic: incoming W3C `traceparent` headers are
parsed; missing ids are generated. Context survives `await`, Promise chains,
timers, and I/O callbacks — no manual passing.

## PII masking

Built-in detectors (applied **before** serialization): emails, phone
numbers, credit cards (Luhn-verified), SSNs, IPv4 addresses, JWTs, bearer
tokens, and well-known API key shapes (`sk-…`, `ghp_…`, `AKIA…`, `xox…`),
plus wholesale masking of sensitive **field names** (`password`, `secret`,
`token`, `apiKey`, `ssn`, `cvv`, …).

Every action is audited: masked paths land in the record's `maskedFields`,
and totals appear in health metrics (`maskingActions`).

## Resilience

- A logging failure **never** throws into application code.
- Failed deliveries park in a bounded in-memory fallback buffer; replay with
  `logger.pipeline.replayFallback(transport)` when the sink recovers.
- `HttpTransport` retries with exponential backoff + jitter, then enters
  degraded mode (cooldown) instead of hammering a dead collector.
- Overflow drops are counted (`dropped`, `fallbackDropped`) — capacity
  problems are observable, not silent.
- `logger.shutdown()` drains the queue and flushes every transport; a
  `beforeExit` hook is installed by default.

## Clustering / multi-process

Every transport emits **atomic single-line NDJSON writes**, which makes
multi-process aggregation configuration-free:

- `cluster` / `child_process` workers: give each worker its own
  `createLogger()` — stdout lines from all workers interleave without
  tearing (pipe writes below `PIPE_BUF` are atomic), and each record
  carries `service` + context (add `{ workerId: cluster.worker?.id }` as
  bound context via `logger.child(...)`).
- Shared log file: `FileTransport` opens with `O_APPEND`; concurrent
  same-file appends of one line each do not interleave on POSIX.
- Centralized aggregation: point every worker's `HttpTransport` at the same
  OTLP collector — batches are independent and self-describing.

## Observability

```ts
logger.health.snapshot();
// { emitted, written, rejected, dropped, suppressedBelowLevel, transportErrors,
//   maskingActions, fallbackBuffered, fallbackDropped, queueDepth, queueCapacity,
//   workerPendingBatches, rssBytes, heapUsedBytes, uptimeSeconds }
```

With `selfMetrics.port` set: `GET /metrics` (JSON) and
`GET /metrics/prometheus` (text format), loopback-only by default. The
logger also self-reports health as a DEBUG record every `intervalMs`.

## Performance

`npm run bench` — Node v22, containerized hardware, 100k messages/run,
every record masked + validated + frozen + serialized:

| Path | emit latency | end-to-end throughput |
|---|---|---|
| null transport (sync pipeline) | 15.8 µs/call | ~62,000 msg/s |
| file transport (async I/O) | 15.9 µs/call | ~49,000 msg/s |
| worker threads | 3.6 µs/call | ~97,000 msg/s |

Well above the 10k msg/s target. Records are validated by a fast structural
checker on the happy path (zod runs only on failures, to produce detailed
diagnostics), the emit call never blocks on I/O, and object pooling keeps
steady-state allocation low.

## Project layout

```
src/contract/   schema (zod), levels, ids, deep-freeze
src/context/    AsyncLocalStorage manager
src/masking/    PII engine + worker-safe option serialization
src/pipeline/   ring buffer, object pool, serializer (private), worker pool, pipeline
src/transports/ base (Writable), stdout, file (rotation), http (OTLP), fallback
src/middleware/ express, fastify, koa
src/enforce/    console rewiring
examples/       web server, microservice, error handling
benchmarks/     throughput
test/           node:test suite
```

## Scripts

```bash
npm install
npm test              # build + full test suite (node:test)
npm run bench         # throughput benchmark
npm run example:web   # http server demo (port 8080, metrics on 9464)
npm run example:micro # microservice demo (workers, file transport, nesting)
npm run example:errors# resilience demo
```

## Migrating from winston / pino / bunyan

See [MIGRATION.md](./MIGRATION.md).
