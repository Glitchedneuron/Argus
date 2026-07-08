/**
 * Log levels and their OpenTelemetry severity mapping.
 *
 * The level set is closed by contract: DEBUG, INFO, WARN, ERROR, FATAL.
 * Severity numbers follow the OTel Logs Data Model
 * (https://opentelemetry.io/docs/specs/otel/logs/data-model/#field-severitynumber).
 */

export const LOG_LEVELS = ['DEBUG', 'INFO', 'WARN', 'ERROR', 'FATAL'] as const;

export type LogLevel = (typeof LOG_LEVELS)[number];

/** OTel severity numbers for each contract level. */
export const SEVERITY_NUMBER: Readonly<Record<LogLevel, number>> = Object.freeze({
  DEBUG: 5,
  INFO: 9,
  WARN: 13,
  ERROR: 17,
  FATAL: 21,
});

/** Numeric rank used for level filtering (higher = more severe). */
export const LEVEL_RANK: Readonly<Record<LogLevel, number>> = Object.freeze({
  DEBUG: 0,
  INFO: 1,
  WARN: 2,
  ERROR: 3,
  FATAL: 4,
});

export function isLogLevel(value: unknown): value is LogLevel {
  return typeof value === 'string' && (LOG_LEVELS as readonly string[]).includes(value);
}

/** Resolve the minimum level from the environment (LOG_LEVEL / ARGUS_LOG_LEVEL / NODE_ENV). */
export function levelFromEnvironment(env: NodeJS.ProcessEnv = process.env): LogLevel {
  const raw = (env['ARGUS_LOG_LEVEL'] ?? env['LOG_LEVEL'] ?? '').toUpperCase();
  if (isLogLevel(raw)) return raw;
  // Sensible zero-config defaults: verbose in dev, lean in prod.
  return env['NODE_ENV'] === 'production' ? 'INFO' : 'DEBUG';
}
