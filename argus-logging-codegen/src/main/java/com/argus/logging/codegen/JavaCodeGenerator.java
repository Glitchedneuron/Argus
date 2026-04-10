package com.argus.logging.codegen;

import com.argus.logging.codegen.model.AttributeDefinition;
import com.argus.logging.codegen.model.EnumDefinition;
import com.argus.logging.codegen.model.EventDefinition;
import com.argus.logging.codegen.model.LogContract;
import com.argus.logging.codegen.model.StringLimits;
import org.apache.maven.plugin.logging.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates Java source files from a parsed {@link LogContract}.
 *
 * <p>For each enum definition → one {@code public enum} class.</p>
 * <p>For each event definition → one {@code public record} implementing {@code LogEvent}.</p>
 *
 * <h2>Naming conventions</h2>
 * <ul>
 *   <li>Enum: name taken verbatim from the contract ({@code HttpOutcome} → {@code HttpOutcome.java}).</li>
 *   <li>Event: dot-separated name PascalCased + "Event" suffix
 *       ({@code http.request.completed} → {@code HttpRequestCompletedEvent.java}).</li>
 *   <li>Field: OTel attribute name camelCased, dots and underscores are word boundaries
 *       ({@code http.response.status_code} → {@code httpResponseStatusCode}).</li>
 * </ul>
 *
 * <h2>Type mapping</h2>
 * <pre>
 * YAML type   required Java type   optional Java type   OTel key factory
 * string      String               String (nullable)    AttributeKey.stringKey
 * int         int                  Integer              AttributeKey.longKey  (cast to long)
 * long        long                 Long                 AttributeKey.longKey
 * double      double               Double               AttributeKey.doubleKey
 * boolean     boolean              Boolean              AttributeKey.booleanKey
 * enum        EnumType             EnumType (nullable)  AttributeKey.stringKey (.name())
 * </pre>
 */
