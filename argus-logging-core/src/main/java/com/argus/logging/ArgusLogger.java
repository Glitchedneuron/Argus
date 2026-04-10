package com.argus.logging;

import io.opentelemetry.api.logs.Logger;

/**
 * Thin wrapper around an OTel {@link Logger} that accepts {@link LogEvent} instances.
 *
 * <p>Obtain via {@link ArgusLoggerFactory#getLogger(String)}.</p>
 *
 * <p>Trace context (trace_id, span_id) is propagated automatically by the OTel SDK
 * from the currently active span — no manual wiring required.</p>
 */
public final class ArgusLogger {

    private final Logger otelLogger;

    ArgusLogger(Logger otelLogger) {
        this.otelLogger = otelLogger;
    }

    /**
     * Emit a structured log event.
     *
     * <p>The event's {@link LogEvent#toAttributes()} are attached as OTel log attributes.
     * The active span context (if any) is attached by the SDK automatically.</p>
     *
     * @param event a generated event record (never {@code null})
     */
    public void emit(LogEvent event) {
        otelLogger.logRecordBuilder()
                .setSeverity(event.severity())
                .setSeverityText(event.severity().name())
                .setBody(event.body())
                .setAllAttributes(event.toAttributes())
                .emit();
    }
}
