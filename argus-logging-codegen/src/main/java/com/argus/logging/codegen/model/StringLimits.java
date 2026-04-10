package com.argus.logging.codegen.model;

/** Global string length limits from the {@code string_limits} section of the contract. */
public final class StringLimits {

    private int defaultMaxLength = 512;
    private int overrideMaxLength = 4096;

    public int getDefaultMaxLength() { return defaultMaxLength; }
    public void setDefaultMaxLength(int defaultMaxLength) { this.defaultMaxLength = defaultMaxLength; }

    public int getOverrideMaxLength() { return overrideMaxLength; }
    public void setOverrideMaxLength(int overrideMaxLength) { this.overrideMaxLength = overrideMaxLength; }
}
