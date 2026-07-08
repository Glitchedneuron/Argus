/**
 * File transport with size-based rotation.
 *
 * Appends NDJSON lines via a `fs.WriteStream` (async I/O, backpressure
 * aware). When the active file exceeds `maxBytes` it is rotated:
 * app.log -> app.log.1 -> app.log.2 ... up to `maxFiles`, oldest deleted.
 */

import { createWriteStream, type WriteStream } from 'node:fs';
import { mkdir, rename, rm, stat } from 'node:fs/promises';
import { dirname } from 'node:path';
import { once } from 'node:events';
import { Transport, type LogEnvelope, type TransportOptions } from './base';

export interface FileTransportOptions extends TransportOptions {
  /** Absolute or CWD-relative path of the active log file. */
  path: string;
  /** Rotate when the active file exceeds this many bytes. Default 50 MiB. */
  maxBytes?: number;
  /** How many rotated files to keep. Default 5. */
  maxFiles?: number;
}

export class FileTransport extends Transport {
  readonly #path: string;
  readonly #maxBytes: number;
  readonly #maxFiles: number;
  #stream: WriteStream | null = null;
  #bytesWritten = 0;
  #opening: Promise<void> | null = null;

  constructor(options: FileTransportOptions) {
    super('file', options);
    if (!options.path) throw new TypeError('FileTransport requires a `path`');
    this.#path = options.path;
    this.#maxBytes = options.maxBytes ?? 50 * 1024 * 1024;
    this.#maxFiles = options.maxFiles ?? 5;
  }

  async #ensureStream(): Promise<WriteStream> {
    const existing = this.#stream;
    if (existing) return existing;
    this.#opening ??= (async () => {
      await mkdir(dirname(this.#path), { recursive: true });
      this.#bytesWritten = await stat(this.#path).then(
        (s) => s.size,
        () => 0,
      );
      const stream = createWriteStream(this.#path, { flags: 'a' });
      // Swallow stream errors here; write() rejections surface via _write.
      stream.on('error', () => undefined);
      this.#stream = stream;
    })();
    await this.#opening;
    this.#opening = null;
    return this.#stream as unknown as WriteStream;
  }

  async #rotate(): Promise<void> {
    const stream = this.#stream;
    this.#stream = null;
    this.#bytesWritten = 0;
    if (stream) {
      stream.end();
      await once(stream, 'close').catch(() => undefined);
    }
    // Shift app.log.(n) -> app.log.(n+1); delete the oldest.
    await rm(`${this.#path}.${this.#maxFiles}`, { force: true }).catch(() => undefined);
    for (let i = this.#maxFiles - 1; i >= 1; i--) {
      await rename(`${this.#path}.${i}`, `${this.#path}.${i + 1}`).catch(() => undefined);
    }
    await rename(this.#path, `${this.#path}.1`).catch(() => undefined);
  }

  protected async writeEnvelope(envelope: LogEnvelope): Promise<void> {
    if (this.#bytesWritten >= this.#maxBytes) {
      await this.#rotate();
    }
    const stream = await this.#ensureStream();
    const chunk = envelope.line + '\n';
    this.#bytesWritten += Buffer.byteLength(chunk);
    if (!stream.write(chunk)) {
      await once(stream, 'drain');
    }
  }

  override async flush(): Promise<void> {
    const stream = this.#stream;
    if (!stream || stream.destroyed) return;
    // A zero-length write's callback fires only after every previously
    // queued chunk has been handed to the kernel.
    await new Promise<void>((resolve) => stream.write('', () => resolve()));
  }

  override async close(): Promise<void> {
    await super.close();
    const stream = this.#stream;
    this.#stream = null;
    if (stream && !stream.destroyed) {
      stream.end();
      await once(stream, 'close').catch(() => undefined);
    }
  }
}
