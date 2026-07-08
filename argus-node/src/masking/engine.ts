/**
 * PII masking engine.
 *
 * Runs BEFORE validation and serialization, so sensitive values never reach
 * a transport. Two detector families:
 *
 *   1. Field-name detectors — keys like `password`, `apiKey`, `ssn` are
 *      masked wholesale regardless of value.
 *   2. Value detectors — string values are scanned for emails, phone
 *      numbers, credit cards (Luhn-checked), SSNs, IPs, JWTs and well-known
 *      API-key shapes.
 *
 * Every masking action is recorded (dot-path) on the record's `maskedFields`
 * so downstream consumers can audit what was redacted, and counted in the
 * logger's health metrics.
 */

export interface CustomPattern {
  /** Short identifier used in the redaction marker, e.g. "employee-id". */
  name: string;
  /** Regex applied to string VALUES. Must not use the `g`-less lastIndex trap; a fresh copy is taken. */
  pattern: RegExp;
}

export interface MaskingOptions {
  enabled: boolean;
  /** Dot-paths (within context/metadata) that are never masked, e.g. "context.ip". */
  allowList: readonly string[];
  /** Additional value patterns supplied by the application. */
  customPatterns: readonly CustomPattern[];
  /** Additional field-name regexes supplied by the application. */
  customFieldMatchers: readonly RegExp[];
  /** Mask IPv4 addresses in values (on by default — IPs are personal data under GDPR). */
  maskIpAddresses: boolean;
}

export const DEFAULT_MASKING_OPTIONS: MaskingOptions = Object.freeze({
  enabled: true,
  allowList: Object.freeze([]) as readonly string[],
  customPatterns: Object.freeze([]) as readonly CustomPattern[],
  customFieldMatchers: Object.freeze([]) as readonly RegExp[],
  maskIpAddresses: true,
});

export interface MaskingResult {
  /** Dot-paths of every masked field/value, e.g. "context.user.email". */
  maskedFields: string[];
}

const MASK = (kind: string): string => `[REDACTED:${kind}]`;

/** Keys whose values are always masked, whatever they contain. */
const SENSITIVE_FIELD_NAME =
  /(^|[._-])(password|passwd|pwd|secret|token|api[_-]?key|apikey|authorization|auth[_-]?header|access[_-]?key|private[_-]?key|client[_-]?secret|ssn|social[_-]?security|credit[_-]?card|card[_-]?number|cvv|cvc|pin)([._-]|$)/i;

interface ValueDetector {
  kind: string;
  pattern: RegExp;
  /** Optional post-match verifier (e.g. Luhn for card numbers). */
  verify?: (match: string) => boolean;
}

const EMAIL = /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g;
// 13–19 digits allowing space/dash separators, bounded to avoid matching inside longer digit runs.
const CREDIT_CARD = /(?<![\d-])(?:\d[ -]?){12,18}\d(?![\d-])/g;
const SSN = /(?<!\d)\d{3}-\d{2}-\d{4}(?!\d)/g;
// Phone shapes only — a leading +country, parenthesized area code, or a
// strict 3-3-4 separated layout. Deliberately NOT "any digit run": loose
// phone matchers shred order numbers and byte counts.
const PHONE =
  /(?<![\w.-])(?:\+\d{1,3}[ .-]?\(?\d{2,4}\)?[ .-]?\d{3,4}[ .-]?\d{3,4}|\(\d{3}\)[ .-]?\d{3}[ .-]?\d{4}|\d{3}[ .-]\d{3}[ .-]\d{4})(?![\w-])/g;
