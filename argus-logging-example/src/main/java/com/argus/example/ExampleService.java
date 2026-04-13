package com.argus.example;

import com.argus.logging.ArgusLogger;
import com.argus.logging.ArgusLoggerFactory;
import com.argus.tracing.ArgusSpan;
import com.argus.tracing.ArgusTracer;
import com.argus.tracing.ArgusTracerFactory;
import com.argus.tracing.SpanKind;
import com.argus.tracing.SpanStatus;

// Generated records — produced by argus-logging-codegen from contracts/log-contract.yaml
import com.argus.logging.events.AsyncEventFailedEvent;
import com.argus.logging.events.AsyncEventProcessedEvent;
import com.argus.logging.events.AsyncOutcome;
import com.argus.logging.events.BusinessOutcome;
import com.argus.logging.events.BusinessStepCompletedEvent;
import com.argus.logging.events.BusinessStepFailedEvent;
import com.argus.logging.events.CacheOperationResultEvent;
import com.argus.logging.events.DataOutcome;
import com.argus.logging.events.DbOperationExecutedEvent;
import com.argus.logging.events.DbOperationFailedEvent;
import com.argus.logging.events.HttpOutcome;
import com.argus.logging.events.HttpRequestCompletedEvent;
import com.argus.logging.events.HttpRequestFailedEvent;

/**
 * Runnable example demonstrating Argus structured logging + the Argus tracing facade.
 *
 * <h2>Running (dev — logs + spans to console)</h2>
 * <pre>
 * mvn -pl argus-logging-example exec:java -Dexec.mainClass=com.argus.example.ExampleService
 * </pre>
 *
 * <h2>Running (production — OTel backend → Datadog agent on localhost:4318)</h2>
 * <pre>
 * OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318 \
 * OTEL_SERVICE_NAME=example-service \
 * OTEL_SERVICE_VERSION=1.0.0 \
 * DEPLOYMENT_ENVIRONMENT=production \
 * mvn -pl argus-logging-example exec:java -Dexec.mainClass=com.argus.example.ExampleService
 * </pre>
 *
 * <h2>Running (Datadog native tracer backend)</h2>
 * <pre>
 * ARGUS_TRACER_BACKEND=datadog \
 * java -javaagent:dd-java-agent.jar \
 *      -Ddd.service=example-service \
 *      -Ddd.env=production \
 *      -jar example.jar
 * </pre>
 *
 * <h2>Log ↔ trace correlation</h2>
 * <p>When the OTel backend is active, any {@code ArgusLogger.emit()} call made while a
 * tracer span is open will automatically include the same {@code trace_id} and
 * {@code span_id} in the log record — no manual wiring required.</p>
 */
public class ExampleService {

    // --------------------------------------------------------------------------
    // One factory for logging, one for tracing.
    //
    // In production you can share a single OpenTelemetry instance between both
    // factories so logging and tracing share the same resource / exporter pipeline:
    //
    //   OpenTelemetry otel = buildSharedOtelSdk();
    //   ArgusLoggerFactory.builder().withOpenTelemetry(otel).build();
    //   ArgusTracerFactory.builder().withOpenTelemetry(otel).build();
    //
    // For this demo each factory creates its own SDK instance (fine for an example).
    // --------------------------------------------------------------------------

    private static final ArgusLogger LOG = ArgusLoggerFactory.builder()
            .serviceName("example-service")
            .serviceVersion("1.0.0")
            .environment("development")
            .build()
            .getLogger(ExampleService.class.getName());

    /**
     * Tracer — backend auto-detected from env vars.
     * Override with: -Dargus.tracer.backend=otel|datadog|noop
     */
    private static final ArgusTracer TRACER = ArgusTracerFactory.builder()
            .serviceName("example-service")
            .serviceVersion("1.0.0")
            .environment("development")
            .build();

    // -------------------------------------------------------------------------
    // HTTP / REST
    // -------------------------------------------------------------------------

