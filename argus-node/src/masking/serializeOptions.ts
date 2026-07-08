/**
 * Masking options travel to worker threads via postMessage, which cannot
 * structured-clone RegExp lastIndex semantics safely across versions — so we
 * ship {source, flags} pairs and revive them on the worker side.
 */

import type { CustomPattern, MaskingOptions } from './engine';

export interface SerializedMaskingOptions {
  enabled: boolean;
  allowList: string[];
  customPatterns: { name: string; source: string; flags: string }[];
  customFieldMatchers: { source: string; flags: string }[];
  maskIpAddresses: boolean;
}

export function serializeMaskingOptions(options: MaskingOptions): SerializedMaskingOptions {
  return {
    enabled: options.enabled,
    allowList: [...options.allowList],
    customPatterns: options.customPatterns.map((p: CustomPattern) => ({
      name: p.name,
      source: p.pattern.source,
      flags: p.pattern.flags,
    })),
    customFieldMatchers: options.customFieldMatchers.map((m: RegExp) => ({
      source: m.source,
      flags: m.flags,
    })),
    maskIpAddresses: options.maskIpAddresses,
  };
}

export function reviveMaskingOptions(serialized: SerializedMaskingOptions): MaskingOptions {
  return {
    enabled: serialized.enabled,
    allowList: serialized.allowList,
    customPatterns: serialized.customPatterns.map((p) => ({
      name: p.name,
      pattern: new RegExp(p.source, p.flags),
    })),
    customFieldMatchers: serialized.customFieldMatchers.map((m) => new RegExp(m.source, m.flags)),
    maskIpAddresses: serialized.maskIpAddresses,
  };
}
