/**
 * Logger configuration.
 *
 * Validated once with zod at `createLogger()` time, normalized, and
 * deep-frozen. There is no reconfiguration API: format, level, and masking
 * rules are immutable for the lifetime of the logger. Note what is absent
 * by design — no `format`, no `serializers`, no `setLevel`.
 */

import { z } from 'zod';
import { LOG_LEVELS, levelFromEnvironment, type LogLevel } from './contract/levels';
import { ENVIRONMENTS, type Environment, type AttributeBag } from './contract/schema';
import { deepFreeze } from './contract/freeze';
import { Transport } from './transports/base';
import type { CustomPattern, MaskingOptions } from './masking/engine';
import { DEFAULT_MASKING_OPTIONS } from './masking/engine';
import type { OverflowPolicy } from './pipeline/ringBuffer';

/** Mutable view a beforeLog hook is allowed to touch. */
export interface LogDraft {
  message: string;
  context: AttributeBag;
  metadata: AttributeBag;
  /** Read-only situational fields for the hook's decision-making. */
  readonly level: LogLevel;
  readonly service: string;
  readonly environment: Environment;
}

/**
 * Pre-serialization middleware. May enrich/transform `message`, `context`
 * and `metadata` on the draft (or return a partial replacement for those
 * three fields). It CANNOT touch level, timestamp, trace ids, service or
 * environment — those never pass through hooks.
 */
export type BeforeLogHook = (draft: LogDraft) => Partial<
  Pick<LogDraft, 'message' | 'context' | 'metadata'>
> | void;

const ENV_ALIASES: Record<string, Environment> = {
  development: 'development',
  dev: 'development',
  local: 'development',
  staging: 'staging',
  stage: 'staging',
  production: 'production',
  prod: 'production',
  test: 'test',
};

function environmentFromEnvVars(env: NodeJS.ProcessEnv): Environment {
  const raw = (
    env['DEPLOYMENT_ENVIRONMENT'] ??
    env['APP_ENV'] ??
    env['NODE_ENV'] ??
    'development'
  ).toLowerCase();
  return ENV_ALIASES[raw] ?? 'development';
}

const customPatternSchema = z.object({
  name: z.string().min(1).max(64),
  pattern: z.instanceof(RegExp),
});

const configSchema = z
  .object({
    service: z.string().min(1).max(256).optional(),
    environment: z.string().optional(),
    level: z.enum(LOG_LEVELS).optional(),
    transports: z
      .array(z.custom<Transport>((value) => value instanceof Transport, 'must be a Transport instance'))
      .max(16)
      .optional(),
    buffer: z
      .object({
        capacity: z.number().int().min(16).max(1_000_000).default(8192),
        overflowPolicy: z.enum(['drop-oldest', 'drop-newest']).default('drop-oldest'),
        fallbackCapacity: z.number().int().min(16).max(1_000_000).default(4096),
      })
      .strict()
      .default({}),
    masking: z
      .object({
        enabled: z.boolean().default(true),
        allowList: z.array(z.string()).max(1024).default([]),
        customPatterns: z.array(customPatternSchema).max(64).default([]),
        customFieldMatchers: z.array(z.instanceof(RegExp)).max(64).default([]),
        maskIpAddresses: z.boolean().default(true),
      })
      .strict()
      .default({}),
    workers: z
      .object({
        enabled: z.boolean().default(false),
        poolSize: z.number().int().min(1).max(8).optional(),
        batchSize: z.number().int().min(1).max(4096).default(64),
      })
      .strict()
      .default({}),
    hooks: z
      .object({
        beforeLog: z.array(z.custom<BeforeLogHook>((v) => typeof v === 'function')).max(16).default([]),
      })
      .strict()
      .default({}),
    selfMetrics: z
      .object({
        enabled: z.boolean().default(true),
        intervalMs: z.number().int().min(1000).max(3_600_000).default(60_000),
        /** When set, serves GET /metrics on this port (loopback only by default). */
        port: z.number().int().min(1).max(65535).optional(),
        host: z.string().default('127.0.0.1'),
      })
      .strict()
      .default({}),
    /** Install a beforeExit hook that flushes the pipeline. Default true. */
    installExitHooks: z.boolean().default(true),
  })
  .strict();

export type ArgusConfigInput = z.input<typeof configSchema>;

export interface ResolvedConfig {
  readonly service: string;
  readonly environment: Environment;
  readonly level: LogLevel;
  readonly buffer: {
    readonly capacity: number;
    readonly overflowPolicy: OverflowPolicy;
    readonly fallbackCapacity: number;
  };
  readonly masking: MaskingOptions;
  readonly workers: {
    readonly enabled: boolean;
    readonly poolSize?: number | undefined;
    readonly batchSize: number;
  };
  readonly hooks: { readonly beforeLog: readonly BeforeLogHook[] };
  readonly selfMetrics: {
    readonly enabled: boolean;
    readonly intervalMs: number;
    readonly port?: number | undefined;
    readonly host: string;
  };
  readonly installExitHooks: boolean;
}

export class ConfigurationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ConfigurationError';
  }
}

/**
 * Validate, normalize (env-var fallbacks), and permanently freeze the
 * configuration. Transports are returned separately — they are stateful
 * stream objects and must not be frozen.
 */
export function resolveConfig(
  input: ArgusConfigInput = {},
  env: NodeJS.ProcessEnv = process.env,
): { config: ResolvedConfig; transports: readonly Transport[] } {
  const parsed = configSchema.safeParse(input);
  if (!parsed.success) {
    throw new ConfigurationError(
      `Invalid Argus configuration: ${parsed.error.issues
        .map((issue) => `${issue.path.join('.') || '(root)'}: ${issue.message}`)
        .join('; ')}`,
    );
  }
  const raw = parsed.data;

  const environment =
    raw.environment !== undefined
      ? ENV_ALIASES[raw.environment.toLowerCase()]
      : environmentFromEnvVars(env);
  if (!environment) {
    throw new ConfigurationError(
      `Unknown environment "${raw.environment}"; expected one of ${ENVIRONMENTS.join(', ')} (or aliases dev/stage/prod/local)`,
    );
  }

  const masking: MaskingOptions = {
    ...DEFAULT_MASKING_OPTIONS,
    enabled: raw.masking.enabled,
    allowList: raw.masking.allowList,
    customPatterns: raw.masking.customPatterns as CustomPattern[],
    customFieldMatchers: raw.masking.customFieldMatchers,
    maskIpAddresses: raw.masking.maskIpAddresses,
  };

  const config: ResolvedConfig = {
    service: raw.service ?? env['OTEL_SERVICE_NAME'] ?? 'unknown-service',
    environment,
    level: raw.level ?? levelFromEnvironment(env),
    buffer: raw.buffer,
    masking,
    workers: raw.workers,
    hooks: { beforeLog: raw.hooks.beforeLog },
    selfMetrics: raw.selfMetrics,
    installExitHooks: raw.installExitHooks,
  };

  return { config: deepFreeze(config), transports: Object.freeze(raw.transports ?? []) };
}
