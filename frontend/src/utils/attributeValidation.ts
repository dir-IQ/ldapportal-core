// SPDX-License-Identifier: Apache-2.0
/**
 * Client-side validation of LDAP attribute values against the rules an admin
 * configures on a provisioning profile (required / length / regex). The
 * server re-validates everything authoritatively (see
 * {@code ProvisioningProfileService.validateAttributes}); these helpers exist
 * purely for instant, field-level feedback in the data-entry forms.
 *
 * The shape is normalized across the two field sources in the app — the admin
 * profile-attribute config and the self-service registration field DTO — so a
 * single implementation backs both the admin forms and the self-service forms.
 */

/** Normalized validation rules for a single attribute. */
export interface AttributeRules {
  /** LDAP attribute name (used as the error-map key). */
  attributeName: string
  /** Human-readable label for "required" messages; falls back to attributeName. */
  label?: string | null
  /** Whether a (non-blank) value must be present. */
  required?: boolean | null
  minLength?: number | null
  maxLength?: number | null
  /** A Java/JS-compatible regular expression source string. */
  validationRegex?: string | null
  /** Custom message shown when the regex does not match. */
  validationMessage?: string | null
  /**
   * The form input type (e.g. {@code DN_LOOKUP}, {@code BOOLEAN}). When set, an
   * intrinsic {@link SyntaxKind} is derived from it (mirroring the server's
   * {@code AttributeSyntax.forInputType}) unless {@link syntaxKind} is given.
   */
  inputType?: string | null
  /**
   * Explicit intrinsic value shape to enforce. Overrides the {@link inputType}
   * derivation — admin forms set this from the {@code /attribute-syntax} hints so
   * well-known bare attributes (e.g. {@code mail}, {@code manager}) are also
   * shape-checked.
   */
  syntaxKind?: SyntaxKind | null
}

/** Intrinsic value shape an attribute is expected to hold (mirrors the server). */
export type SyntaxKind = 'DN' | 'EMAIL' | 'BOOLEAN'

/**
 * The built-in attribute-syntax hints served by {@code GET /api/v1/attribute-syntax}
 * (workstream B). Both maps carry {@link SyntaxKind} names as values.
 */
export interface AttributeSyntaxHints {
  /** lower-case attribute name → kind, for bare / unprofiled attributes. */
  wellKnownAttributes: Record<string, string>
  /** {@code InputType} name → kind, for profile-configured input types. */
  inputTypeSyntax: Record<string, string>
}

/** Deliberately permissive email shape — mirrors the server's check. */
const EMAIL_RE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/

/** @returns true when {@code value} looks like a valid email address. */
export function validateEmail(value: string): boolean {
  return EMAIL_RE.test(value)
}

/** @returns true when {@code value} is {@code TRUE}/{@code FALSE} (case-insensitive). */
export function validateBoolean(value: string): boolean {
  return /^(TRUE|FALSE)$/i.test(value)
}

/**
 * Lightweight, RFC-4514-ish DN check for instant client feedback only — the
 * server's UnboundID parser is authoritative. Accepts one or more
 * comma-separated RDNs (commas may be backslash-escaped inside a value), each a
 * {@code +}-joined set of {@code attr=value} pairs where {@code attr} is a
 * descriptor (letter-led) or a numeric OID. Kept permissive so it never blocks a
 * value the directory would actually accept.
 */
export function validateDn(value: string): boolean {
  const s = value.trim()
  if (!s || s.startsWith(',') || s.endsWith(',')) return false
  // Neutralise escaped pairs (\X — e.g. an escaped comma or plus inside a
  // value) before splitting, so they aren't treated as RDN/AVA separators.
  // We avoid regex look-behind deliberately: it isn't parseable by Safari
  // < 16.4 (within this project's Vite browser baseline), and a look-behind
  // literal would throw a SyntaxError that breaks this whole module on load.
  const masked = s.replace(/\\./g, '\u0000')
  const attrType = '(?:[A-Za-z][\\w-]*|\\d+(?:\\.\\d+)+)'
  const pair = new RegExp(`^\\s*${attrType}\\s*=\\s*.+$`)
  return masked.split(',').every((rdn) =>
    rdn.split('+').every((av) => pair.test(av)),
  )
}

