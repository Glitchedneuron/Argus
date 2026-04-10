package com.argus.example;

import com.argus.logging.ArgusLogger;
import com.argus.logging.ArgusLoggerFactory;

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
 * Runnable example demonstrating all Argus log event types, including the mandatory
 * OTel envelope and the error envelope for ERROR severity events.
 *
 * <h2>Running</h2>
 * <pre>
 * # Dev — logs to console (no OTLP endpoint set)
 * mvn -pl argus-logging-example exec:java -Dexec.mainClass=com.argus.example.ExampleService
 *
 * # Production (Datadog agent on localhost:4318)
 * OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318 \
 * OTEL_SERVICE_NAME=example-service \
 * OTEL_SERVICE_VERSION=1.0.0 \
 * DEPLOYMENT_ENVIRONMENT=production \
 * mvn -pl argus-logging-example exec:java -Dexec.mainClass=com.argus.example.ExampleService
 * </pre>
 *
 * <h2>Mandatory OTel envelope (on every record)</h2>
 * <ul>
 *   <li>timestamp — set automatically by the OTel SDK at emit() time</li>
 *   <li>body — rendered from the event's body template</li>
 *   <li>severity / severityText — fixed per event definition</li>
 *   <li>service.name / service.version / deployment.environment.name — from factory config</li>
 *   <li>trace_id / span_id — auto-propagated from the active OTel span (when present)</li>
 * </ul>
 *
 * <h2>Error envelope (on ERROR / WARN records)</h2>
 * <ul>
 *   <li>exception.type, exception.message, exception.stacktrace — from the Throwable</li>
 *   <li>error.type — short category, defined on the event itself</li>
 * </ul>
 */
public class ExampleService {

    private static final ArgusLogger LOG = ArgusLoggerFactory.builder()
            .serviceName("example-service")
            .serviceVersion("1.0.0")
            .environment("development")
            .build()
            .getLogger(ExampleService.class.getName());

    // -------------------------------------------------------------------------
    // HTTP / REST
    // -------------------------------------------------------------------------

    public void handleRequest(String method, String route, int statusCode, long durationMs) {
        HttpOutcome outcome = switch (statusCode / 100) {
            case 5 -> HttpOutcome.SERVER_ERROR;
            case 4 -> HttpOutcome.CLIENT_ERROR;
            default -> HttpOutcome.SUCCESS;
        };
        LOG.emit(new HttpRequestCompletedEvent(
                method, route, statusCode, durationMs, outcome,
                outcome == HttpOutcome.SUCCESS ? null : "HTTP " + statusCode));
    }

    public void handleRequestFailure(String method, String route, long durationMs,
                                     String errorType, Throwable cause) {
        // emit(event, throwable) automatically populates exception.type,
        // exception.message, and exception.stacktrace from 'cause'.
        LOG.emit(new HttpRequestFailedEvent(
                method, route, durationMs, HttpOutcome.SERVER_ERROR, errorType,
                // error_envelope fields (exception.*) — pass null here;
                // ArgusLogger.emit(event, cause) fills them in from the Throwable.
                null, null, null, null), cause);
    }

    // -------------------------------------------------------------------------
    // Async / event-driven
    // -------------------------------------------------------------------------

    public void processMessage(String eventType, String source, String eventId, boolean success) {
        LOG.emit(new AsyncEventProcessedEvent(
                eventType, source, eventId,
                success ? AsyncOutcome.SUCCESS : AsyncOutcome.FAILURE,
                success ? null : "Consumer threw an exception",
                0, null));
    }

    public void handleEventFailure(String eventType, String source, String eventId,
                                   int retryCount, Throwable cause) {
        LOG.emit(new AsyncEventFailedEvent(
                eventType, source, eventId, retryCount,
                cause.getClass().getSimpleName(),
                // exception.* fields — filled in by ArgusLogger from the Throwable
                null, null, null), cause);
    }

    // -------------------------------------------------------------------------
    // Database
    // -------------------------------------------------------------------------

    public void recordDbQuery(String operation, String collection, long durationMs,
                              int rowsAffected, boolean found) {
        LOG.emit(new DbOperationExecutedEvent(
                operation, collection, durationMs, rowsAffected,
                found ? DataOutcome.SUCCESS : DataOutcome.NOT_FOUND, null));
    }

    public void handleDbFailure(String operation, String collection, Throwable cause) {
        LOG.emit(new DbOperationFailedEvent(
                operation, collection, "DatabaseError",
                null, null, null), cause);
    }

    // -------------------------------------------------------------------------
    // Cache
    // -------------------------------------------------------------------------

    public void recordCacheAccess(String operation, String keyPrefix, boolean hit) {
        LOG.emit(new CacheOperationResultEvent(
                operation, keyPrefix,
                hit ? DataOutcome.CACHE_HIT : DataOutcome.CACHE_MISS, null));
    }

    // -------------------------------------------------------------------------
    // Business logic
    // -------------------------------------------------------------------------

    public void recordStep(String step, String flow, String entityType, String entityId,
                           boolean success, long durationMs) {
        LOG.emit(new BusinessStepCompletedEvent(
                step, flow, entityType, entityId,
                success ? BusinessOutcome.SUCCESS : BusinessOutcome.FAILURE,
                success ? null : "Validation failed", durationMs));
    }

    public void handleStepFailure(String step, String flow, String entityType,
                                  String entityId, Throwable cause) {
        LOG.emit(new BusinessStepFailedEvent(
                step, flow, entityType, entityId,
                cause.getClass().getSimpleName(),
                null, null, null), cause);
    }

    // -------------------------------------------------------------------------

    public static void main(String[] args) throws InterruptedException {
        ExampleService svc = new ExampleService();

        System.out.println("=== Argus Logging Framework — Example Output ===\n");

        // --- INFO events (normal path) ---
        svc.handleRequest("GET",  "/api/orders/{id}", 200, 38);
        svc.handleRequest("GET",  "/api/users/{id}",  404, 12);

        svc.processMessage("order.placed",   "order-service",   "evt-001", true);

        svc.recordDbQuery("SELECT", "orders", 22, 1, true);
        svc.recordCacheAccess("GET", "user:profile:", true);
        svc.recordCacheAccess("GET", "product:detail:", false);
        svc.recordStep("validate-order", "checkout", "Order", "ord-987", true, 5);

        // --- ERROR events (failure path) —
        // ArgusLogger.emit(event, throwable) fills exception.type / message / stacktrace
        RuntimeException dbEx = new RuntimeException("Connection refused by primary");
        svc.handleDbFailure("INSERT", "orders", dbEx);

        IllegalStateException bizEx = new IllegalStateException("Inventory check failed: insufficient stock");
        svc.handleStepFailure("reserve-inventory", "checkout", "Order", "ord-988", bizEx);

        svc.handleRequestFailure("POST", "/api/payments", 1204, "PaymentGatewayError",
                new java.net.SocketTimeoutException("Read timed out after 1200ms"));

        svc.handleEventFailure("payment.capture", "payment-service", "evt-456", 3,
                new RuntimeException("Downstream returned 503"));

        // Give the BatchLogRecordProcessor time to flush when using OTLP
        Thread.sleep(3_000);
        System.out.println("\n=== Done ===");
    }
}