final class JavaCodeGenerator {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^}]+)}");

    private final Log log;

    JavaCodeGenerator(Log log) {
        this.log = log;
    }

    void generate(LogContract contract, File outputDir) throws IOException {
        String namespace = contract.getNamespace();
        File packageDir = new File(outputDir, namespace.replace('.', '/'));
        Files.createDirectories(packageDir.toPath());

        for (EnumDefinition enumDef : contract.getEnums().values()) {
            generateEnum(enumDef, namespace, packageDir);
        }
        for (EventDefinition eventDef : contract.getEvents().values()) {
            generateEventRecord(eventDef, contract, namespace, packageDir);
        }
    }

    // -------------------------------------------------------------------------
    // Enum generation
    // -------------------------------------------------------------------------

    private void generateEnum(EnumDefinition def, String namespace, File packageDir) throws IOException {
        StringBuilder sb = new StringBuilder(512);

        sb.append("package ").append(namespace).append(";\n\n");
        if (!def.getDescription().isBlank()) {
            sb.append("/** ").append(def.getDescription()).append(" — generated, do not edit. */\n");
        }
        sb.append("public enum ").append(def.getName()).append(" {\n");

        List<String> values = def.getValues();
        for (int i = 0; i < values.size(); i++) {
            sb.append("    ").append(values.get(i));
            if (i < values.size() - 1) sb.append(',');
            sb.append('\n');
        }
        sb.append("}\n");

        writeFile(packageDir, def.getName() + ".java", sb.toString());
        log.info("[argus-logging-codegen] generated enum  " + def.getName());
    }

    // -------------------------------------------------------------------------
    // Record generation
    // -------------------------------------------------------------------------

    private void generateEventRecord(EventDefinition event, LogContract contract,
                                     String namespace, File packageDir) throws IOException {
        String className = toClassName(event.getEventName());
        Map<String, AttributeDefinition> attributes = event.getAttributes();
        List<Map.Entry<String, AttributeDefinition>> attrList = new ArrayList<>(attributes.entrySet());
        StringLimits limits = contract.getStringLimits();

        StringBuilder sb = new StringBuilder(2048);

        // Package + imports
        sb.append("package ").append(namespace).append(";\n\n");
        sb.append("import com.argus.logging.LogEvent;\n");
        sb.append("import io.opentelemetry.api.common.AttributeKey;\n");
        sb.append("import io.opentelemetry.api.common.Attributes;\n");
        sb.append("import io.opentelemetry.api.common.AttributesBuilder;\n");
        sb.append("import io.opentelemetry.api.logs.Severity;\n");
        sb.append("import java.util.Objects;\n\n");

        // Javadoc
        sb.append("/**\n");
        if (!event.getDescription().isBlank()) {
            sb.append(" * ").append(event.getDescription()).append('\n');
            sb.append(" *\n");
        }
        sb.append(" * <p>Event name: {@code ").append(event.getEventName()).append("}</p>\n");
        sb.append(" * <p>Severity: ").append(event.getSeverity()).append("</p>\n");
        if (!event.getContext().isEmpty()) {
            sb.append(" * <p>Ambient context (propagated by OTel SDK): ")
              .append(String.join(", ", event.getContext())).append("</p>\n");
        }
        sb.append(" *\n");
        sb.append(" * <p><strong>Generated from log-contract.yaml — do not edit.</strong></p>\n");
        sb.append(" */\n");

        // Record declaration
        sb.append("public record ").append(className).append("(\n");
        for (int i = 0; i < attrList.size(); i++) {
            var entry = attrList.get(i);
            String otelName = entry.getKey();
            AttributeDefinition attr = entry.getValue();
            String javaType = toJavaType(attr);
            String fieldName = toFieldName(otelName);

            sb.append("        ").append(javaType).append(' ').append(fieldName);
            // inline comment: OTel name + constraints
            sb.append("  // ").append(otelName);
            if (attr.isRequired()) sb.append(", required");
            if ("string".equals(attr.getType()) && effectiveMaxLength(attr, limits) > 0) {
                sb.append(", max=").append(effectiveMaxLength(attr, limits));
            }
            if (i < attrList.size() - 1) sb.append(',');
            sb.append('\n');
        }
        sb.append(") implements LogEvent {\n\n");

        // Compact constructor
        appendCompactConstructor(sb, className, attrList, limits);

        // eventName()
        sb.append("    @Override\n");
        sb.append("    public String eventName() {\n");
        sb.append("        return \"").append(event.getEventName()).append("\";\n");
        sb.append("    }\n\n");

        // severity()
        sb.append("    @Override\n");
        sb.append("    public Severity severity() {\n");
        sb.append("        return Severity.").append(event.getSeverity()).append(";\n");
        sb.append("    }\n\n");

        // body()
        sb.append("    @Override\n");
        sb.append("    public String body() {\n");
        sb.append("        return ").append(renderBodyExpression(event.getBody(), attributes)).append(";\n");
        sb.append("    }\n\n");

        // toAttributes()
        appendToAttributes(sb, attrList);

        sb.append("}\n");

        writeFile(packageDir, className + ".java", sb.toString());
        log.info("[argus-logging-codegen] generated record " + className);
    }

    // -------------------------------------------------------------------------
    // Compact constructor
    // -------------------------------------------------------------------------

    private void appendCompactConstructor(StringBuilder sb, String className,
                                          List<Map.Entry<String, AttributeDefinition>> attrs,
                                          StringLimits limits) {
        sb.append("    public ").append(className).append(" {\n");

        for (var entry : attrs) {
            String otelName = entry.getKey();
            AttributeDefinition attr = entry.getValue();
            String fieldName = toFieldName(otelName);

            // Null check for required reference types (primitives can't be null)
            if (attr.isRequired() && isNullableJavaType(attr)) {
                sb.append("        Objects.requireNonNull(").append(fieldName)
                  .append(", \"").append(otelName).append(" is required\");\n");
            }

            // String truncation (applied to both required and optional strings)
            if ("string".equals(attr.getType())) {
                int max = effectiveMaxLength(attr, limits);
                sb.append("        if (").append(fieldName).append(" != null && ")
                  .append(fieldName).append(".length() > ").append(max).append(") {\n");
                sb.append("            ").append(fieldName).append(" = ")
                  .append(fieldName).append(".substring(0, ").append(max).append(");\n");
                sb.append("        }\n");
            }
        }

        sb.append("    }\n\n");
    }

    // -------------------------------------------------------------------------
    // toAttributes()
    // -------------------------------------------------------------------------

    private void appendToAttributes(StringBuilder sb,
                                    List<Map.Entry<String, AttributeDefinition>> attrs) {
        sb.append("    @Override\n");
        sb.append("    public Attributes toAttributes() {\n");
        sb.append("        AttributesBuilder builder = Attributes.builder();\n");

        for (var entry : attrs) {
            String otelName = entry.getKey();
            AttributeDefinition attr = entry.getValue();
            String fieldName = toFieldName(otelName);
            String keyFactory = toOtelKeyFactory(attr.getType());
            String valueExpr = toOtelValueExpr(fieldName, attr);

            boolean alwaysPresent = attr.isRequired() || isPrimitive(attr);
            if (alwaysPresent) {
                sb.append("        builder.put(").append(keyFactory).append("(\"")
                  .append(otelName).append("\"), ").append(valueExpr).append(");\n");
            } else {
                sb.append("        if (").append(fieldName).append(" != null) {\n");
                sb.append("            builder.put(").append(keyFactory).append("(\"")
                  .append(otelName).append("\"), ").append(valueExpr).append(");\n");
                sb.append("        }\n");
            }
        }

        sb.append("        return builder.build();\n");
        sb.append("    }\n");
    }

    // -------------------------------------------------------------------------
    // Body template rendering
    // -------------------------------------------------------------------------

    /**
     * Converts a body template like {@code "{http.method} {http.route} -> {http.response.status_code}"}
     * into a Java string concatenation expression.
     */
    private String renderBodyExpression(String template, Map<String, AttributeDefinition> attributes) {
        if (template == null || template.isBlank()) return "\"\"";

        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder expr = new StringBuilder();
        int cursor = 0;

        while (m.find()) {
            // Literal segment before this placeholder
            String literal = template.substring(cursor, m.start());
            if (!literal.isEmpty()) {
                if (expr.length() > 0) expr.append(" + ");
                expr.append('"').append(escapeJava(literal)).append('"');
            }

            // Attribute expression
            String attrName = m.group(1);
            AttributeDefinition attr = attributes.get(attrName);
            String fieldName = toFieldName(attrName);

            if (expr.length() > 0) expr.append(" + ");
            if (attr != null && "enum".equals(attr.getType())) {
                // null-safe for optional enums
                if (!attr.isRequired()) {
                    expr.append('(').append(fieldName).append(" != null ? ")
                        .append(fieldName).append(".name() : \"null\")");
                } else {
                    expr.append(fieldName).append(".name()");
                }
            } else {
                expr.append(fieldName);
            }
            cursor = m.end();
        }

        // Remaining literal
        String tail = template.substring(cursor);
        if (!tail.isEmpty()) {
            if (expr.length() > 0) expr.append(" + ");
            expr.append('"').append(escapeJava(tail)).append('"');
        }

        return expr.length() > 0 ? expr.toString() : "\"\"";
    }

    // -------------------------------------------------------------------------
    // Name / type helpers
    // -------------------------------------------------------------------------

    /**
     * {@code http.request.completed} → {@code HttpRequestCompletedEvent}
     * Splits on {@code . - _}, capitalises each word, appends "Event".
     */
    static String toClassName(String eventName) {
        StringBuilder sb = new StringBuilder();
        for (String part : eventName.split("[._\\-]")) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) sb.append(part.substring(1));
            }
        }
        sb.append("Event");
        return sb.toString();
    }

    /**
     * {@code http.response.status_code} → {@code httpResponseStatusCode}
     * Dots and underscores are word boundaries; first word is all-lower.
     */
    static String toFieldName(String otelName) {
        String[] dotParts = otelName.split("\\.");
        StringBuilder sb = new StringBuilder();
        for (String dotPart : dotParts) {
            String[] underParts = dotPart.split("_");
            for (String part : underParts) {
                if (part.isEmpty()) continue;
                if (sb.length() == 0) {
                    sb.append(part.toLowerCase());
                } else {
                    sb.append(Character.toUpperCase(part.charAt(0)));
                    if (part.length() > 1) sb.append(part.substring(1).toLowerCase());
                }
            }
        }
        return sb.toString();
    }

    /** Maps a contract type to the Java type used in the record component. */
    private static String toJavaType(AttributeDefinition attr) {
        boolean required = attr.isRequired();
        return switch (attr.getType()) {
            case "int"     -> required ? "int"     : "Integer";
            case "long"    -> required ? "long"    : "Long";
            case "double"  -> required ? "double"  : "Double";
            case "boolean" -> required ? "boolean" : "Boolean";
            case "enum"    -> attr.getEnumType();
            default        -> "String"; // string
        };
    }

    /** Returns true if this attribute's Java type is a reference type (can be null). */
    private static boolean isNullableJavaType(AttributeDefinition attr) {
        return switch (attr.getType()) {
            case "int", "long", "double", "boolean" -> false; // primitives when required
            default -> true;
        };
    }

    /** True if the attribute maps to a Java primitive (always present, never null). */
    private static boolean isPrimitive(AttributeDefinition attr) {
        return attr.isRequired() && switch (attr.getType()) {
            case "int", "long", "double", "boolean" -> true;
            default -> false;
        };
    }

    private static String toOtelKeyFactory(String type) {
        return switch (type) {
            case "int", "long" -> "AttributeKey.longKey";
            case "double"      -> "AttributeKey.doubleKey";
            case "boolean"     -> "AttributeKey.booleanKey";
            default            -> "AttributeKey.stringKey"; // string, enum
        };
    }

    /** Java expression that produces the value to store in OTel attributes. */
    private static String toOtelValueExpr(String fieldName, AttributeDefinition attr) {
        return switch (attr.getType()) {
            // int is stored as long in OTel
            case "int"  -> attr.isRequired() ? "(long) " + fieldName : fieldName + ".longValue()";
            // enum → stored as its .name() string
            case "enum" -> fieldName + ".name()";
            default     -> fieldName; // long, double, boolean, string — direct
        };
    }

    private static int effectiveMaxLength(AttributeDefinition attr, StringLimits limits) {
        return attr.getMaxLength() != null ? attr.getMaxLength() : limits.getDefaultMaxLength();
    }

    private static String escapeJava(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    // -------------------------------------------------------------------------

    private void writeFile(File dir, String filename, String content) throws IOException {
        File file = new File(dir, filename);
        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
    }
}
