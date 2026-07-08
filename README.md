# Argus — Structured Logging + Distributed Tracing for Java

Contract-first observability framework built on OpenTelemetry.  
A YAML contract is the single source of truth; a Maven plugin generates Java 21 records at build time.  
At runtime records are emitted via the OTel SDK — to an OTLP collector in production, or to a pretty JSON console in dev.

---

## Modules

| Module | Purpose |
|---|---|
| `argus-logging-codegen` | Maven plugin — generates Java records from `log-contract.yaml` |
| `argus-logging-core` | Logging runtime — `ArgusLogger`, `ArgusLoggerFactory`, OTel bridge |
| `argus-tracing` | Tracing facade — `ArgusTracer`, `ArgusSpan`, `ArgusAgent` |
| `argus-tracing-agent` | Fat Java agent JAR — zero-code transparent tracing via `-javaagent` |
| `argus-logging-example` | Runnable demo of all log event types |
| [`argus-node`](./argus-node/README.md) | Argus for Node.js — contract-enforced OTel logging (TypeScript, AsyncLocalStorage, Worker Threads, PII masking) |

---

## Quick Start — Logging Only

### 1. Add the dependency

```xml
<dependency>
    <groupId>com.argus</groupId>
    <artifactId>argus-logging-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. Plug in the codegen plugin and point it at your contract

```xml
<plugin>
    <groupId>com.argus</groupId>
    <artifactId>argus-logging-codegen</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <executions>
        <execution>
            <goals><goal>generate</goal></goals>
            <configuration>
                <contractFile>${project.basedir}/contracts/log-contract.yaml</contractFile>
            </configuration>
        </execution>
    </executions>
</plugin>
```

### 3. Obtain a logger and emit events

```java
private static final ArgusLogger LOG = ArgusLoggerFactory.builder()
        .serviceName("order-service")
        .serviceVersion("2.0.0")
        .environment("production")
        .build()
        .getLogger(OrderService.class.getName());

// Emit a contract-defined event
LOG.emit(new HttpRequestCompletedEvent("GET", "/orders/{id}", 200, 38L, HttpOutcome.SUCCESS, null));

// Emit with a Throwable — exception.* attributes populated automatically
LOG.emit(new HttpRequestFailedEvent("POST", "/payments", 1204L,
         HttpOutcome.SERVER_ERROR, "PaymentGatewayError", null, null, null), cause);
```

In dev (no `OTEL_EXPORTER_OTLP_ENDPOINT` set) each record prints as JSON to stdout:

```json
{
  "timestamp": "2026-04-24T10:15:30.042Z",
  "severity_number": 9,
  "severity_text": "INFO",
  "event_name": "http.request.completed",
  "body": "GET /orders/{id} -> 200 [SUCCESS] in 38ms",
  "trace_id": null,
  "span_id": null,
  "resource": { "service.name": "order-service", "service.version": "2.0.0", "deployment.environment.name": "production" },
  "instrumentation_scope": { "name": "com.argus.logging", "version": "1.0.0" },
  "attributes": { "http.method": "GET", "http.route": "/orders/{id}", "http.response.status_code": 200, "duration.ms": 38, "outcome": "SUCCESS" }
}
```

### 4. Production — send to an OTLP collector

```bash
OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318 \
OTEL_SERVICE_NAME=order-service \
OTEL_SERVICE_VERSION=2.0.0 \
DEPLOYMENT_ENVIRONMENT=production \
java -jar order-service.jar
```

---

## Quick Start — Logging + Tracing (Programmatic API)

Use `ArgusAgent` when you want a unified SDK for both logging and tracing in your application code.

### 1. Add the tracing dependency

```xml
<dependency>
    <groupId>com.argus</groupId>
    <artifactId>argus-tracing</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. Bootstrap the agent at startup

```java
ArgusAgent agent = ArgusAgent.builder()
        .serviceName("order-service")
        .serviceVersion("2.0.0")
        .environment("production")
        // backend auto-detected from env vars (see Backend Selection below)
        .build();

private static final ArgusTracer TRACER = agent.tracer();
private static final ArgusLogger LOG    = agent.logger(OrderService.class.getName());

// Flush on shutdown
Runtime.getRuntime().addShutdownHook(new Thread(agent::shutdown));
```