    public void handleRequest(String method, String route, int statusCode, long durationMs) {
        // Wrap the entire request in a SERVER span — the log emitted inside will
        // automatically carry the same trace_id and span_id (OTel backend).
        try (ArgusSpan span = TRACER.startSpan("http.server.request", SpanKind.SERVER)) {
            span.tag("http.method", method)
                .tag("http.route", route)
                .tag("http.response.status_code", (long) statusCode);

            HttpOutcome outcome = switch (statusCode / 100) {
                case 5 -> HttpOutcome.SERVER_ERROR;
                case 4 -> HttpOutcome.CLIENT_ERROR;
                default -> HttpOutcome.SUCCESS;
            };
            if (outcome == HttpOutcome.SERVER_ERROR) {
                span.setStatus(SpanStatus.ERROR, "HTTP " + statusCode);
            }

            LOG.emit(new HttpRequestCompletedEvent(
                    method, route, statusCode, durationMs, outcome,
                    outcome == HttpOutcome.SUCCESS ? null : "HTTP " + statusCode));
        }
    }

    public void handleRequestFailure(String method, String route, long durationMs,
                                     String errorType, Throwable cause) {
        try (ArgusSpan span = TRACER.startSpan("http.server.request", SpanKind.SERVER)) {
            span.tag("http.method", method)
                .tag("http.route", route)
                .recordException(cause)
                .setStatus(SpanStatus.ERROR, cause.getMessage());

            // emit(event, throwable) automatically populates exception.type,
            // exception.message, and exception.stacktrace from 'cause'.
            LOG.emit(new HttpRequestFailedEvent(
                    method, route, durationMs, HttpOutcome.SERVER_ERROR, errorType,
                    null, null, null, null), cause);
        }
    }

    // -------------------------------------------------------------------------
    // Async / event-driven
    // -------------------------------------------------------------------------

    public void processMessage(String eventType, String source, String eventId, boolean success) {
        try (ArgusSpan span = TRACER.startSpan("async.event.process", SpanKind.CONSUMER)) {
            span.tag("event.type", eventType)
                .tag("event.source", source);

            LOG.emit(new AsyncEventProcessedEvent(
                    eventType, source, eventId,
                    success ? AsyncOutcome.SUCCESS : AsyncOutcome.FAILURE,
                    success ? null : "Consumer threw an exception",
                    0, null));

            if (!success) span.setStatus(SpanStatus.ERROR);
        }
    }

    public void handleEventFailure(String eventType, String source, String eventId,
                                   int retryCount, Throwable cause) {
        try (ArgusSpan span = TRACER.startSpan("async.event.process", SpanKind.CONSUMER)) {
            span.tag("event.type", eventType)
                .tag("retry.count", (long) retryCount)
                .recordException(cause);

            LOG.emit(new AsyncEventFailedEvent(
                    eventType, source, eventId, retryCount,
                    cause.getClass().getSimpleName(),
                    null, null, null), cause);
        }
    }

    // -------------------------------------------------------------------------
    // Database
    // -------------------------------------------------------------------------

    public void recordDbQuery(String operation, String collection, long durationMs,
                              int rowsAffected, boolean found) {
        try (ArgusSpan span = TRACER.startSpan("db.query", SpanKind.CLIENT)) {
            span.tag("db.operation.name", operation)
                .tag("db.collection.name", collection);

            LOG.emit(new DbOperationExecutedEvent(
                    operation, collection, durationMs, rowsAffected,
                    found ? DataOutcome.SUCCESS : DataOutcome.NOT_FOUND, null));
        }
    }

    public void handleDbFailure(String operation, String collection, Throwable cause) {
        try (ArgusSpan span = TRACER.startSpan("db.query", SpanKind.CLIENT)) {
            span.tag("db.operation.name", operation)
                .tag("db.collection.name", collection)
                .recordException(cause);

            LOG.emit(new DbOperationFailedEvent(
                    operation, collection, "DatabaseError",
                    null, null, null), cause);
        }
    }

    // -------------------------------------------------------------------------
    // Cache
    // -------------------------------------------------------------------------

    public void recordCacheAccess(String operation, String keyPrefix, boolean hit) {
        // Inline trace() wrapper — span ends automatically when the lambda returns.
        TRACER.trace("cache." + operation.toLowerCase(), () ->
            LOG.emit(new CacheOperationResultEvent(
                    operation, keyPrefix,
                    hit ? DataOutcome.CACHE_HIT : DataOutcome.CACHE_MISS, null))
        );
    }

    // -------------------------------------------------------------------------
    // Business logic
    // -------------------------------------------------------------------------

