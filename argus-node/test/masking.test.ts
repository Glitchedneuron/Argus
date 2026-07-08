import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { PiiMaskingEngine } from '../src/masking/engine';

function mask(context: Record<string, unknown>, engine = new PiiMaskingEngine()) {
  const carrier = { message: 'test message', context, metadata: {} };
  const result = engine.maskRecord(carrier);
  return { carrier, result };
}

describe('PII masking', () => {
  it('masks email addresses in values', () => {
    const { carrier, result } = mask({ user: 'contact alice@example.com now' });
    assert.equal(carrier.context['user'], 'contact [REDACTED:email] now');
    assert.deepEqual(result.maskedFields, ['context.user']);
  });

  it('masks Luhn-valid credit card numbers but not random digit runs', () => {
    const { carrier } = mask({
      card: 'pay with 4532 0151 1283 0366',
      order: 'order 1234 5678 9012 3456 is fine',
    });
    assert.match(String(carrier.context['card']), /\[REDACTED:credit-card\]/);
    // 1234...3456 fails Luhn — must NOT be masked as a card.
    assert.match(String(carrier.context['order']), /1234 5678 9012 3456/);
  });

  it('masks SSNs, phone numbers and IPv4 addresses in values', () => {
    const { carrier } = mask({
      note: 'ssn is 123-45-6789',
      contact: 'call +1-415-555-0123 or (415) 555-0123',
      client: 'client 192.168.1.100',
    });
    assert.match(String(carrier.context['note']), /\[REDACTED:ssn\]/);
    assert.equal(String(carrier.context['contact']), 'call [REDACTED:phone] or [REDACTED:phone]');
    assert.match(String(carrier.context['client']), /\[REDACTED:ipv4\]/);
  });

  it('masks the ssn field name wholesale', () => {
    const { carrier } = mask({ ssn: '123-45-6789' });
    assert.equal(carrier.context['ssn'], '[REDACTED:sensitive-field]');
  });

  it('does not mangle plain numeric text (no false positives)', () => {
    const { carrier } = mask({
      note: 'processed 1234 records in 5678 ms, batch 9012',
      version: 'v1.2.3 build 456',
    });
    assert.equal(carrier.context['note'], 'processed 1234 records in 5678 ms, batch 9012');
    assert.equal(carrier.context['version'], 'v1.2.3 build 456');
  });

  it('masks API keys, bearer tokens and JWTs', () => {
    const { carrier } = mask({
      a: 'key sk-abcdefghijklmnop1234',
      b: 'Authorization: Bearer abcdef123456789',
      c: 'jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U',
      d: 'aws AKIAIOSFODNN7EXAMPLE',
    });
    assert.match(String(carrier.context['a']), /\[REDACTED:api-key\]/);
    assert.match(String(carrier.context['b']), /\[REDACTED:bearer-token\]/);
    assert.match(String(carrier.context['c']), /\[REDACTED:jwt\]/);
    assert.match(String(carrier.context['d']), /\[REDACTED:api-key\]/);
  });

  it('masks sensitive field names wholesale', () => {
    const { carrier, result } = mask({
      password: 'hunter2',
      apiKey: 'whatever',
      nested: { client_secret: 'x', ok: 'visible' },
    });
    assert.equal(carrier.context['password'], '[REDACTED:sensitive-field]');
    assert.equal(carrier.context['apiKey'], '[REDACTED:sensitive-field]');
    assert.equal((carrier.context['nested'] as Record<string, unknown>)['client_secret'], '[REDACTED:sensitive-field]');
    assert.equal((carrier.context['nested'] as Record<string, unknown>)['ok'], 'visible');
    assert.ok(result.maskedFields.includes('context.password'));
    assert.ok(result.maskedFields.includes('context.nested.client_secret'));
  });

  it('respects the allow list', () => {
    const engine = new PiiMaskingEngine({ allowList: ['context.ip'] });
    const { carrier } = mask({ ip: '10.0.0.1', other: '10.0.0.2' }, engine);
    assert.equal(carrier.context['ip'], '10.0.0.1');
    assert.equal(carrier.context['other'], '[REDACTED:ipv4]');
  });

  it('supports custom patterns', () => {
    const engine = new PiiMaskingEngine({
      customPatterns: [{ name: 'employee-id', pattern: /EMP-\d{6}/ }],
    });
    const { carrier } = mask({ who: 'employee EMP-123456 did it' }, engine);
    assert.equal(carrier.context['who'], 'employee [REDACTED:employee-id] did it');
  });

  it('masks inside arrays and the message itself', () => {
    const engine = new PiiMaskingEngine();
    const carrier = {
      message: 'user bob@example.com logged in',
      context: { emails: ['a@b.co', 'clean'] },
      metadata: {},
    };
    const { maskedFields } = engine.maskRecord(carrier);
    assert.equal(carrier.message, 'user [REDACTED:email] logged in');
    assert.equal((carrier.context['emails'] as string[])[0], '[REDACTED:email]');
    assert.ok(maskedFields.includes('message'));
    assert.ok(maskedFields.includes('context.emails[0]'));
  });

  it('counts masking actions for audit', () => {
    const engine = new PiiMaskingEngine();
    mask({ email: 'x@y.zz' }, engine);
    mask({ password: 'p' }, engine);
    assert.ok(engine.totalMasked >= 2);
  });

  it('can be disabled explicitly', () => {
    const engine = new PiiMaskingEngine({ enabled: false });
    const { carrier } = mask({ email: 'x@y.zz' }, engine);
    assert.equal(carrier.context['email'], 'x@y.zz');
  });
});
