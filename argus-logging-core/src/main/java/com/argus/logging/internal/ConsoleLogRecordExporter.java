package com.argus.logging.internal;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;

/**
 * Development-mode log exporter that writes human-readable output to stdout.
 *
 * <p>Not intended for production use — the OTel SDK selects this exporter automatically
 * when {@code OTEL_EXPORTER_OTLP_ENDPOINT} is absent (see {@link OtelInitializer}).</p>
 *
 * <p>Example output:</p>
 * <pre>
 * [14:32:01.042] INFO  http.request.completed
 *   GET /api/orders -> 200 [SUCCESS] in 37ms
 *   service=order-service  trace=4bf92f3577b34da6a3ce929d0e0e4736  span=a3ce929d0e0e4736
 *   http.method=GET  http.route=/api/orders  http.response.status_code=200
 *   duration.ms=37  outcome=SUCCESS
 * </pre>
 */
final class ConsoleLogRecordExporter implements LogRecordExporter {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private static final AttributeKey<String> SERVICE_NAME  = AttributeKey.stringKey("service.name");
    private static final AttributeKey<String> EVENT_NAME    = AttributeKey.stringKey("event.name");

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
        String time = TIME_FMT.format(Instant.ofEpochSecond(0L, log.getTimestampEpochNanos()));
        String severity = padRight(log.getSeverityText() != null ? log.getSeverityText() : "?", 5);
        String eventName = eventName(log);
        String body = bodyString(log);
        String service = log.getResource().getAttribute(SERVICE_NAME);

        StringBuilder sb = new StringBuilder(256);

        // Header line
        sb.append('[').append(time).append("] ")
          .append(severity).append(' ')
          .append(eventName).append('\n');

        // Body
        sb.append("  ").append(body).append('\n');

        // Trace context
        var spanCtx = log.getSpanContext();
        if (spanCtx != null && spanCtx.isValid()) {
            sb.append("  service=").append(service != null ? service : "?")
              .append("  trace=").append(spanCtx.getTraceId())
              .append("  span=").append(spanCtx.getSpanId())
              .append('\n');
        } else if (service != null) {
            sb.append("  service=").append(service).append('\n');
        }

        // Attributes — skip event.name (already shown in the header line)
        log.getAttributes().forEach((k, v) -> {
            if (!EVENT_NAME.equals(k)) {
                sb.append("  ").append(k.getKey()).append('=').append(v).append('\n');
            }
        });

        System.out.print(sb);
    }

    private static String eventName(LogRecordData log) {
        return log.getAttributes().get(EVENT_NAME);
    }

    @SuppressWarnings("unchecked")
    private static String bodyString(LogRecordData log) {
        // getBodyValue() returns AnyValue<?>; getValue() returns the underlying value.
        // For string bodies this is a String; toString() is safe for other types.
        var body = log.getBodyValue();
        if (body == null) return "";
        Object value = body.getValue();
        return value != null ? value.toString() : "";
    }

    private static String padRight(String s, int width) {
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
    }
}
