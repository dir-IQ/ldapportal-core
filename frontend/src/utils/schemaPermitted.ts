// SPDX-License-Identifier: Apache-2.0
/**
 * Helpers for validating profile attribute configs against the live directory
 * schema (the per-objectClass required/optional sets the profile editor
 * caches). Pure functions — kept out of the SFC so they're unit-testable.
 */

export interface OcSchemaEntry {
  required: string[]
  optional: string[]
}

/**
 * Union of permitted attribute names (lower-cased) across the selected
 * objectClasses, from the editor's per-OC schema cache. Returns null when
 * none of the selected classes have schema loaded (lookup failed or pending)
 * — callers must treat null as "schema unknown, skip validation" rather than
 * "nothing permitted".
 */
export function permittedAttrSet(
  cache: Record<string, OcSchemaEntry>,
  objectClassNames: string[],
): Set<string> | null {
  let any = false
  const out = new Set<string>()
  for (const oc of objectClassNames) {
    const entry = cache[oc]
    if (!entry) continue
    any = true
    for (const a of entry.required) out.add(a.toLowerCase())
    for (const a of entry.optional) out.add(a.toLowerCase())
  }
  return any ? out : null
}

/**
 * The subset of attribute names not permitted by the schema. Empty when the
 * permitted set is null (schema unknown). Case-insensitive; preserves the
 * input casing in the result for display.
 */
export function unsupportedAttrs(
  names: string[],
  permitted: Set<string> | null,
): string[] {
  if (!permitted) return []
  return names.filter(n => !permitted.has(n.toLowerCase()))
}
