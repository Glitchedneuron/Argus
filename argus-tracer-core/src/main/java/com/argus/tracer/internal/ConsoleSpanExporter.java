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

    private void print(SpanData span) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\n");

        sb.append("  \"trace_id\": ").append(q(span.getTraceId())).append(",\n");
        sb.append("  \"span_id\": ").append(q(span.getSpanId())).append(",\n");
        sb.append("  \"parent_span_id\": ").append(q(span.getParentSpanId())).append(",\n");
        sb.append("  \"name\": ").append(q(span.getName())).append(",\n");
        sb.append("  \"kind\": ").append(q(span.getKind().name())).append(",\n");

        long startNs = span.getStartEpochNanos();
        long endNs   = span.getEndEpochNanos();
        sb.append("  \"start_time\": ").append(epochNsToIso(startNs)).append(",\n");
        sb.append("  \"end_time\": ").append(epochNsToIso(endNs)).append(",\n");
        sb.append("  \"duration_ms\": ").append(TimeUnit.NANOSECONDS.toMillis(endNs - startNs)).append(",\n");

        StatusData status = span.getStatus();
        sb.append("  \"status\": ").append(q(status.getStatusCode().name()));
        if (status.getDescription() != null && !status.getDescription().isBlank()) {
            sb.append(",\n  \"status_description\": ").append(q(status.getDescription()));
        }
        sb.append(",\n");

        sb.append("  \"resource\": ").append(attrsInline(span.getResource().getAttributes())).append(",\n");

        var scope = span.getInstrumentationScopeInfo();
        sb.append("  \"instrumentation_scope\": { \"name\": ").append(q(scope.getName()));
        if (scope.getVersion() != null) {
            sb.append(", \"version\": ").append(q(scope.getVersion()));
        }
        sb.append(" },\n");

        sb.append("  \"attributes\": ").append(attrsInline(span.getAttributes())).append(",\n");

        sb.append("  \"events\": [");
        var events = span.getEvents();
        for (int i = 0; i < events.size(); i++) {
            var e = events.get(i);
            sb.append("\n    { \"name\": ").append(q(e.getName()))
              .append(", \"time\": ").append(epochNsToIso(e.getEpochNanos()))
              .append(", \"attributes\": ").append(attrsInline(e.getAttributes()))
              .append(" }");
            if (i < events.size() - 1) sb.append(",");
        }
        if (!events.isEmpty()) sb.append("\n  ");
        sb.append("]\n}");

        System.out.println(sb);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String attrsInline(Attributes attrs) {
        if (attrs.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        AtomicBoolean first = new AtomicBoolean(true);
        attrs.forEach((k, v) -> {
            if (!first.getAndSet(false)) sb.append(", ");
            sb.append(q(k.getKey())).append(": ").append(jsonVal((AttributeKey) k, v));
        });
        return sb.append("}").toString();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String jsonVal(AttributeKey key, Object value) {
        return switch ((AttributeType) key.getType()) {
            case LONG, DOUBLE, BOOLEAN -> String.valueOf(value);
            default -> q(String.valueOf(value));
        };
    }

    private static String epochNsToIso(long nanos) {
        if (nanos == 0) return "null";
        return q(ISO_FMT.format(Instant.ofEpochSecond(nanos / 1_000_000_000L, nanos % 1_000_000_000L)));
    }

    private static String q(String s) {
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r")
                       .replace("\t", "\\t") + "\"";
    }
}