### 3. Use the tracer

```java
// try-with-resources — span ends automatically
try (ArgusSpan span = TRACER.startSpan("process-order", SpanKind.SERVER)) {
    span.tag("order.id", orderId)
        .tag("customer.tier", "gold");

    processOrder(orderId);   // any LOG.emit() here gets trace_id/span_id automatically
}

// Inline wrap — Supplier<T>
Order order = TRACER.trace("fetch-order", () -> orderRepo.findById(orderId));

// Inline wrap — Runnable (void)
TRACER.trace("publish-event", () -> eventBus.publish(event));

// Error handling — recordException marks the span ERROR and re-throws
try (ArgusSpan span = TRACER.startSpan("charge-payment")) {
    try {
        paymentGateway.charge(amount);
    } catch (PaymentException e) {
        span.recordException(e)
            .setStatus(SpanStatus.ERROR, "Payment gateway rejected charge");
        throw e;
    }
}

// Read the active trace/span IDs (e.g. to inject into an outbound header)
String traceId = TRACER.currentTraceId();  // 32-hex W3C format
String spanId  = TRACER.currentSpanId();   // 16-hex W3C format
```

**Log-trace correlation is automatic.** Any `LOG.emit()` call made while a span is open carries the same `trace_id` and `span_id` — no extra wiring needed.

---

## Quick Start — Transparent Tracing (Zero Code Changes)

Ops teams can add tracing to any service **without touching application code** by attaching the Argus tracing agent as a `-javaagent`.

### 1. Build the agent JAR

```bash
mvn install -pl argus-logging-codegen,argus-logging-core,argus-tracing,argus-tracing-agent
# Output: argus-tracing-agent/target/argus-tracing-agent-1.0.0-SNAPSHOT.jar
```

### 2. Attach at JVM startup

**OTel backend** — sends traces and logs to an OTel Collector:
```bash
ARGUS_TRACER_BACKEND=otel \
OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318 \
OTEL_SERVICE_NAME=order-service \
java -javaagent:argus-tracing-agent.jar -jar order-service.jar
```

**Datadog backend** — sends OTLP to the Datadog agent:
```bash
ARGUS_TRACER_BACKEND=datadog \
DD_AGENT_HOST=datadog-agent.svc.cluster.local \
OTEL_SERVICE_NAME=order-service \
java -javaagent:argus-tracing-agent.jar -jar order-service.jar
```

The agent:
1. Runs `premain()` before the application's `main()`
2. Builds an `OpenTelemetrySdk` with both logging and tracing providers
3. Registers it as `GlobalOpenTelemetry`
4. Sets the system property `argus.agent.initialized=true`
5. `ArgusLoggerFactory.create()` detects this and delegates to `GlobalOpenTelemetry.get()` — every subsequent `LOG.emit()` call automatically carries `trace_id` / `span_id` from whatever span is active

The application team adds **zero tracing code**.

---

## Backend Selection

Both OTEL and DATADOG backends use the OTel SDK internally. The only differences are the OTLP endpoint and, for Datadog, additional Unified Service Tagging resource attributes.

| Backend | Sends to | How to select |
|---|---|---|
| `OTEL` | `OTEL_EXPORTER_OTLP_ENDPOINT`, or JSON console in dev | Default when no DD vars are set |
| `DATADOG` | `http://$DD_AGENT_HOST:4318` (Datadog agent OTLP port) | Set `DD_AGENT_HOST` or `ARGUS_TRACER_BACKEND=datadog` |
| `NOOP` | Discarded, zero overhead | `ARGUS_TRACER_BACKEND=noop` |

### Selection order (programmatic API)

1. `ArgusAgent.builder().backend(Backend.DATADOG)` — explicit override
2. `argus.tracer.backend` system property
3. `ARGUS_TRACER_BACKEND` environment variable
4. `DD_AGENT_HOST` or `DD_TRACE_AGENT_URL` set → `DATADOG`
5. `OTEL_EXPORTER_OTLP_ENDPOINT` set → `OTEL`
6. Fallback → `OTEL` with console output (dev mode)

