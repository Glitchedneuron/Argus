package com.argus.tracing;

/**
 * Immutable configuration snapshot passed to a tracing backend at construction time.
 *
 * <p>All fields are nullable; backends resolve missing values from environment variables
 * using the same precedence rules as {@code ArgusLoggerFactory}:</p>
 * <ul>
 *   <li>{@code serviceName} → {@code OTEL_SERVICE_NAME} → {@code "unknown-service"}</li>
 *   <li>{@code serviceVersion} → {@code OTEL_SERVICE_VERSION} → {@code "unknown"}</li>
 *   <li>{@code environment} → {@code DEPLOYMENT_ENVIRONMENT} → {@code APP_ENV} → {@code "development"}</li>
 *   <li>{@code exporterEndpoint} → {@code OTEL_EXPORTER_OTLP_ENDPOINT} → console (OTel backend only)</li>
 * </ul>
 */
public record TracerConfig(
        /** Already-resolved backend enum value. Never null inside a constructed backend. */
        Backend backend,
        /** Overrides {@code OTEL_SERVICE_NAME}. Nullable. */
        String serviceName,
        /** Overrides {@code OTEL_SERVICE_VERSION}. Nullable. */
        String serviceVersion,
        /** Overrides {@code DEPLOYMENT_ENVIRONMENT}. Nullable. */
        String environment,
        /**
         * OTLP endpoint for the OTel backend.
         * Nullable — falls back to {@code OTEL_EXPORTER_OTLP_ENDPOINT} env var,
         * then to the console exporter in dev mode.
         */
        String exporterEndpoint
) {}
