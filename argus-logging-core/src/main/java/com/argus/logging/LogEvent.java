package com.argus.logging;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.logs.Severity;

/**
 * Contract implemented by every generated event record.
 *
 * <p>Do not implement this interface manually — define events in {@code log-contract.yaml}
 * and let the {@code argus-logging-codegen} plugin generate the records.</p>
 *
 * <p>The OTel Logs Bridge API picks up trace_id / span_id automatically from the active
 * span context, so implementations do not need to capture those.</p>
 */
public interface LogEvent {

    /** Canonical dot-separated event name as defined in the YAML contract. */
    String eventName();

    /** OTel severity level for this event (fixed per event type, defined in the contract). */
    Severity severity();

    /**
     * Human-readable log body assembled from the contract's {@code body} template.
     * Values are substituted directly; the result is stored as the OTel log record body.
     */
    String body();

    /**
     * Structured attributes to attach to the log record.
     * Keys follow OTel semantic conventions (dot-separated).
     */
    Attributes toAttributes();
}
