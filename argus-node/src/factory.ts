/**
 * Logger construction — the only public entry point.
 *
 * `createLogger()` is zero-config: service/environment/level fall back to
 * OTEL_SERVICE_NAME, DEPLOYMENT_ENVIRONMENT/NODE_ENV and LOG_LEVEL, and a
 * structured-JSON stdout transport is installed when none is given.
 * The Java-style `ArgusLoggerFactory.builder()` API is provided for teams
 * migrating from Argus for Java.
 */

import { resolveConfig, type ArgusConfigInput } from './config';
import { ArgusLogger } from './logger';
import { StdoutTransport } from './transports/stdout';
import type { Transport } from './transports/base';
import type { LogLevel } from './contract/levels';

const registeredLoggers: ArgusLogger[] = [];
let exitHookInstalled = false;

function installExitHook(): void {
  if (exitHookInstalled) return;
  exitHookInstalled = true;
  process.once('beforeExit', () => {
    for (const logger of registeredLoggers) {
      void logger.shutdown();
    }
  });
}

/** Create a sealed logger. Configuration is immutable after this call. */
export function createLogger(input: ArgusConfigInput = {}): ArgusLogger {
  const { config, transports } = resolveConfig(input);
  const effectiveTransports: readonly Transport[] =
    transports.length > 0 ? transports : Object.freeze([new StdoutTransport()]);
  const logger = new ArgusLogger(config, effectiveTransports);
  registeredLoggers.push(logger);
  if (config.installExitHooks) installExitHook();
  return logger;
}

/** Builder API mirroring Argus for Java's ArgusLoggerFactory. */
export class ArgusLoggerFactory {
  #input: ArgusConfigInput = {};
  #built = false;

  static builder(): ArgusLoggerFactory {
    return new ArgusLoggerFactory();
  }

  serviceName(service: string): this {
    this.#assertNotBuilt();
    this.#input = { ...this.#input, service };
    return this;
  }

  environment(environment: string): this {
    this.#assertNotBuilt();
    this.#input = { ...this.#input, environment };
    return this;
  }

  level(level: LogLevel): this {
    this.#assertNotBuilt();
    this.#input = { ...this.#input, level };
    return this;
  }

  transports(...transports: Transport[]): this {
    this.#assertNotBuilt();
    this.#input = { ...this.#input, transports };
    return this;
  }

  configure(input: Omit<ArgusConfigInput, 'service' | 'environment' | 'level' | 'transports'>): this {
    this.#assertNotBuilt();
    this.#input = { ...this.#input, ...input };
    return this;
  }

  /** Builds exactly once; the factory is single-use to prevent config reuse drift. */
  build(): ArgusLogger {
    this.#assertNotBuilt();
    this.#built = true;
    return createLogger(this.#input);
  }

  #assertNotBuilt(): void {
    if (this.#built) {
      throw new Error('ArgusLoggerFactory is single-use: build() was already called');
    }
  }
}
