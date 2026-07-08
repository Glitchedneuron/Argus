import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { setTimeout as sleep } from 'node:timers/promises';
import { withContext, withRequestScope, activeContext } from '../src/context/manager';
import { parseTraceparent } from '../src/contract/ids';

describe('async context propagation', () => {
  it('flows through await boundaries', async () => {
    await withContext({ requestId: 'req-1', userId: 7 }, async () => {
      await sleep(5);
      const ctx = activeContext();
      assert.equal(ctx['requestId'], 'req-1');
      assert.equal(ctx['userId'], 7);
    });
  });

  it('flows through promise chains and timers', async () => {
    await withContext({ requestId: 'req-2' }, () =>
      Promise.resolve()
        .then(() => new Promise((resolve) => setTimeout(resolve, 5)))
        .then(() => {
          assert.equal(activeContext()['requestId'], 'req-2');
        }),
    );
  });

  it('supports nested contexts with inheritance (service-to-service)', async () => {
    await withContext({ requestId: 'outer', tenant: 'acme' }, async () => {
      await withContext({ hop: 'inventory-service' }, async () => {
        const ctx = activeContext();
        assert.equal(ctx['requestId'], 'outer'); // inherited
        assert.equal(ctx['hop'], 'inventory-service'); // added
      });
      assert.equal(activeContext()['hop'], undefined); // inner scope ended
    });
  });

  it('isolates parallel scopes', async () => {
    await Promise.all(
      ['a', 'b', 'c'].map((id) =>
        withContext({ requestId: id }, async () => {
          await sleep(Math.random() * 10);
          assert.equal(activeContext()['requestId'], id);
        }),
      ),
    );
  });

  it('withRequestScope generates missing ids and keeps provided ones', () => {
    withRequestScope({}, () => {
      const ctx = activeContext();
      assert.match(String(ctx['traceId']), /^[0-9a-f]{32}$/);
      assert.match(String(ctx['spanId']), /^[0-9a-f]{16}$/);
      assert.ok(ctx['requestId']);
    });
    const traceId = 'a'.repeat(32);
    withRequestScope({ traceId }, () => {
      assert.equal(activeContext()['traceId'], traceId);
    });
  });

  it('parses W3C traceparent headers', () => {
    const parsed = parseTraceparent(`00-${'ab'.repeat(16)}-${'cd'.repeat(8)}-01`);
    assert.deepEqual(parsed, { traceId: 'ab'.repeat(16), spanId: 'cd'.repeat(8) });
    assert.equal(parseTraceparent('garbage'), null);
    assert.equal(parseTraceparent(undefined), null);
    assert.equal(parseTraceparent(`00-${'0'.repeat(32)}-${'cd'.repeat(8)}-01`), null);
  });
});
