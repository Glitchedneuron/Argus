package com.argus.tracer.internal;

import com.argus.tracer.ArgusTracer;
import datadog.opentracing.DDTracer;
import io.opentracing.util.GlobalTracer;

import java.util.Properties;

/**
 * Builds or discovers a Datadog native tracer and wraps it in a {@link DatadogSdkTracerBackend}.
 *
 * <p>If {@code dd-java-agent.jar} is already attached as a {@code -javaagent} and has registered
 * itself with {@link GlobalTracer}, that instance is reused. Otherwise a {@link DDTracer} is built
 * programmatically using resolved configuration and registered as the global tracer.</p>
 *
 * <p>Requires {@code dd-trace-ot} on the runtime classpath (provided by the fat-JAR agent or
 * added explicitly when using the library directly).</p>
 */
public final class DatadogSdkInitializer {

    private DatadogSdkInitializer() {}

    /**
     * @param serviceName    nullable — resolved against {@code OTEL_SERVICE_NAME}
     * @param serviceVersion nullable — resolved against {@code OTEL_SERVICE_VERSION}
     * @param environment    nullable — resolved against {@code DEPLOYMENT_ENVIRONMENT}
     * @param agentHost      nullable — resolved against {@code DD_AGENT_HOST}
     * @param agentPort      0 means resolve from {@code DD_TRACE_AGENT_PORT}, else 8126
     */
    public static ArgusTracer initialize(
            String serviceName,
            String serviceVersion,
            String environment,
            String agentHost,
            int    agentPort) {

        String resolvedName = SdkInitializer.resolve(serviceName,    "OTEL_SERVICE_NAME",    "unknown-service");
        String resolvedVer  = SdkInitializer.resolve(serviceVersion, "OTEL_SERVICE_VERSION", "unknown");
        String resolvedEnv  = SdkInitializer.resolve(environment,    "DEPLOYMENT_ENVIRONMENT",
                               SdkInitializer.resolve(null,          "APP_ENV",              "development"));
        String resolvedHost = SdkInitializer.resolve(agentHost,      "DD_AGENT_HOST",        "localhost");
        String portStr      = SdkInitializer.resolve(null,           "DD_TRACE_AGENT_PORT",
                               agentPort > 0 ? String.valueOf(agentPort) : "8126");
        int    resolvedPort = Integer.parseInt(portStr);

        if (!GlobalTracer.isRegistered()) {
            Properties props = new Properties();
            props.setProperty("service.name",      resolvedName);
            props.setProperty("version",           resolvedVer);
            props.setProperty("env",               resolvedEnv);
            props.setProperty("agent.host",        resolvedHost);
            props.setProperty("trace.agent.port",  String.valueOf(resolvedPort));

            DDTracer ddTracer = DDTracer.builder()
                    .withProperties(props)
                    .build();
            GlobalTracer.registerIfAbsent(ddTracer);
        }

        return new DatadogSdkTracerBackend(GlobalTracer.get());
    }
}