### Selection order (Java agent)

Same as above except the fallback is `NOOP` — the agent does nothing when `ARGUS_TRACER_BACKEND` is absent, making it safe to attach in any environment.

---

## Environment Variables

| Variable | Used by | Default | Description |
|---|---|---|---|
| `ARGUS_TRACER_BACKEND` | agent, programmatic | `noop` (agent) / auto (code) | `otel`, `datadog`, or `noop` |
| `OTEL_SERVICE_NAME` | all | `unknown-service` | Service name on all telemetry |
| `OTEL_SERVICE_VERSION` | all | `unknown` | Service version |
| `DEPLOYMENT_ENVIRONMENT` | all | `development` | Deployment environment (also reads `APP_ENV` as fallback) |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | OTEL backend | console | OTLP/HTTP endpoint URL, e.g. `http://collector:4318` |
| `DD_AGENT_HOST` | DATADOG backend | `localhost` | Hostname of the Datadog agent (OTLP ingestion on port 4318) |

---

## Writing a Log Contract

Contracts live in `contracts/log-contract.yaml`. The codegen plugin turns each event into a Java 21 record implementing `LogEvent`.

```yaml
version: "1.0"
namespace: "com.example.logging.events"

string_limits:
  default_max_length: 512

enums:
  OrderOutcome:
    description: "Outcome of an order operation"
    values: [SUCCESS, FAILED, CANCELLED]

events:
  order.placed:
    severity: INFO
    description: "A new order was successfully placed"
    body: "Order {order.id} placed by {customer.id} for ${amount}"
    attributes:
      order.id:
        type: string
        required: true
        max_length: 64
      customer.id:
        type: string
        required: true
      amount:
        type: double
        required: true
      outcome:
        type: enum
        enum: OrderOutcome
        required: true
```

Generated record:

```java
public record OrderPlacedEvent(
        String orderId,       // order.id, required, max=64
        String customerId,    // customer.id, required
        double amount,        // amount, required
        OrderOutcome outcome  // outcome, required
) implements LogEvent { ... }
```

**ERROR and WARN** events automatically get `exception.type`, `exception.message`, and `exception.stacktrace` fields appended from the `error_envelope` section.

---

## Building

```bash
# First-time — install codegen plugin, then build everything
mvn install

# Skip example module
mvn install -pl argus-logging-codegen,argus-logging-core,argus-tracing,argus-tracing-agent

# Run the example (logs to console)
mvn -pl argus-logging-example exec:java -Dexec.mainClass=com.argus.example.ExampleService
```

---

## Datadog Agent Setup

The Datadog backend sends OTLP/HTTP to the Datadog agent on port 4318. Enable OTLP ingestion in `datadog.yaml`:

```yaml
otlp_config:
  receiver:
    protocols:
      http:
        endpoint: "0.0.0.0:4318"
```

See `docs/datadog-agent.yaml` for a complete reference configuration.

---

## Architecture

```
contracts/log-contract.yaml
        │
        ▼ (mvn generate-sources)
argus-logging-codegen
        │  generates Java 21 records
        ▼
argus-logging-core          argus-tracing
  ArgusLoggerFactory    ◄──  ArgusAgent / ArgusTracerFactory
  ArgusLogger               ArgusTracer / ArgusSpan
  OtelInitializer       ◄──  UnifiedSdkInitializer
        │                          │
        ▼                          ▼
  OTel SDK (SdkLoggerProvider)   OTel SDK (SdkTracerProvider)
        │                          │
        └──────────┬───────────────┘
                   ▼
          OTLP/HTTP exporter
          (or JSON console in dev)
                   │
         ┌─────────┴─────────┐
         ▼                   ▼
   OTel Collector       Datadog agent
                        (OTLP port 4318)

argus-tracing-agent  (fat JAR, -javaagent)
  premain() → UnifiedSdkInitializer → GlobalOpenTelemetry.set(sdk)
            → System.setProperty("argus.agent.initialized", "true")
            → ArgusLoggerFactory.create() picks up GlobalOpenTelemetry automatically
```