    public void recordStep(String step, String flow, String entityType, String entityId,
                           boolean success, long durationMs) {
        try (ArgusSpan span = TRACER.startSpan("business." + step)) {
            span.tag("step.name", step)
                .tag("flow.name", flow);

            LOG.emit(new BusinessStepCompletedEvent(
                    step, flow, entityType, entityId,
                    success ? BusinessOutcome.SUCCESS : BusinessOutcome.FAILURE,
                    success ? null : "Validation failed", durationMs));

            if (!success) span.setStatus(SpanStatus.ERROR, "Validation failed");
        }
    }

    public void handleStepFailure(String step, String flow, String entityType,
                                  String entityId, Throwable cause) {
        try (ArgusSpan span = TRACER.startSpan("business." + step)) {
            span.tag("step.name", step)
                .tag("flow.name", flow)
                .recordException(cause);

            LOG.emit(new BusinessStepFailedEvent(
                    step, flow, entityType, entityId,
                    cause.getClass().getSimpleName(),
                    null, null, null), cause);
        }
    }

    // -------------------------------------------------------------------------
    // Custom span demo — no contract event needed
    // -------------------------------------------------------------------------

    /**
     * Demonstrates a fully manual custom span with explicit tags, status, and ID
     * access — useful for operations that don't map to any contract-defined log event.
     */
    public void runCustomSpan() {
        ArgusSpan span = TRACER.startSpan("custom.enrichment-pipeline", SpanKind.INTERNAL);
        try {
            span.tag("pipeline.stage", "normalise")
                .tag("pipeline.batch_size", 500L)
                .tag("pipeline.dry_run", false);

            // Manual access to trace / span IDs — e.g. for propagating to a
            // downstream system that doesn't support OTel context propagation.
            System.out.println("[custom span]  trace_id=" + span.traceId()
                    + "  span_id=" + span.spanId());

            span.setStatus(SpanStatus.OK);
        } catch (RuntimeException e) {
            span.recordException(e).setStatus(SpanStatus.ERROR, e.getMessage());
            throw e;
        } finally {
            span.end();  // explicit end — not try-with-resources so we can rethrow
        }
    }

    // -------------------------------------------------------------------------

    public static void main(String[] args) throws InterruptedException {
        ExampleService svc = new ExampleService();

        System.out.println("=== Argus Logging + Tracing Framework — Example Output ===\n");
        System.out.println("Active tracer backend: " + detectBackend());
        System.out.println();

        // ---- INFO / DEBUG events (normal path) ---
        svc.handleRequest("GET", "/api/orders/{id}", 200, 38);
        svc.handleRequest("GET", "/api/users/{id}",  404, 12);

        svc.processMessage("order.placed", "order-service", "evt-001", true);

        svc.recordDbQuery("SELECT", "orders", 22, 1, true);
        svc.recordCacheAccess("GET", "user:profile:", true);
        svc.recordCacheAccess("GET", "product:detail:", false);
        svc.recordStep("validate-order", "checkout", "Order", "ord-987", true, 5);

        // ---- Custom span (no contract event) ---
        svc.runCustomSpan();

        // ---- ERROR events (failure path) ---
        RuntimeException dbEx = new RuntimeException("Connection refused by primary");
        svc.handleDbFailure("INSERT", "orders", dbEx);

        IllegalStateException bizEx = new IllegalStateException(
                "Inventory check failed: insufficient stock");
        svc.handleStepFailure("reserve-inventory", "checkout", "Order", "ord-988", bizEx);

        svc.handleRequestFailure("POST", "/api/payments", 1204, "PaymentGatewayError",
                new java.net.SocketTimeoutException("Read timed out after 1200ms"));

        svc.handleEventFailure("payment.capture", "payment-service", "evt-456", 3,
                new RuntimeException("Downstream returned 503"));

        // Give BatchLogRecordProcessor / BatchSpanProcessor time to flush when using OTLP
        Thread.sleep(3_000);
        TRACER.shutdown();
        System.out.println("\n=== Done ===");
    }

    /** Mirrors ArgusTracerFactory auto-detect so we can print it before tracer construction. */
    private static String detectBackend() {
        String prop = System.getProperty("argus.tracer.backend");
        if (prop != null && !prop.isBlank()) return prop.toUpperCase();
        String env = System.getenv("ARGUS_TRACER_BACKEND");
        if (env != null && !env.isBlank()) return env.toUpperCase();
        if (System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT") != null) return "OTEL (OTLP → " + System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT") + ")";
        if (System.getenv("DD_AGENT_HOST") != null) return "DATADOG";
        return "OTEL (console / dev mode)";
    }
}
