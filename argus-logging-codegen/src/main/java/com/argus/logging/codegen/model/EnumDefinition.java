package com.argus.logging.codegen.model;

import java.util.List;

/** One entry from the {@code enums} section of the contract. */
public final class EnumDefinition {

    private String name;
    private String description = "";
    private List<String> values;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description != null ? description : ""; }

    public List<String> getValues() { return values; }
    public void setValues(List<String> values) { this.values = values; }
}
