# Migrating to `argus-logging-node`

This guide maps winston, pino, and bunyan idioms to Argus. The core mental
shift: **the format is not yours to configure.** Argus emits exactly one
shape — the OTel-aligned contract record — and your migration is mostly
deleting formatter code.

## TL;DR mapping

| You had | You write now |
|---|---|
| `winston.createLogger({...})` / `pino()` / `bunyan.createLogger()` | `createLogger({ service: 'my-svc' })` |
| `logger.log('info', msg)` | `logger.info(msg)` |
| `logger.info({ userId }, 'msg')` (pino arg order) | `logger.info('msg', { userId })` |
| `logger.child({ component })` | `logger.child({ component })` (same) |
| `logger.level = 'debug'` at runtime | **removed by design** — set `level` (or `LOG_LEVEL`) at creation |
| formats / `printf` / `prettyPrint` / serializers | **removed by design** — the contract is the format |
| winston transports / pino destinations | `StdoutTransport`, `FileTransport`, `HttpTransport`, or subclass `Transport` |
| `pino-http`, `express-winston` | `expressMiddleware(logger)`, `argusFastifyPlugin(logger)`, `koaMiddleware(logger)` |
| redact paths (`pino({ redact })`) | on by default: PII auto-masking + `masking.customPatterns` / `customFieldMatchers` |
| `logger.flush()` (pino) | `await logger.flush()` / `await logger.shutdown()` |

## From winston

```js
// BEFORE
const winston = require('winston');
const logger = winston.createLogger({
  level: 'info',
  format: winston.format.combine(winston.format.timestamp(), winston.format.json()),
  defaultMeta: { service: 'auth' },
  transports: [
    new winston.transports.Console(),
    new winston.transports.File({ filename: 'app.log', maxsize: 5e6, maxFiles: 3 }),
  ],
});
logger.info('User login', { userId: 123 });
```

```js
// AFTER
const { createLogger, StdoutTransport, FileTransport } = require('argus-logging-node');
const logger = createLogger({
  service: 'auth',
  level: 'INFO',
  transports: [
    new StdoutTransport(),
    new FileTransport({ path: 'app.log', maxBytes: 5e6, maxFiles: 3 }),
  ],
});
logger.info('User login', { userId: 123 });
```

Deleted, not migrated: `format.combine`, `format.timestamp`, `format.printf`,
`format.colorize`. Timestamps, shape, and key order are contract-fixed.
Custom winston transports port by subclassing `Transport` and implementing
`writeEnvelope({ record, line })` — you receive a finished line; there is no
`format` to apply.

## From pino

```js
// BEFORE
const pino = require('pino');
const logger = pino({
  level: 'info',
  redact: ['password', 'req.headers.authorization'],
  serializers: { err: pino.stdSerializers.err },
});
logger.info({ userId: 123 }, 'User login');
logger.error({ err }, 'failed');
```

```js
// AFTER
const { createLogger } = require('argus-logging-node');
const logger = createLogger({ service: 'auth', level: 'INFO' });
logger.info('User login', { userId: 123 });     // message first, then context
logger.error('failed', err);                     // Error becomes error.{type,message,stacktrace}
```

- **Argument order flips**: pino is `(obj, msg)`, Argus is `(msg, context, extras?)`.
- `redact` is unnecessary for common PII (auto-masked); for bespoke paths use
  `masking.customFieldMatchers` or `masking.customPatterns`.
- `serializers` have no equivalent **on purpose**. Error serialization is
  built in; everything else must be plain JSON-safe data.
- pino's `transport: { target }` worker offload ≈ `workers: { enabled: true }`
  plus a `Transport` instance.
- `pino-http` → `expressMiddleware(logger)`; you also gain automatic
  traceId/spanId/requestId propagation via AsyncLocalStorage.

## From bunyan

```js
// BEFORE
const bunyan = require('bunyan');
const logger = bunyan.createLogger({
  name: 'auth',
  streams: [{ level: 'info', path: '/var/log/app.log' }],
});
logger.info({ userId: 123 }, 'User login');
```

```js
// AFTER
const { createLogger, FileTransport } = require('argus-logging-node');
const logger = createLogger({
  service: 'auth',                      // bunyan `name` → `service`
  transports: [new FileTransport({ path: '/var/log/app.log' })],
});
logger.info('User login', { userId: 123 });
```

- Bunyan levels `trace/debug/info/warn/error/fatal` → contract levels
  `DEBUG/INFO/WARN/ERROR/FATAL` (`trace` maps to `DEBUG`).
- `log.child({ component })` works the same: `logger.child({ component })`.
- Bunyan serializers (`req`, `res`, `err`): `err` is built in; for req/res
  use the middleware (which logs method/path/status/duration for you).

## Level semantics

| winston | pino | bunyan | Argus |
|---|---|---|---|
| silly/verbose/debug | trace/debug | trace/debug | `DEBUG` |
| info/http | info | info | `INFO` |
| warn | warn | warn | `WARN` |
| error | error | error | `ERROR` |
| — | fatal | fatal | `FATAL` |

## Things that will surprise you (by design)

1. **No runtime level changes.** Level is fixed at `createLogger()`. Use the
   `LOG_LEVEL` env var per deployment instead of flipping levels in code.
2. **Records are frozen.** If old code mutated log objects after logging,
   that write now throws in strict mode. Log the final data instead.
3. **Non-JSON-safe context is rejected.** Functions, class instances,
   circular structures → the record is rejected and a diagnostic ERROR
   record with the violation reasons is emitted (your app never crashes).
4. **PII disappears.** Emails, tokens, card numbers etc. are masked. If a
   field is a false positive, add its dot-path to `masking.allowList`
   (e.g. `'context.ip'`) — allow-listing is explicit and auditable.
5. **`console.log` still exists** — until you opt in to
   `enforceConsoleContract(logger)`, which rewires and freezes `console`.
   Recommended in production entrypoints.

## Rollout checklist

1. Add `argus-logging-node`; create one logger per service entrypoint.
2. Replace logger construction (table above); delete formatter/serializer code.
3. Flip pino-style `(obj, msg)` call sites to `(msg, context)`.
4. Add HTTP middleware; delete manual request-id plumbing.
5. Point `HttpTransport` at your OTel collector (`/v1/logs`).
6. Run your suite: contract rejections show up as diagnostic ERROR records
   with `argus.rejection.issues` explaining each violation.
7. Add `enforceConsoleContract(logger)` to the entrypoint.
8. Wire `/metrics` into your monitoring; alert on `dropped` and `transportErrors`.
