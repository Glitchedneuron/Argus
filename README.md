# Argus Tracer

Lightweight distributed tracing for Java 21.  
Switchable between **OpenTelemetry** and **Datadog** backends via one environment variable.  
Both backends use the OTel SDK over OTLP/HTTP — no Datadog-specific SDK required.

---

## Modules

| Module | Artifact | Purpose |
|---|---|---|
| `argus-tracer-core` | Library JAR | `ArgusTracer` / `ArgusSpan` API + OTel · Datadog · Noop backends |
| `argus-tracer-agent` | Fat JAR | Java agent — transparent tracing, zero app-code changes |

---

## Quick Start — Without Custom Spans (Agent Mode)

Attach the agent at JVM startup. Your application code is **not touched**.

### 1. Build

```bash
mvn install
# produces: argus-tracer-agent/target/argus-tracer-agent-1.0.0-SNAPSHOT.jar
```

### 2. Run with OTel backend

```bash
ARGUS_TRACER_BACKEND=otel \
OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318 \
OTEL_SERVICE_NAME=my-service \
OTEL_SERVICE_VERSION=1.0.0 \
java -javaagent:argus-tracer-agent/target/argus-tracer-agent-1.0.0-SNAPSHOT.jar \
     -jar myapp.jar
```

### 3. Run with Datadog backend

```bash
ARGUS_TRACER_BACKEND=datadog \
DD_AGENT_HOST=datadog-agent.svc.cluster.local \
OTEL_SERVICE_NAME=my-service \
java -javaagent:argus-tracer-agent/target/argus-tracer-agent-1.0.0-SNAPSHOT.jar \
     -jar myapp.jar
```

