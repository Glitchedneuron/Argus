package com.argus.logging;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.logs.LogRecordBuilder;
import io.opentelemetry.api.logs.Logger;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Thin wrapper around an OTel {@link Logger} that accepts {@link LogEvent} instances.
 *
 * <p>Obtain via {@link ArgusLoggerFactory#getLogger(String)}.</p>
 *
 * <p>Trace context (trace_id, span_id) is propagated automatically by the OTel SDK
 * from the currently active span — no manual wiring required.</p>
 *
 * <h2>Mandatory OTel envelope</h2>
 * <p>Every emitted log record carries the following fields regardless of which overload
 * is used; they are set here or by the SDK/factory:</p>
 * <ul>
 *   <li>{@code timestamp} — nanosecond epoch, set by the OTel SDK at emit time.</li>
 *   <li>{@code body} — rendered from the event's body template.</li>
 *   <li>{@code severity} / {@code severityText} — fixed per event type.</li>
 *   <li>{@code service.name}, {@code service.version}, {@code deployment.environment.name}
 *       — OTel Resource attributes, set once in {@link ArgusLoggerFactory}.</li>
 *   <li>{@code trace_id}, {@code span_id} — propagated from the active OTel span.</li>
 *   <li>{@code event.name} — canonical event name from the contract (e.g. {@code http.request.completed});
 *       maps to the OTel log data model {@code EventName} field.</li>
 * </ul>
 */
public final class ArgusLogger {

    private static final int MAX_STACKTRACE = 4096;
    private static final int MAX_MESSAGE    = 4096;

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
        buildBase(event).emit();
    }

    /**
     * Emit a structured log event with exception detail.
     *
     * <p>Populates the standard OTel error envelope attributes automatically from
     * the provided {@link Throwable}:</p>
     * <ul>
     *   <li>{@code exception.type} — {@code error.getClass().getName()}</li>
     *   <li>{@code exception.message} — {@code error.getMessage()} (truncated to 4 096 chars)</li>
     *   <li>{@code exception.stacktrace} — full stack trace string (truncated to 4 096 chars)</li>
     * </ul>
     *
     * <p>Use this overload for ERROR / WARN events whose generated record includes the
     * injected error envelope fields. Any {@code exception.*} or {@code error.type} values
     * already set on the record take precedence; the throwable fills in the rest.</p>
     *
     * @param event a generated event record (never {@code null})
     * @param error the exception that caused the event; {@code null} is a no-op (same as {@link #emit(LogEvent)})
     */
    public void emit(LogEvent event, Throwable error) {
        LogRecordBuilder builder = buildBase(event);

        if (error != null) {
            // exception.type — always present
            builder.setAttribute(
                    AttributeKey.stringKey("exception.type"),
                    error.getClass().getName());

            // exception.message — present when the exception has a message
            String msg = error.getMessage();
            if (msg != null) {
                builder.setAttribute(
                        AttributeKey.stringKey("exception.message"),
                        truncate(msg, MAX_MESSAGE));
            }

            // exception.stacktrace — full trace, truncated
            builder.setAttribute(
                    AttributeKey.stringKey("exception.stacktrace"),
                    truncate(stacktraceOf(error), MAX_STACKTRACE));
        }

        builder.emit();
    }

    // -------------------------------------------------------------------------

    private LogRecordBuilder buildBase(LogEvent event) {
        return otelLogger.logRecordBuilder()
                .setSeverity(event.severity())
                .setSeverityText(event.severity().name())
                .setBody(event.body())
                .setAllAttributes(event.toAttributes())
                // EventName — top-level OTel log data model field (data-model §EventName).
                // Set as the event.name attribute per spec recommendation; the OTLP exporter
                // and collectors (Datadog agent ≥ 7.53, OTel Collector ≥ 0.96) map this to
                // the EventName field automatically.
                .setAttribute(AttributeKey.stringKey("event.name"), event.eventName());
    }

    private static String stacktraceOf(Throwable t) {
        StringWriter sw = new StringWriter(2048);
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
