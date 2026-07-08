/**
 * Console enforcement (opt-in).
 *
 * `enforceConsoleContract(logger)` rewires console.log/info/warn/error/
 * debug/trace to the contract pipeline and freezes the console object so
 * application code cannot swap the methods back. Raw `console.log` output
 * — unstructured, unmasked, un-correlated — becomes impossible.
 */

import type { ArgusLogger } from '../logger';

function toMessage(args: unknown[]): { message: string; context: Record<string, unknown> } {
  if (args.length === 0) return { message: '(empty console call)', context: {} };
  const [first, ...rest] = args;
  const message =
    typeof first === 'string'
      ? first
      : first instanceof Error
        ? first.message
        : JSON.stringify(first) ?? String(first);
  const context: Record<string, unknown> = {};
  if (rest.length > 0) {
    context['consoleArgs'] = rest.map((arg) =>
      arg instanceof Error
        ? { type: arg.name, message: arg.message }
        : arg === undefined
          ? null
          : (arg as unknown),
    );
  }
  return { message: message.length > 0 ? message : '(empty message)', context };
}

/**
 * Redirect all console output through the logger and freeze `console`.
 * Irreversible for the lifetime of the process — by design.
 */
export function enforceConsoleContract(logger: ArgusLogger): void {
  const route =
    (level: 'debug' | 'info' | 'warn' | 'error') =>
    (...args: unknown[]): void => {
      const { message, context } = toMessage(args);
      const err = args.find((a): a is Error => a instanceof Error);
      if ((level === 'error') && err) {
        logger.error(message, err);
      } else {
        logger[level](message, context);
      }
    };

  console.log = route('info');
  console.info = route('info');
  console.debug = route('debug');
  console.warn = route('warn');
  console.error = route('error');
  console.trace = route('debug');
  Object.freeze(console);
}
