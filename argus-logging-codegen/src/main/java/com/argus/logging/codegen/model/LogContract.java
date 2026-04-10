package com.argus.logging.codegen.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** Top-level representation of a parsed {@code log-contract.yaml} file. */
public final class LogContract {

    private String version = "1.0";
    private String namespace = "com.argus.logging.events";
    private StringLimits stringLimits = new StringLimits();
    private Map<String, EnumDefinition> enums = new LinkedHashMap<>();
    private Map<String, EventDefinition> events = new LinkedHashMap<>();

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }

    public StringLimits getStringLimits() { return stringLimits; }
    public void setStringLimits(StringLimits stringLimits) { this.stringLimits = stringLimits; }

    public Map<String, EnumDefinition> getEnums() { return enums; }
    public void setEnums(Map<String, EnumDefinition> enums) { this.enums = enums; }

    public Map<String, EventDefinition> getEvents() { return events; }
    public void setEvents(Map<String, EventDefinition> events) { this.events = events; }
}
