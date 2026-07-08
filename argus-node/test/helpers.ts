/**
 * Shared test helpers: an in-memory capture transport.
 */

import { Transport, type LogEnvelope } from '../src/transports/base';
import type { LogRecord } from '../src/contract/schema';

export class CaptureTransport extends Transport {
  public readonly lines: string[] = [];
  public readonly records: Readonly<LogRecord>[] = [];
  public failNext = 0;

  constructor() {
    super('capture');
  }

  protected writeEnvelope(envelope: LogEnvelope): void {
    if (this.failNext > 0) {
      this.failNext--;
      throw new Error('simulated transport failure');
    }
    this.lines.push(envelope.line);
    this.records.push(envelope.record);
  }
}
