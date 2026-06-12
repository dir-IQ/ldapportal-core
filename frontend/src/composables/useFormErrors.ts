// SPDX-License-Identifier: Apache-2.0
import { reactive, ref, computed, nextTick } from 'vue'

/** One failing field, as shown in the {@link FormValidationSummary} banner. */
export interface FormErrorEntry {
  /** Field key — matches the `data-field` attribute on its control. */
  key: string
  /** Human-readable label for the summary. */
  label: string
  /** The validation message. */
  message: string
}

export interface UseFormErrorsOptions {
  /** Resolve a human label for a field key (defaults to the key itself). */
  labelFor?: (key: string) => string
}

/**
 * Shared form-validation error state + "take me to the problem" behaviour:
 *
 * - holds a reactive `errors` map (fieldKey → message),
 * - exposes `summary` for the top-of-form {@link FormValidationSummary} banner,
 * - on {@link report}, reveals the banner and moves the user to the first
 *   failing field: focuses the control carrying `data-field="<key>"`, scrolling
 *   it into view if off-screen.
 *
 * Controls opt in by setting `data-field="<key>"` (FormField does this via its
 * `field-key` prop; raw inputs bind it directly).
 */
export function useFormErrors(options: UseFormErrorsOptions = {}) {
  const errors = reactive<Record<string, string>>({})
  const showSummary = ref(false)

  const summary = computed<FormErrorEntry[]>(() =>
    Object.entries(errors).map(([key, message]) => ({
      key,
      message,
      label: options.labelFor?.(key) ?? key,
    })),
  )

  function clear(): void {
    for (const k of Object.keys(errors)) delete errors[k]
    showSummary.value = false
  }

  /** Replace the error set (clears first). */
  function setErrors(next: Record<string, string>): void {
    clear()
    Object.assign(errors, next)
  }

  /**
   * Reveal the summary (when there are errors) and jump to the first failing
   * field. Pass the form's root element to scope the field lookup (defaults to
   * the whole document). Returns true when the form has errors.
   */
  function report(rootEl?: HTMLElement | null): boolean {
    const hasErrors = Object.keys(errors).length > 0
    showSummary.value = hasErrors
    if (hasErrors) nextTick(() => focusFirstError(rootEl))
    return hasErrors
  }

  /** Focus + scroll the first failing field into view. */
  function focusFirstError(rootEl?: HTMLElement | null): void {
    const errored = new Set(Object.keys(errors))
    if (errored.size === 0) return
    const root: ParentNode = rootEl ?? document
    // querySelectorAll returns document order, so the first match is the
    // topmost failing field.
    let target: HTMLElement | null = null
    for (const el of root.querySelectorAll<HTMLElement>('[data-field]')) {
      if (errored.has(el.dataset.field ?? '')) { target = el; break }
    }
    if (!target) return
    target.scrollIntoView?.({ behavior: 'smooth', block: 'center' })
    // preventScroll: scrollIntoView above already positions it nicely.
    target.focus?.({ preventScroll: true })
  }

  return { errors, summary, showSummary, clear, setErrors, report, focusFirstError }
}
