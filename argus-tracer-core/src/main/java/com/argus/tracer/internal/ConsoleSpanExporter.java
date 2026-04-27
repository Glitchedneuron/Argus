package com.argus.tracer.internal;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributeType;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Development-mode span exporter — writes one JSON object per completed span to stdout.
 *
 * <p>Selected automatically when {@code OTEL_EXPORTER_OTLP_ENDPOINT} is absent.
 * Not intended for production.</p>
 *
 * <p>Example output:</p>
 * <pre>
 * {
 *   "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736",
 *   "span_id": "a3ce929d0e0e4736",
 *   "parent_span_id": "0000000000000000",
 *   "name": "checkout",
 *   "kind": "SERVER",
 *   "start_time": "2026-04-24T10:15:00.042Z",
 *   "end_time": "2026-04-24T10:15:00.117Z",
 *   "duration_ms": 75,
 *   "status": "OK",
 *   "resource": { "service.name": "order-service" },
 *   "instrumentation_scope": { "name": "com.argus.tracer", "version": "1.0.0" },
 *   "attributes": { "order.id": "ord-123", "customer.tier": "gold" },
 *   "events": []
 * }
 * </pre>
 */
final class ConsoleSpanExporter implements SpanExporter {

    private static final DateTimeFormatter ISO_FMT =
            DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);

    /** JSON field separator used between every top-level span field. */
    private static final String FIELD_SEP = ",\n";

    static ConsoleSpanExporter create() {
        return new ConsoleSpanExporter();
    }

    private ConsoleSpanExporter() {}

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        spans.forEach(this::print);
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

    @SuppressWarnings("PMD.SystemPrintln")
    private void print(SpanData span) {
        StringBuilder json = new StringBuilder(512);
        json.append("{\n");

        json.append("  \"trace_id\": ").append(quoted(span.getTraceId())).append(FIELD_SEP);
        json.append("  \"span_id\": ").append(quoted(span.getSpanId())).append(FIELD_SEP);
        json.append("  \"parent_span_id\": ").append(quoted(span.getParentSpanId())).append(FIELD_SEP);
        json.append("  \"name\": ").append(quoted(span.getName())).append(FIELD_SEP);
        json.append("  \"kind\": ").append(quoted(span.getKind().name())).append(FIELD_SEP);

        long startNs = span.getStartEpochNanos();
        long endNs   = span.getEndEpochNanos();
        json.append("  \"start_time\": ").append(epochNsToIso(startNs)).append(FIELD_SEP);
        json.append("  \"end_time\": ").append(epochNsToIso(endNs)).append(FIELD_SEP);
        json.append("  \"duration_ms\": ").append(TimeUnit.NANOSECONDS.toMillis(endNs - startNs)).append(FIELD_SEP);

        StatusData statusData = span.getStatus();
        json.append("  \"status\": ").append(quoted(statusData.getStatusCode().name()));
        if (statusData.getDescription() != null && !statusData.getDescription().isBlank()) {
            json.append(FIELD_SEP).append("  \"status_description\": ").append(quoted(statusData.getDescription()));
        }
        json.append(FIELD_SEP);

        json.append("  \"resource\": ").append(attrsInline(span.getResource().getAttributes())).append(FIELD_SEP);

        var scope = span.getInstrumentationScopeInfo();
        json.append("  \"instrumentation_scope\": { \"name\": ").append(quoted(scope.getName()));
        if (scope.getVersion() != null) {
            json.append(", \"version\": ").append(quoted(scope.getVersion()));
        }
        json.append(" }").append(FIELD_SEP);

        json.append("  \"attributes\": ").append(attrsInline(span.getAttributes())).append(FIELD_SEP);

        json.append("  \"events\": [");
        var events = span.getEvents();
        for (int idx = 0; idx < events.size(); idx++) {
            var event = events.get(idx);
            json.append("\n    { \"name\": ").append(quoted(event.getName()))
                .append(", \"time\": ").append(epochNsToIso(event.getEpochNanos()))
                .append(", \"attributes\": ").append(attrsInline(event.getAttributes()))
                .append(" }");
            if (idx < events.size() - 1) json.append(",");
        }
        if (!events.isEmpty()) json.append("\n  ");
        json.append("]\n}");

        System.out.println(json);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String attrsInline(Attributes attrs) {
        if (attrs.isEmpty()) return "{}";
        StringBuilder builder = new StringBuilder("{");
        AtomicBoolean first = new AtomicBoolean(true);
        attrs.forEach((key, val) -> {
            if (!first.getAndSet(false)) builder.append(", ");
            builder.append(quoted(key.getKey())).append(": ").append(jsonVal((AttributeKey) key, val));
        });
        return builder.append("}").toString();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String jsonVal(AttributeKey key, Object value) {
        return switch ((AttributeType) key.getType()) {
            case LONG, DOUBLE, BOOLEAN -> String.valueOf(value);
            default -> quoted(String.valueOf(value));
        };
    }

    private static String epochNsToIso(long nanos) {
        if (nanos == 0) return "null";
        return quoted(ISO_FMT.format(Instant.ofEpochSecond(nanos / 1_000_000_000L, nanos % 1_000_000_000L)));
    }

    private static String quoted(String text) {
        return "\"" + text.replace("\\", "\\\\")
                          .replace("\"", "\\\"")
                          .replace("\n", "\\n")
                          .replace("\r", "\\r")
                          .replace("\t", "\\t") + "\"";
    }
}
