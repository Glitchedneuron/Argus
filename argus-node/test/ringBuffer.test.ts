import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { RingBuffer } from '../src/pipeline/ringBuffer';

describe('ring buffer', () => {
  it('is FIFO within capacity', () => {
    const ring = new RingBuffer<number>(4);
    ring.push(1);
    ring.push(2);
    ring.push(3);
    assert.equal(ring.shift(), 1);
    assert.equal(ring.shift(), 2);
    ring.push(4);
    ring.push(5);
    assert.deepEqual([ring.shift(), ring.shift(), ring.shift()], [3, 4, 5]);
    assert.equal(ring.shift(), undefined);
  });

  it('drop-oldest evicts the oldest entry and counts drops', () => {
    const ring = new RingBuffer<number>(3, 'drop-oldest');
    for (const n of [1, 2, 3, 4, 5]) ring.push(n);
    assert.equal(ring.droppedCount, 2);
    assert.deepEqual([ring.shift(), ring.shift(), ring.shift()], [3, 4, 5]);
  });

  it('drop-newest rejects incoming entries when full', () => {
    const ring = new RingBuffer<number>(2, 'drop-newest');
    assert.equal(ring.push(1), true);
    assert.equal(ring.push(2), true);
    assert.equal(ring.push(3), false);
    assert.equal(ring.droppedCount, 1);
    assert.deepEqual([ring.shift(), ring.shift()], [1, 2]);
  });

  it('drainInto respects max', () => {
    const ring = new RingBuffer<number>(8);
    for (let i = 0; i < 6; i++) ring.push(i);
    const out: number[] = [];
    assert.equal(ring.drainInto(out, 4), 4);
    assert.deepEqual(out, [0, 1, 2, 3]);
    assert.equal(ring.size, 2);
  });
});
