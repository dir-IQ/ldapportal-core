// SPDX-License-Identifier: Apache-2.0
/**
 * Pure helpers for building RFC 4515 LDAP filter strings from a set of
 * declarative `FilterRule`s. All the value-escaping rules and operator
 * vocabulary live here so the visual builder component
 * (`LdapFilterBuilder.vue`) stays presentation-only.
 *
 * Why not parse here too? v1 is one-way (build → string). The reverse
 * (string → rules) requires a small but careful RFC 4515 parser; that's
 * deferred per the design spec because most filters are simple and the
 * raw-text input remains available as an escape hatch.
 *
 * See `docs/superpowers/specs/2026-04-26-ldap-filter-builder-design.md`.
 */

export type FilterOp =
  | 'equals'
  | 'not-equals'
  | 'starts-with'
  | 'ends-with'
  | 'contains'
  | 'present'
  | 'gte'
  | 'lte'
  | 'approx'
  | 'is-one-of'

export type Conjunction = 'AND' | 'OR'

export interface FilterRule {
  attr: string
  op: FilterOp
  /** For `is-one-of`: comma-separated. Ignored for `present`. */
  value: string
}

export interface OperatorMeta {
  value: FilterOp
  label: string
  /** When false, the value input is hidden in the UI. */
  hasValue: boolean
}

/** Single source of truth for the operator dropdown.
 *  Order is the order users see in the dropdown. */
export const OPERATORS: ReadonlyArray<OperatorMeta> = [
  { value: 'equals',      label: 'equals',         hasValue: true  },
  { value: 'not-equals',  label: 'not equals',     hasValue: true  },
  { value: 'starts-with', label: 'starts with',    hasValue: true  },
  { value: 'ends-with',   label: 'ends with',      hasValue: true  },
  { value: 'contains',    label: 'contains',       hasValue: true  },
  { value: 'present',     label: 'present',        hasValue: false },
  { value: 'gte',         label: 'greater than',   hasValue: true  },
  { value: 'lte',         label: 'less than',      hasValue: true  },
  { value: 'approx',      label: 'approx',         hasValue: true  },
  { value: 'is-one-of',   label: 'is one of',      hasValue: true  },
]

/**
 * Escape a value per RFC 4515 §3. We escape the five characters that have
 * special meaning in filter strings — `*`, `(`, `)`, `\`, NUL — using
 * the `\xx` two-digit hex form. Everything else passes through unchanged
 * so a well-formed filter is human-readable.
 */
export function escapeLdapValue(s: string): string {
  if (s == null) return ''
  let out = ''
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i)
    if (c === 0)  { out += '\\00'; continue }
    if (c === 0x28) { out += '\\28'; continue } // (
    if (c === 0x29) { out += '\\29'; continue } // )
    if (c === 0x2a) { out += '\\2a'; continue } // *
    if (c === 0x5c) { out += '\\5c'; continue } // backslash
    out += s[i]
  }
  return out
}

/**
 * Compile a single rule to a parenthesized filter expression. Returns the
 * empty string when the rule is incomplete (missing attr, or missing
 * value for an op that requires one) — callers can ignore empties so an
 * in-progress rule doesn't break the compiled string.
 */
export function compileRule(rule: FilterRule): string {
  const attr = rule.attr.trim()
  if (!attr) return ''

  switch (rule.op) {
    case 'present':
      return `(${attr}=*)`
    case 'is-one-of': {
      // Compile to `(|(attr=v1)(attr=v2)…)`. Empty list → empty filter.
      const values = rule.value.split(',').map(v => v.trim()).filter(v => v.length > 0)
      if (values.length === 0) return ''
      if (values.length === 1) return `(${attr}=${escapeLdapValue(values[0])})`
      const parts = values.map(v => `(${attr}=${escapeLdapValue(v)})`).join('')
      return `(|${parts})`
    }
    default: {
      const v = escapeLdapValue(rule.value)
      // Empty value is allowed for `equals` (matches the empty string) but
      // we still emit it so the user sees the structure they're building.
      switch (rule.op) {
        case 'equals':      return `(${attr}=${v})`
        case 'not-equals':  return `(!(${attr}=${v}))`
        case 'starts-with': return `(${attr}=${v}*)`
        case 'ends-with':   return `(${attr}=*${v})`
        case 'contains':    return `(${attr}=*${v}*)`
        case 'gte':         return `(${attr}>=${v})`
        case 'lte':         return `(${attr}<=${v})`
        case 'approx':      return `(${attr}~=${v})`
      }
    }
  }
  return ''
}

/**
 * Compile a list of rules into a single filter string. With one rule, no
 * outer wrapper is emitted — `(cn=Alice)` rather than `(&(cn=Alice))` —
 * since LDAP servers accept either but the unwrapped form is what users
 * recognise. Empty rule list → empty string (caller decides what to do
 * with that, e.g. fall back to `(objectClass=*)`).
 */
export function compileRules(rules: FilterRule[], conj: Conjunction): string {
  const compiled = rules.map(compileRule).filter(s => s.length > 0)
  if (compiled.length === 0) return ''
  if (compiled.length === 1) return compiled[0]
  const op = conj === 'OR' ? '|' : '&'
  return `(${op}${compiled.join('')})`
}

/** Returns true if the operator does not take a value input. */
export function operatorHasValue(op: FilterOp): boolean {
  return OPERATORS.find(o => o.value === op)?.hasValue ?? true
}
