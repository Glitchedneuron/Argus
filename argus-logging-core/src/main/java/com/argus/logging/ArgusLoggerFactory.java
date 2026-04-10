package com.argus.logging;

import com.argus.logging.internal.OtelInitializer;
import io.opentelemetry.api.OpenTelemetry;

/**
 * Entry point for obtaining {@link ArgusLogger} instances.
 *
 * <h2>Quick start (env-var driven)</h2>
 * <pre>{@code
 * private static final ArgusLogger LOG =
 *         ArgusLoggerFactory.create().getLogger(MyService.class.getName());
 * }</pre>
 *
 * <h2>Explicit configuration</h2>
 * <pre>{@code
 * ArgusLoggerFactory factory = ArgusLoggerFactory.builder()
 *         .serviceName("payment-service")
 *         .serviceVersion("2.1.0")
 *         .environment("production")
 *         .build();
 * }</pre>
 *
 * <h2>Bring your own OTel SDK</h2>
 * <p>If your application already initialises the OTel SDK (e.g. via the Java agent or
 * a shared bootstrap class), pass the existing instance to avoid creating a second SDK:</p>
 * <pre>{@code
 * ArgusLoggerFactory factory = ArgusLoggerFactory.builder()
 *         .withOpenTelemetry(existingOpenTelemetry)
 *         .build();
 * }</pre>
 *
 * <h2>Exporter selection</h2>
 * <p>When the factory creates its own OTel SDK:</p>
 * <ul>
 *   <li>{@code OTEL_EXPORTER_OTLP_ENDPOINT} set → OTLP/HTTP exporter (production)</li>
 *   <li>Not set → pretty console exporter (dev / local)</li>
 * </ul>
 */
public final class ArgusLoggerFactory {

    /** OTel instrumentation scope name — visible in back-end UIs as the logger/library name. */
    private static final String INSTRUMENTATION_SCOPE = "com.argus.logging";
    private static final String INSTRUMENTATION_VERSION = "1.0.0";

    private final io.opentelemetry.api.logs.Logger otelLogger;

    private ArgusLoggerFactory(OpenTelemetry openTelemetry) {
        this.otelLogger = openTelemetry.getLogsBridge()
                .loggerBuilder(INSTRUMENTATION_SCOPE)
                .setInstrumentationVersion(INSTRUMENTATION_VERSION)
                .build();
    }

    /**
     * Creates a factory using default environment-variable-driven configuration.
     * Equivalent to {@code ArgusLoggerFactory.builder().build()}.
     */
    public static ArgusLoggerFactory create() {
        return builder().build();
    }

    /** Returns a builder for explicit configuration. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns an {@link ArgusLogger}.  The {@code name} parameter is carried as metadata
     * for debugging; all loggers share the same underlying OTel logger within a factory.
     *
     * @param name typically {@code MyClass.class.getName()}
     */
    public ArgusLogger getLogger(String name) {
        return new ArgusLogger(otelLogger);
    }

    // -------------------------------------------------------------------------

    public static final class Builder {

        private OpenTelemetry openTelemetry;
        private String serviceName;
        private String serviceVersion;
        private String environment;

        /**
         * Use an already-initialised OTel SDK instead of creating a standalone one.
         * When set, {@link #serviceName}, {@link #serviceVersion}, and {@link #environment}
         * are ignored (the existing SDK's Resource is used).
         */
        public Builder withOpenTelemetry(OpenTelemetry openTelemetry) {
            this.openTelemetry = openTelemetry;
            return this;
        }

        /**
         * Overrides {@code OTEL_SERVICE_NAME} env var.
         * Defaults to {@code OTEL_SERVICE_NAME} → {@code "unknown-service"}.
         */
        public Builder serviceName(String serviceName) {
            this.serviceName = serviceName;
            return this;
        }

        /**
         * Overrides {@code OTEL_SERVICE_VERSION} env var.
         * Defaults to {@code OTEL_SERVICE_VERSION} → {@code "unknown"}.
         */
        public Builder serviceVersion(String serviceVersion) {
            this.serviceVersion = serviceVersion;
            return this;
        }

        /**
         * Overrides {@code DEPLOYMENT_ENVIRONMENT} env var.
         * Defaults to {@code DEPLOYMENT_ENVIRONMENT} → {@code APP_ENV} → {@code "development"}.
         */
        public Builder environment(String environment) {
            this.environment = environment;
            return this;
        }

        public ArgusLoggerFactory build() {
            OpenTelemetry otel = (openTelemetry != null)
                    ? openTelemetry
                    : OtelInitializer.initialize(serviceName, serviceVersion, environment);
            return new ArgusLoggerFactory(otel);
        }
    }
}
