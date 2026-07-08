/**
 * Stdout transport — structured NDJSON only.
 *
 * This is the sanctioned replacement for `console.log`: one JSON object per
 * line on stdout (or stderr for ERROR/FATAL when `splitStderr` is set),
 * honoring stream backpressure.
 */

import { Transport, writeWithBackpressure, type LogEnvelope, type TransportOptions } from './base';
import { LEVEL_RANK } from '../contract/levels';

export interface StdoutTransportOptions extends TransportOptions {
  /** Route ERROR/FATAL to stderr instead of stdout. Default false. */
  splitStderr?: boolean;
  /** Pretty-print records (dev convenience). Default: auto (TTY and not production). */
  pretty?: boolean;
}

export class StdoutTransport extends Transport {
  readonly #splitStderr: boolean;
  readonly #pretty: boolean;

  constructor(options: StdoutTransportOptions = {}) {
    super('stdout', options);
    this.#splitStderr = options.splitStderr ?? false;
    this.#pretty =
      options.pretty ?? (process.stdout.isTTY === true && process.env['NODE_ENV'] !== 'production');
  }

  protected writeEnvelope(envelope: LogEnvelope): Promise<void> {
    const target =
      this.#splitStderr && LEVEL_RANK[envelope.record.level] >= LEVEL_RANK.ERROR
        ? process.stderr
        : process.stdout;
    const chunk = this.#pretty
      ? JSON.stringify(envelope.record, null, 2) + '\n'
      : envelope.line + '\n';
    return writeWithBackpressure(target, chunk);
  }
}