> The Datadog agent must have OTLP/HTTP ingestion enabled on port 4318 — see
> [Backend Details](#backend-details) below.

### 4. Dev mode (no exporter — JSON to console)

```bash
ARGUS_TRACER_BACKEND=otel \
OTEL_SERVICE_NAME=my-service \
java -javaagent:argus-tracer-agent/target/argus-tracer-agent-1.0.0-SNAPSHOT.jar \
     -jar myapp.jar
```

Each completed span prints as JSON:

```json
{
  "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736",
  "span_id": "a3ce929d0e0e4736",
  "parent_span_id": "0000000000000000",
  "name": "checkout",
  "kind": "SERVER",
  "start_time": "2026-04-24T10:15:00.042Z",
  "end_time": "2026-04-24T10:15:00.117Z",
  "duration_ms": 75,
  "status": "OK",
  "resource": {"service.name": "my-service", "service.version": "1.0.0", "deployment.environment.name": "development"},
  "instrumentation_scope": {"name": "com.argus.tracer", "version": "1.0.0"},
  "attributes": {},
  "events": []
}
```

### What the agent does

1. `premain()` executes before the application's `main()`.
2. Reads `ARGUS_TRACER_BACKEND` to select the backend.
3. Calls `SdkInitializer.initialize()` to build an `OpenTelemetrySdk`.
4. Registers the SDK as `GlobalOpenTelemetry` — every OTel-aware framework on the classpath uses it.
5. Sets `argus.tracer.initialized=true` so `ArgusTracerFactory.create()` wraps `GlobalOpenTelemetry` automatically.
6. Registers a JVM shutdown hook to flush and close the exporter.

---

## Quick Start — With Custom Spans (Programmatic API)

Add `argus-tracer-core` as a dependency:

```xml
<dependency>
    <groupId>com.argus</groupId>
    <artifactId>argus-tracer-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### Get a tracer

```java
// Auto-detects backend from env vars.
// If the agent is also attached, this wraps GlobalOpenTelemetry automatically.
ArgusTracer tracer = ArgusTracerFactory.create();
```

Or configure explicitly:

```java
ArgusTracer tracer = ArgusTracerFactory.builder()
        .backend(Backend.DATADOG)
        .serviceName("order-service")
        .serviceVersion("2.0.0")
        .environment("production")
        .datadogAgentHost("datadog-agent.svc.cluster.local")
        .build();

// Flush spans on shutdown
Runtime.getRuntime().addShutdownHook(new Thread(tracer::shutdown));
```

### Custom span — try-with-resources (recommended)

```java
try (ArgusSpan span = tracer.startSpan("process-order", SpanKind.SERVER)) {
    span.tag("order.id", orderId)
        .tag("item.count", itemCount)
        .tag("express.shipping", true);

    processOrder(orderId);
} // span ends and is exported here
```

### Custom span — inline wrap (returns a value)

```java
Order order = tracer.trace("fetch-order", () -> repo.findById(orderId));
```

### Custom span — inline wrap (void)

```java
tracer.trace("publish-event", () -> eventBus.publish(event));
```

If the block throws a `RuntimeException`, the span is automatically marked `ERROR` and the exception is re-thrown.

### Custom span — error recording

```java
try (ArgusSpan span = tracer.startSpan("charge-payment", SpanKind.CLIENT)) {
    try {
        gateway.charge(amount);
        span.setStatus(SpanStatus.OK);
    } catch (PaymentException e) {
        span.recordException(e)                          // adds exception.* attributes
            .setStatus(SpanStatus.ERROR, e.getMessage()); // shown in APM UI
        throw e;
    }
}
```

### Custom span — nested (parent → child)

```java
try (ArgusSpan parent = tracer.startSpan("checkout", SpanKind.SERVER)) {
    parent.tag("order.id", orderId);

    // Child span — automatically linked to parent via OTel context
    try (ArgusSpan child = tracer.startSpan("validate-stock", SpanKind.INTERNAL)) {
        child.tag("warehouse.id", warehouseId);
        inventory.checkStock(orderId);
    }

    payment.charge(orderId);
}
```

### Reading trace / span IDs

Useful for injecting into outbound headers or attaching to log records manually:

```java
try (ArgusSpan span = tracer.startSpan("call-upstream", SpanKind.CLIENT)) {
    httpRequest.setHeader("X-Trace-Id", span.traceId()); // 32-hex W3C format
    httpRequest.setHeader("X-Span-Id",  span.spanId());  // 16-hex W3C format
    upstream.call(httpRequest);
}

// Or read the currently active span from OTel context
String traceId = tracer.currentTraceId();
String spanId  = tracer.currentSpanId();
```

### Agent + custom spans combined

Attach the agent for transparent tracing AND use `ArgusTracerFactory.create()` for
your own custom spans. They join the same trace automatically:

```bash
ARGUS_TRACER_BACKEND=otel \
OTEL_SERVICE_NAME=order-service \
java -javaagent:argus-tracer-agent.jar -jar order-service.jar
```

```java
// create() detects argus.tracer.initialized=true and wraps GlobalOpenTelemetry
private static final ArgusTracer TRACER = ArgusTracerFactory.create();

public void handleRequest(String orderId) {
    try (ArgusSpan span = TRACER.startSpan("handle-order", SpanKind.SERVER)) {
        span.tag("order.id", orderId);
        processOrder(orderId);
    }
}
```

---

## Backend Details

Both backends use the **OTel SDK** internally — the difference is where OTLP is sent and which resource attributes are added.

| Backend | Protocol | Sends to | Extra resource attrs |
|---|---|---|---|
| `OTEL` | OTLP/HTTP | `OTEL_EXPORTER_OTLP_ENDPOINT` or JSON console | — |
| `DATADOG` | OTLP/HTTP | `http://$DD_AGENT_HOST:4318` | `dd.service`, `dd.env`, `dd.version` |
| `NOOP` | — | Discarded | — |

### Datadog agent — enable OTLP ingestion

Add to `datadog.yaml`:

```yaml
otlp_config:
  receiver:
    protocols:
      http:
        endpoint: "0.0.0.0:4318"
```

Restart the agent. Verify with:

```bash
curl -s http://localhost:4318/v1/traces -H "Content-Type: application/json" -d '{}' | head -c 100
```

---

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `ARGUS_TRACER_BACKEND` | `noop` (agent) / auto (code) | `otel`, `datadog`, or `noop` |
| `OTEL_SERVICE_NAME` | `unknown-service` | Service name on all spans |
| `OTEL_SERVICE_VERSION` | `unknown` | Service version |
| `DEPLOYMENT_ENVIRONMENT` | `development` | Deployment environment (also reads `APP_ENV` as fallback) |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | console | OTLP/HTTP endpoint URL (OTel backend) |
| `DD_AGENT_HOST` | `localhost` | Datadog agent hostname (Datadog backend) |

### Programmatic backend auto-detection order

When no backend is explicitly set in `ArgusTracerFactory.builder()`:

1. `argus.tracer.backend` system property
2. `ARGUS_TRACER_BACKEND` environment variable
3. `DD_AGENT_HOST` or `DD_TRACE_AGENT_URL` set → `DATADOG`
4. `OTEL_EXPORTER_OTLP_ENDPOINT` set → `OTEL`
5. Fallback → `OTEL` with JSON console (dev mode)

**Agent default is `NOOP`** — the agent is inert unless `ARGUS_TRACER_BACKEND` is set.
This makes it safe to attach in any environment without risk of unexpected behaviour.

---

## Span API Reference

```java
ArgusSpan span = tracer.startSpan("name", SpanKind.SERVER);

span.tag("key", "value")              // String attribute
    .tag("key", 42L)                  // Long attribute
    .tag("key", true)                 // Boolean attribute
    .recordException(throwable)       // Records exception.* attributes, sets ERROR status
    .setStatus(SpanStatus.OK)         // Explicit status (OK / ERROR / UNSET)
    .setStatus(SpanStatus.ERROR, "msg")
    .traceId()                        // W3C 32-hex trace ID (empty string when noop)
    .spanId()                         // W3C 16-hex span ID  (empty string when noop)
    .end();                           // End span + release context (idempotent)
```

| `SpanKind` | Use case |
|---|---|
| `INTERNAL` | Default — work within the same service |
| `SERVER` | Inbound HTTP / RPC request handler |
| `CLIENT` | Outbound HTTP / RPC call to another service |
| `PRODUCER` | Publishing a message to a broker |
| `CONSUMER` | Consuming / processing a message |
