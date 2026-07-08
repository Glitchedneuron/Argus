/**
 * Deep-freeze utilities.
 *
 * Every validated log record is deep-frozen before it leaves the contract
 * layer, so no hook, transport, or application code can mutate it post-hoc.
 */

/** Recursively freeze an object graph in place and return it. */
export function deepFreeze<T>(value: T): Readonly<T> {
  if (value === null || typeof value !== 'object') return value;
  const seen = new Set<object>();
  const stack: object[] = [value as object];
  while (stack.length > 0) {
    const current = stack.pop() as object;
    if (seen.has(current)) continue;
    seen.add(current);
    Object.freeze(current);
    for (const key of Object.getOwnPropertyNames(current)) {
      const child = (current as Record<string, unknown>)[key];
      if (child !== null && typeof child === 'object' && !Object.isFrozen(child)) {
        stack.push(child);
      }
    }
  }
  return value;
}

/** True when the whole object graph is frozen. */
export function isDeepFrozen(value: unknown): boolean {
  if (value === null || typeof value !== 'object') return true;
  if (!Object.isFrozen(value)) return false;
  return Object.getOwnPropertyNames(value).every((key) =>
    isDeepFrozen((value as Record<string, unknown>)[key]),
  );
}