/**
 * Derive the intrinsic {@link SyntaxKind} for a field, mirroring the server's
 * {@code LdapAttributeValidator.resolveKind}: the input type is authoritative
 * (a {@code DN_LOOKUP} field is a DN, a {@code BOOLEAN} field is a boolean),
 * otherwise the well-known attribute map decides. {@code hints} may be omitted
 * (e.g. self-service, which can't read the admin endpoint) — the built-in
 * input-type fallback still covers {@code DN_LOOKUP}/{@code BOOLEAN}.
 */
export function resolveSyntaxKind(
  inputType: string | null | undefined,
  attributeName: string,
  hints?: AttributeSyntaxHints | null,
): SyntaxKind | null {
  const fromInput = (inputType && hints?.inputTypeSyntax?.[inputType])
    || kindForInputType(inputType)
  if (fromInput) return fromInput as SyntaxKind
  const wk = hints?.wellKnownAttributes?.[attributeName.toLowerCase()]
  return (wk as SyntaxKind) || null
}

/** Built-in input-type → kind fallback (mirrors {@code AttributeSyntax.forInputType}). */
function kindForInputType(inputType: string | null | undefined): SyntaxKind | null {
  // DN_LOOKUP (picker) and DN (typed directly) both hold a DN.
  if (inputType === 'DN_LOOKUP' || inputType === 'DN') return 'DN'
  if (inputType === 'BOOLEAN') return 'BOOLEAN'
  return null
}

/** @returns an error message for a syntax violation, or null when valid. */
function validateSyntax(kind: SyntaxKind, value: string): string | null {
  switch (kind) {
    case 'DN': return validateDn(value) ? null : 'Not a valid DN'
    case 'EMAIL': return validateEmail(value) ? null : 'Not a valid email address'
    case 'BOOLEAN': return validateBoolean(value) ? null : 'Must be TRUE or FALSE'
  }
}

/**
 * Coerce a form value to the string the rules apply to. Booleans map to the
 * LDAP-style {@code TRUE}/{@code FALSE} tokens (matching the server and the
 * self-service submit path); null/undefined become an empty string.
 */
function toStringValue(value: unknown): string {
  if (typeof value === 'boolean') return value ? 'TRUE' : 'FALSE'
  if (value == null) return ''
  return String(value)
}

/**
 * Validate a single value against its rules.
 *
 * @returns an error message, or {@code null} when the value is valid.
 */
export function validateAttributeValue(rules: AttributeRules, value: unknown): string | null {
  const strVal = toStringValue(value)

  if (rules.required && !strVal) {
    return `${rules.label || rules.attributeName} is required`
  }
  // Empty + not required → nothing else to check.
  if (!strVal) return null

  if (rules.minLength != null && strVal.length < rules.minLength) {
    return `Must be at least ${rules.minLength} characters`
  }
  if (rules.maxLength != null && strVal.length > rules.maxLength) {
    return `Must be at most ${rules.maxLength} characters`
  }

  if (rules.validationRegex) {
    let re: RegExp | null = null
    try {
      re = new RegExp(rules.validationRegex)
    } catch {
      // A malformed stored pattern must not crash the form; the server
      // rejects the value defensively. Skip the client-side check.
      re = null
    }
    if (re && !re.test(strVal)) {
      return rules.validationMessage || 'Invalid format'
    }
  }

  // Intrinsic syntax (DN / email / boolean), mirroring the server's syntax
  // layer. Explicit syntaxKind wins; otherwise derive from the input type.
  const kind = rules.syntaxKind || kindForInputType(rules.inputType)
  if (kind) {
    const syntaxErr = validateSyntax(kind, strVal)
    if (syntaxErr) return syntaxErr
  }

  return null
}

/**
 * Validate a set of fields, reading each value through {@code getValue}.
 *
 * @returns a map of {@code attributeName → error message} containing only the
 *          fields that failed; an empty object means the form is valid.
 */
export function validateAttributes(
  rulesList: AttributeRules[],
  getValue: (attributeName: string) => unknown,
): Record<string, string> {
  const errors: Record<string, string> = {}
  for (const rules of rulesList) {
    const err = validateAttributeValue(rules, getValue(rules.attributeName))
    if (err) errors[rules.attributeName] = err
  }
  return errors
}
