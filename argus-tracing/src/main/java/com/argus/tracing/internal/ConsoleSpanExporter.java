package com.argus.tracing.internal;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributeType;
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
 * Development-mode span exporter that writes one JSON object per span to stdout.
 *
 * <p>Selected automatically when {@code OTEL_EXPORTER_OTLP_ENDPOINT} is absent
 * (see {@link OtelTracingInitializer}).</p>
 *
 * <p>Example output:</p>
 * <pre>
 * {
 *   "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736",
 *   "span_id": "a3ce929d0e0e4736",
 *   "parent_span_id": "0000000000000000",
 *   "operation_name": "process-order",
 *   "kind": "SERVER",
 *   "start_time": "2026-04-10T14:32:01.042Z",
 *   "end_time": "2026-04-10T14:32:01.079Z",
 *   "duration_ms": 37,
 *   "status": "OK",
 *   "resource": { "service.name": "example-service" },
 *   "instrumentation_scope": { "name": "com.argus.tracing", "version": "1.0.0" },
 *   "attributes": { "order.id": "ord-123" },
 *   "events": []
 * }
 * </pre>
 */
final class ConsoleSpanExporter implements SpanExporter {

    private static final DateTimeFormatter ISO_FMT = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC);

    private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey("service.name");

    static ConsoleSpanExporter create() {
        return new ConsoleSpanExporter();
    }

    private ConsoleSpanExporter() {}

    @Override
    public CompletableResultCode export(Collection<? extends SpanData> spans) {
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

        sb.append("  \"trace_id\": ").append(quoted(span.getTraceId())).append(",\n");
        sb.append("  \"span_id\": ").append(quoted(span.getSpanId())).append(",\n");
        sb.append("  \"parent_span_id\": ").append(quoted(span.getParentSpanId())).append(",\n");
        sb.append("  \"operation_name\": ").append(quoted(span.getName())).append(",\n");
        sb.append("  \"kind\": ").append(quoted(span.getKind().name())).append(",\n");

        long startNanos = span.getStartEpochNanos();
        long endNanos   = span.getEndEpochNanos();
        sb.append("  \"start_time\": ").append(epochNanosToIso(startNanos)).append(",\n");
        sb.append("  \"end_time\": ").append(epochNanosToIso(endNanos)).append(",\n");
        sb.append("  \"duration_ms\": ")
          .append(TimeUnit.NANOSECONDS.toMillis(endNanos - startNanos))
          .append(",\n");

        StatusData status = span.getStatus();
        sb.append("  \"status\": ").append(quoted(status.getStatusCode().name()));
        if (status.getDescription() != null && !status.getDescription().isBlank()) {
            sb.append(",\n  \"status_description\": ").append(quoted(status.getDescription()));
        }
        sb.append(",\n");

        // resource (key subset — just service.* and deployment.*)
        sb.append("  \"resource\": {\n");
        AtomicBoolean firstR = new AtomicBoolean(true);
        span.getResource().getAttributes().forEach((k, v) -> {
            if (!firstR.getAndSet(false)) sb.append(",\n");
            sb.append("    ").append(quoted(k.getKey())).append(": ").append(jsonValue(k, v));
        });
        sb.append("\n  },\n");

        // instrumentation scope
        var scope = span.getInstrumentationScopeInfo();
        sb.append("  \"instrumentation_scope\": {\n");
        sb.append("    \"name\": ").append(quoted(scope.getName()));
        if (scope.getVersion() != null) {
            sb.append(",\n    \"version\": ").append(quoted(scope.getVersion()));
        }
        sb.append("\n  },\n");

        // attributes
        sb.append("  \"attributes\": {\n");
        AtomicBoolean firstA = new AtomicBoolean(true);
        span.getAttributes().forEach((k, v) -> {
            if (!firstA.getAndSet(false)) sb.append(",\n");
            sb.append("    ").append(quoted(k.getKey())).append(": ").append(jsonValue(k, v));
        });
        sb.append("\n  },\n");

        // events (recorded exceptions, etc.)
        sb.append("  \"events\": [\n");
        var events = span.getEvents();
        for (int i = 0; i < events.size(); i++) {
            var e = events.get(i);
            sb.append("    {\n");
            sb.append("      \"name\": ").append(quoted(e.getName())).append(",\n");
            sb.append("      \"time\": ").append(epochNanosToIso(e.getEpochNanos())).append(",\n");
            sb.append("      \"attributes\": {\n");
            AtomicBoolean firstE = new AtomicBoolean(true);
            e.getAttributes().forEach((k, v) -> {
                if (!firstE.getAndSet(false)) sb.append(",\n");
                sb.append("        ").append(quoted(k.getKey())).append(": ").append(jsonValue(k, v));
            });
            sb.append("\n      }\n    }");
            if (i < events.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n}");

        System.out.println(sb);
    }

    // -------------------------------------------------------------------------

    private static String epochNanosToIso(long nanos) {
        if (nanos == 0) return "null";
        return quoted(ISO_FMT.format(
                Instant.ofEpochSecond(nanos / 1_000_000_000L, nanos % 1_000_000_000L)));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String jsonValue(AttributeKey key, Object value) {
        return switch ((AttributeType) key.getType()) {
            case LONG, DOUBLE, BOOLEAN -> String.valueOf(value);
            default                    -> quoted(String.valueOf(value));
        };
    }

    private static String quoted(String s) {
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r")
                       .replace("\t", "\\t")
               + "\"";
    }
}