const IPV4 = /(?<!\d)(?:(?:25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(?:25[0-5]|2[0-4]\d|1?\d?\d)(?!\d)/g;
const JWT = /\beyJ[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}\b/g;
const API_KEY =
  /\b(?:sk-[A-Za-z0-9-]{16,}|ghp_[A-Za-z0-9]{20,}|gho_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16}|xox[baprs]-[A-Za-z0-9-]{10,})\b/g;
const BEARER = /\bBearer\s+[A-Za-z0-9._~+/-]{8,}=*/gi;

function luhnValid(candidate: string): boolean {
  const digits = candidate.replace(/[^\d]/g, '');
  if (digits.length < 13 || digits.length > 19) return false;
  let sum = 0;
  let double = false;
  for (let i = digits.length - 1; i >= 0; i--) {
    let d = digits.charCodeAt(i) - 48;
    if (double) {
      d *= 2;
      if (d > 9) d -= 9;
    }
    sum += d;
    double = !double;
  }
  return sum % 10 === 0;
}

/** Phone matcher is deliberately loose; require 7+ digits to cut false positives. */
function phonePlausible(candidate: string): boolean {
  const digits = candidate.replace(/[^\d]/g, '');
  return digits.length >= 7 && digits.length <= 15;
}

const BASE_DETECTORS: readonly ValueDetector[] = Object.freeze([
  { kind: 'api-key', pattern: API_KEY },
  { kind: 'bearer-token', pattern: BEARER },
  { kind: 'jwt', pattern: JWT },
  { kind: 'email', pattern: EMAIL },
  { kind: 'credit-card', pattern: CREDIT_CARD, verify: luhnValid },
  { kind: 'ssn', pattern: SSN },
  { kind: 'phone', pattern: PHONE, verify: phonePlausible },
]);

const MAX_DEPTH = 8;
const MAX_STRING_SCAN = 16384;

export class PiiMaskingEngine {
  readonly #options: MaskingOptions;
  readonly #detectors: readonly ValueDetector[];
  readonly #fieldMatchers: readonly RegExp[];
  readonly #allow: ReadonlySet<string>;
  #totalMasked = 0;

  constructor(options: Partial<MaskingOptions> = {}) {
    this.#options = { ...DEFAULT_MASKING_OPTIONS, ...options };
    const detectors: ValueDetector[] = [...BASE_DETECTORS];
    if (this.#options.maskIpAddresses) detectors.push({ kind: 'ipv4', pattern: IPV4 });
    for (const custom of this.#options.customPatterns) {
      const flags = custom.pattern.flags.includes('g')
        ? custom.pattern.flags
        : custom.pattern.flags + 'g';
      detectors.push({ kind: custom.name, pattern: new RegExp(custom.pattern.source, flags) });
    }
    this.#detectors = Object.freeze(detectors);
    // Fresh copies without g/y flags: .test() on a sticky/global regex
    // mutates lastIndex, which breaks on frozen config objects.
    this.#fieldMatchers = Object.freeze([
      SENSITIVE_FIELD_NAME,
      ...this.#options.customFieldMatchers.map(
        (m) => new RegExp(m.source, m.flags.replace(/[gy]/g, '')),
      ),
    ]);
    this.#allow = new Set(this.#options.allowList);
    Object.freeze(this);
  }

  /** Total masking actions performed by this engine (for health metrics). */
  get totalMasked(): number {
    return this.#totalMasked;
  }

  /**
   * Mask a mutable candidate record in place. Only `message`, `context`,
   * `metadata` and `error.message` are scanned — structural fields
   * (timestamp, level, ids) are contract-controlled and cannot contain
   * user-supplied strings.
   */
  maskRecord(candidate: {
    message: string;
    context: Record<string, unknown>;
    metadata: Record<string, unknown>;
    error?: { type: string; message: string; stacktrace?: string | undefined };
  }): MaskingResult {
    const maskedFields: string[] = [];
    if (!this.#options.enabled) return { maskedFields };

    const masked = this.#maskString(candidate.message, 'message', maskedFields);
    if (masked !== null) candidate.message = masked;

    this.#maskBag(candidate.context, 'context', maskedFields, 1);
    this.#maskBag(candidate.metadata, 'metadata', maskedFields, 1);

    if (candidate.error) {
      const errMasked = this.#maskString(candidate.error.message, 'error.message', maskedFields);
      if (errMasked !== null) candidate.error.message = errMasked;
    }

    this.#totalMasked += maskedFields.length;
    return { maskedFields };
  }

  #isAllowed(path: string): boolean {
    return this.#allow.has(path);
  }

  #fieldNameSensitive(key: string): boolean {
    return this.#fieldMatchers.some((matcher) => matcher.test(key));
  }

  #maskBag(
    bag: Record<string, unknown>,
    path: string,
    maskedFields: string[],
    depth: number,
  ): void {
    if (depth > MAX_DEPTH) return;
    for (const key of Object.keys(bag)) {
      const childPath = `${path}.${key}`;
      if (this.#isAllowed(childPath)) continue;
      const value = bag[key];
      if (this.#fieldNameSensitive(key)) {
        bag[key] = MASK('sensitive-field');
        maskedFields.push(childPath);
        continue;
      }
      if (typeof value === 'string') {
        const masked = this.#maskString(value, childPath, maskedFields);
        if (masked !== null) bag[key] = masked;
      } else if (Array.isArray(value)) {
        this.#maskArray(value, childPath, maskedFields, depth + 1);
      } else if (value !== null && typeof value === 'object') {
        this.#maskBag(value as Record<string, unknown>, childPath, maskedFields, depth + 1);
      }
    }
  }

  #maskArray(items: unknown[], path: string, maskedFields: string[], depth: number): void {
    if (depth > MAX_DEPTH) return;
    for (let i = 0; i < items.length; i++) {
      const childPath = `${path}[${i}]`;
      if (this.#isAllowed(childPath)) continue;
      const value = items[i];
      if (typeof value === 'string') {
        const masked = this.#maskString(value, childPath, maskedFields);
        if (masked !== null) items[i] = masked;
      } else if (Array.isArray(value)) {
        this.#maskArray(value, childPath, maskedFields, depth + 1);
      } else if (value !== null && typeof value === 'object') {
        this.#maskBag(value as Record<string, unknown>, childPath, maskedFields, depth + 1);
      }
    }
  }

  /** Returns the masked string, or null when nothing matched. */
  #maskString(value: string, path: string, maskedFields: string[]): string | null {
    if (value.length === 0 || value.length > MAX_STRING_SCAN) return null;
    let result = value;
    let touched = false;
    for (const detector of this.#detectors) {
      detector.pattern.lastIndex = 0;
      result = result.replace(detector.pattern, (match) => {
        if (detector.verify && !detector.verify(match)) return match;
        touched = true;
        return MASK(detector.kind);
      });
    }
    if (!touched) return null;
    maskedFields.push(path);
    return result;
  }
}
