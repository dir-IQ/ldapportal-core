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
