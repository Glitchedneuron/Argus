package com.argus.logging.codegen.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One entry from the {@code events} section of the contract. */
public final class EventDefinition {

    /** Canonical dot-separated event name, e.g. {@code http.request.completed}. */
    private String eventName;

    /** OTel severity: INFO | DEBUG | WARN | ERROR */
    private String severity = "INFO";

    private String description = "";

    /**
     * Body template, e.g. {@code "{http.method} {http.route} -> {http.response.status_code}"}.
     * Placeholders in braces are replaced with the corresponding Java field expression.
     */
    private String body = "";

    /** Ordered map of OTel attribute name → attribute definition. */
    private Map<String, AttributeDefinition> attributes = new LinkedHashMap<>();

    /**
     * Ambient context fields listed in the contract (trace_id, span_id, service.name …).
     * These are propagated automatically by the OTel SDK; listed here for documentation only.
     */
    private List<String> context = List.of();

    public String getEventName() { return eventName; }
    public void setEventName(String eventName) { this.eventName = eventName; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity != null ? severity.toUpperCase() : "INFO"; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description != null ? description : ""; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body != null ? body : ""; }

    public Map<String, AttributeDefinition> getAttributes() { return attributes; }
    public void setAttributes(Map<String, AttributeDefinition> attributes) { this.attributes = attributes; }

    public List<String> getContext() { return context; }
    public void setContext(List<String> context) { this.context = context != null ? context : List.of(); }
}
