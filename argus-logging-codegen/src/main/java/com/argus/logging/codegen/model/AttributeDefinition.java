package com.argus.logging.codegen.model;

/**
 * One attribute entry from an event definition in the contract.
 *
 * <p>Supported types: {@code string}, {@code int}, {@code long},
 * {@code double}, {@code boolean}, {@code enum}.</p>
 */
public final class AttributeDefinition {

    /** OTel attribute name (dot-separated), e.g. {@code http.response.status_code}. */
    private String otelName;

    /** One of: string | int | long | double | boolean | enum */
    private String type = "string";

    /** Name of the enum from the contract's {@code enums} section (only when type=enum). */
    private String enumType;

    private boolean required = false;

    /**
     * Max string length override. {@code null} means "use the contract's
     * {@code string_limits.default_max_length}".
     */
    private Integer maxLength;

    private String description = "";

    public String getOtelName() { return otelName; }
    public void setOtelName(String otelName) { this.otelName = otelName; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type != null ? type : "string"; }

    public String getEnumType() { return enumType; }
    public void setEnumType(String enumType) { this.enumType = enumType; }

    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }

    public Integer getMaxLength() { return maxLength; }
    public void setMaxLength(Integer maxLength) { this.maxLength = maxLength; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description != null ? description : ""; }
}
