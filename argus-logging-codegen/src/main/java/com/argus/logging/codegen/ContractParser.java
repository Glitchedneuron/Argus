package com.argus.logging.codegen;

import com.argus.logging.codegen.model.AttributeDefinition;
import com.argus.logging.codegen.model.EnumDefinition;
import com.argus.logging.codegen.model.EventDefinition;
import com.argus.logging.codegen.model.LogContract;
import com.argus.logging.codegen.model.StringLimits;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses a {@code log-contract.yaml} file into a {@link LogContract} model.
 *
 * <p>Uses raw SnakeYAML Maps so the parser is independent of the contract's
 * exact SnakeYAML bean-mapping strategy and remains easy to evolve.</p>
 */
final class ContractParser {

    LogContract parse(File contractFile) throws IOException {
        try (InputStream in = new FileInputStream(contractFile)) {
            return parse(in);
        }
    }

    @SuppressWarnings("unchecked")
    LogContract parse(InputStream in) {
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(in);

        LogContract contract = new LogContract();
        contract.setVersion(str(root, "version", "1.0"));
        contract.setNamespace(str(root, "namespace", "com.argus.logging.events"));

        // string_limits
        Map<String, Object> limitsMap = (Map<String, Object>) root.get("string_limits");
        if (limitsMap != null) {
            StringLimits limits = new StringLimits();
            limits.setDefaultMaxLength(integer(limitsMap, "default_max_length", 512));
            limits.setOverrideMaxLength(integer(limitsMap, "override_max_length", 4096));
            contract.setStringLimits(limits);
        }

        // enums
        Map<String, Object> enumsYaml = (Map<String, Object>) root.get("enums");
        if (enumsYaml != null) {
            Map<String, EnumDefinition> enums = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : enumsYaml.entrySet()) {
                enums.put(entry.getKey(), parseEnum(entry.getKey(), (Map<String, Object>) entry.getValue()));
            }
            contract.setEnums(enums);
        }

        // error_envelope — optional attributes auto-injected into ERROR/WARN events
        Map<String, Object> errorEnvelopeYaml = (Map<String, Object>) root.get("error_envelope");
        if (errorEnvelopeYaml != null) {
            Map<String, AttributeDefinition> errorEnvelope = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : errorEnvelopeYaml.entrySet()) {
                errorEnvelope.put(entry.getKey(),
                        parseAttribute(entry.getKey(), (Map<String, Object>) entry.getValue()));
            }
            contract.setErrorEnvelope(errorEnvelope);
        }

        // events
        Map<String, Object> eventsYaml = (Map<String, Object>) root.get("events");
        if (eventsYaml != null) {
            Map<String, EventDefinition> events = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : eventsYaml.entrySet()) {
                events.put(entry.getKey(), parseEvent(entry.getKey(), (Map<String, Object>) entry.getValue()));
            }
            contract.setEvents(events);
        }

        return contract;
    }

    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private EnumDefinition parseEnum(String name, Map<String, Object> data) {
        EnumDefinition def = new EnumDefinition();
        def.setName(name);
        def.setDescription(str(data, "description", ""));
        def.setValues((List<String>) data.get("values"));
        return def;
    }

    @SuppressWarnings("unchecked")
    private EventDefinition parseEvent(String name, Map<String, Object> data) {
        EventDefinition def = new EventDefinition();
        def.setEventName(name);
        def.setSeverity(str(data, "severity", "INFO"));
        def.setDescription(str(data, "description", ""));
        def.setBody(str(data, "body", ""));
        def.setContext((List<String>) data.getOrDefault("context", List.of()));

        Map<String, Object> attrsYaml = (Map<String, Object>) data.get("attributes");
        if (attrsYaml != null) {
            Map<String, AttributeDefinition> attrs = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : attrsYaml.entrySet()) {
                attrs.put(entry.getKey(), parseAttribute(entry.getKey(), (Map<String, Object>) entry.getValue()));
            }
            def.setAttributes(attrs);
        }
        return def;
    }

    private AttributeDefinition parseAttribute(String otelName, Map<String, Object> data) {
        AttributeDefinition def = new AttributeDefinition();
        def.setOtelName(otelName);
        def.setType(str(data, "type", "string"));
        def.setEnumType(str(data, "enum", null));
        def.setRequired(Boolean.TRUE.equals(data.get("required")));
        def.setMaxLength(data.containsKey("max_length") ? integer(data, "max_length", -1) : null);
        def.setDescription(str(data, "description", ""));
        return def;
    }

    // -------------------------------------------------------------------------

    private static String str(Map<String, Object> map, String key, String defaultValue) {
        Object v = map.get(key);
        return (v instanceof String s) ? s : defaultValue;
    }

    private static int integer(Map<String, Object> map, String key, int defaultValue) {
        Object v = map.get(key);
        return (v instanceof Number n) ? n.intValue() : defaultValue;
    }
}
