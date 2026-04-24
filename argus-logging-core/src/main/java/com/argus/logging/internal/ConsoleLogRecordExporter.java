package com.argus.logging.internal;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributeType;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Development-mode log exporter that writes one JSON object per log record to stdout.
 *
 * <p>The JSON structure follows the OTel Log Data Model fields:
 * <a href="https://opentelemetry.io/docs/specs/otel/logs/data-model/">OTel Log Data Model</a></p>
 *
 * <p>Not intended for production use — selected automatically when
 * {@code OTEL_EXPORTER_OTLP_ENDPOINT} is absent (see {@link OtelInitializer}).</p>
 *
 * <p>Example output:</p>
 * <pre>
 * {
 *   "timestamp": "2026-04-10T14:32:01.042Z",
 *   "severity_number": 9,
 *   "severity_text": "INFO",
 *   "event_name": "http.request.completed",
 *   "body": "GET /api/orders/{id} -> 200 [SUCCESS] in 38ms",
 *   "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736",
 *   "span_id": "a3ce929d0e0e4736",
 *   "trace_flags": "01",
 *   "resource": {
 *     "service.name": "example-service",
 *     "service.version": "1.0.0",
 *     "deployment.environment.name": "development"
 *   },
 *   "instrumentation_scope": {
 *     "name": "com.argus.logging",
 *     "version": "1.0.0"
 *   },
 *   "attributes": {
 *     "http.method": "GET",
 *     "http.route": "/api/orders/{id}",
 *     "http.response.status_code": 200,
 *     "duration.ms": 38,
 *     "outcome": "SUCCESS"
 *   }
 * }
 * </pre>
 */
final class ConsoleLogRecordExporter implements LogRecordExporter {

    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);

    private static final AttributeKey<String> EVENT_NAME = AttributeKey.stringKey("event.name");

    static ConsoleLogRecordExporter create() {
        return new ConsoleLogRecordExporter();
    }

    private ConsoleLogRecordExporter() {}

    @Override
    public CompletableResultCode export(Collection<LogRecordData> logs) {
        logs.forEach(this::print);
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }

    // -------------------------------------------------------------------------

    private void print(LogRecordData log) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\n");

        // timestamp — from the explicit setTimestamp() call in ArgusLogger.buildBase()
        long epochNanos = log.getTimestampEpochNanos();
        String timestamp = epochNanos == 0
                ? "null"
                : quoted(ISO_FMT.format(Instant.ofEpochSecond(epochNanos / 1_000_000_000L,
                                                               epochNanos % 1_000_000_000L)));
        sb.append("  \"timestamp\": ").append(timestamp).append(",\n");

        // observed_timestamp — set by the SDK automatically
        long obsNanos = log.getObservedTimestampEpochNanos();
        String obsTimestamp = obsNanos == 0
                ? "null"
                : quoted(ISO_FMT.format(Instant.ofEpochSecond(obsNanos / 1_000_000_000L,
                                                               obsNanos % 1_000_000_000L)));
        sb.append("  \"observed_timestamp\": ").append(obsTimestamp).append(",\n");

        // severity_number / severity_text
        int severityNum = log.getSeverity() != null ? log.getSeverity().getSeverityNumber() : 0;
        sb.append("  \"severity_number\": ").append(severityNum).append(",\n");
        sb.append("  \"severity_text\": ")
          .append(log.getSeverityText() != null ? quoted(log.getSeverityText()) : "null")
          .append(",\n");

        // event_name — from the event.name attribute (maps to OTel EventName field)
        String eventName = log.getAttributes().get(EVENT_NAME);
        sb.append("  \"event_name\": ")
          .append(eventName != null ? quoted(eventName) : "null")
          .append(",\n");

        // body
        String body = bodyString(log);
        sb.append("  \"body\": ").append(quoted(body)).append(",\n");

        // trace context
        var spanCtx = log.getSpanContext();
        if (spanCtx != null && spanCtx.isValid()) {
            sb.append("  \"trace_id\": ").append(quoted(spanCtx.getTraceId())).append(",\n");
            sb.append("  \"span_id\": ").append(quoted(spanCtx.getSpanId())).append(",\n");
            sb.append("  \"trace_flags\": ")
              .append(quoted(String.format("%02x", spanCtx.getTraceFlags().asByte())))
              .append(",\n");
        } else {
            sb.append("  \"trace_id\": null,\n");
            sb.append("  \"span_id\": null,\n");
            sb.append("  \"trace_flags\": null,\n");
        }

        // resource attributes
        sb.append("  \"resource\": {\n");
        AtomicBoolean firstResource = new AtomicBoolean(true);
        log.getResource().getAttributes().forEach((k, v) -> {
            if (!firstResource.getAndSet(false)) sb.append(",\n");
            sb.append("    ").append(quoted(k.getKey())).append(": ").append(jsonValue(k, v));
        });
        sb.append("\n  },\n");

        // instrumentation scope
        var scope = log.getInstrumentationScopeInfo();
        sb.append("  \"instrumentation_scope\": {\n");
        sb.append("    \"name\": ").append(quoted(scope.getName()));
        if (scope.getVersion() != null) {
            sb.append(",\n    \"version\": ").append(quoted(scope.getVersion()));
        }
        sb.append("\n  },\n");

        // attributes — skip event.name (already surfaced as event_name above)
        sb.append("  \"attributes\": {\n");
        AtomicBoolean firstAttr = new AtomicBoolean(true);
        log.getAttributes().forEach((k, v) -> {
            if (EVENT_NAME.equals(k)) return;
            if (!firstAttr.getAndSet(false)) sb.append(",\n");
            sb.append("    ").append(quoted(k.getKey())).append(": ").append(jsonValue(k, v));
        });
        sb.append("\n  }\n");

        sb.append("}");

        System.out.println(sb);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String jsonValue(AttributeKey key, Object value) {
        AttributeType type = key.getType();
        return switch (type) {
            case LONG, DOUBLE, BOOLEAN -> String.valueOf(value);
            default -> quoted(String.valueOf(value));
        };
    }

    private static String bodyString(LogRecordData log) {
        var body = log.getBody();
        if (body == null) return "";
        return body.asString();
    }

    private static String quoted(String s) {
        // Minimal JSON string escaping for control characters and quotes.
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r")
                       .replace("\t", "\\t")
               + "\"";
    }
}
