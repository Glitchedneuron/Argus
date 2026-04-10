package com.argus.example;

import com.argus.logging.ArgusLogger;
import com.argus.logging.ArgusLoggerFactory;

// Generated records — produced by argus-logging-codegen from contracts/log-contract.yaml
import com.argus.logging.events.AsyncEventProcessedEvent;
import com.argus.logging.events.AsyncOutcome;
import com.argus.logging.events.BusinessOutcome;
import com.argus.logging.events.BusinessStepCompletedEvent;
import com.argus.logging.events.CacheOperationResultEvent;
import com.argus.logging.events.DataOutcome;
import com.argus.logging.events.DbOperationExecutedEvent;
import com.argus.logging.events.HttpOutcome;
import com.argus.logging.events.HttpRequestCompletedEvent;

/**
 * Runnable example demonstrating all Argus log event types.
 *
 * <h2>Running</h2>
 * <pre>
 * # Dev — logs to console
 * mvn -pl argus-logging-example exec:java -Dexec.mainClass=com.argus.example.ExampleService
 *
 * # Production (Datadog agent on localhost:4318)
 * OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318 \
 * OTEL_SERVICE_NAME=example-service \
 * OTEL_SERVICE_VERSION=1.0.0 \
 * DEPLOYMENT_ENVIRONMENT=production \
 * mvn -pl argus-logging-example exec:java -Dexec.mainClass=com.argus.example.ExampleService
 * </pre>
 */
public class ExampleService {

    // One factory per application, one logger per class (lightweight).
    private static final ArgusLogger LOG = ArgusLoggerFactory.builder()
            .serviceName("example-service")
            .serviceVersion("1.0.0")
            .environment("development")
            .build()
            .getLogger(ExampleService.class.getName());

    // -------------------------------------------------------------------------
    // HTTP / REST
    // -------------------------------------------------------------------------

    public void handleInboundRequest(String method, String route, int statusCode, long durationMs) {
        HttpOutcome outcome = switch (statusCode / 100) {
            case 5 -> HttpOutcome.SERVER_ERROR;
            case 4 -> HttpOutcome.CLIENT_ERROR;
            default -> HttpOutcome.SUCCESS;
        };
        LOG.emit(new HttpRequestCompletedEvent(
                method,
                route,
                statusCode,
                durationMs,
                outcome,
                outcome == HttpOutcome.SUCCESS ? null : "HTTP " + statusCode,  // outcome.reason
                null                                                             // error.type
        ));
    }

    // -------------------------------------------------------------------------
    // Async / event-driven
    // -------------------------------------------------------------------------

    public void processMessage(String eventType, String source, String eventId, boolean success) {
        LOG.emit(new AsyncEventProcessedEvent(
                eventType,
                source,
                eventId,                                        // event.id  (optional)
                success ? AsyncOutcome.SUCCESS : AsyncOutcome.FAILURE,
                success ? null : "Consumer threw an exception", // outcome.reason
                0,                                              // retry.count
                null                                            // duration.ms
        ));
    }

    // -------------------------------------------------------------------------
    // Database
    // -------------------------------------------------------------------------

    public void recordDbQuery(String operation, String collection, long durationMs,
                              int rowsAffected, boolean found) {
        LOG.emit(new DbOperationExecutedEvent(
                operation,
                collection,
                durationMs,
                rowsAffected,                                         // rows.affected (optional)
                found ? DataOutcome.SUCCESS : DataOutcome.NOT_FOUND,
                null                                                  // outcome.reason
        ));
    }

    // -------------------------------------------------------------------------
    // Cache
    // -------------------------------------------------------------------------

    public void recordCacheAccess(String operation, String keyPrefix, boolean hit) {
        LOG.emit(new CacheOperationResultEvent(
                operation,
                keyPrefix,
                hit ? DataOutcome.CACHE_HIT : DataOutcome.CACHE_MISS,
                null   // duration.ms (optional)
        ));
    }

    // -------------------------------------------------------------------------
    // Business logic
    // -------------------------------------------------------------------------

    public void recordBusinessStep(String step, String flow, String entityType, String entityId,
                                   boolean success, long durationMs) {
        LOG.emit(new BusinessStepCompletedEvent(
                step,
                flow,
                entityType,                                              // entity.type (optional)
                entityId,                                                // entity.id   (optional)
                success ? BusinessOutcome.SUCCESS : BusinessOutcome.FAILURE,
                success ? null : "Validation failed",                    // outcome.reason
                durationMs                                               // duration.ms (optional)
        ));
    }

    // -------------------------------------------------------------------------

    public static void main(String[] args) throws InterruptedException {
        ExampleService svc = new ExampleService();

        System.out.println("=== Argus Logging Framework — Example Output ===\n");

        // HTTP events
        svc.handleInboundRequest("GET",  "/api/orders/{id}", 200, 38);
        svc.handleInboundRequest("POST", "/api/orders",      500, 1204);
        svc.handleInboundRequest("GET",  "/api/users/{id}",  404, 12);

        // Async events
        svc.processMessage("order.placed",   "order-service",   "evt-001", true);
        svc.processMessage("payment.failed", "payment-service", "evt-002", false);

        // DB events
        svc.recordDbQuery("SELECT", "orders",    22,  1, true);
        svc.recordDbQuery("SELECT", "inventory", 15,  0, false);

        // Cache events
        svc.recordCacheAccess("GET", "user:profile:", true);
        svc.recordCacheAccess("GET", "product:detail:", false);

        // Business logic
        svc.recordBusinessStep("validate-order", "checkout", "Order", "ord-987", true,  5);
        svc.recordBusinessStep("charge-payment", "checkout", "Order", "ord-987", false, 230);

        // Give the BatchLogRecordProcessor time to flush when using OTLP
        Thread.sleep(3_000);
        System.out.println("\n=== Done ===");
    }
}
