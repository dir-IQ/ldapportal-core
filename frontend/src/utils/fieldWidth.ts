// SPDX-License-Identifier: Apache-2.0
/**
 * Field-width rules shared by the form-layout designer and the rendered forms,
 * so the width an admin picks in the designer is the width the operator sees.
 *
 * Forms lay fields out on a 6-column grid; a field's `columnSpan` is how many
 * of those columns it takes.
 */

export interface SpanOption {
  span: number
  label: string
}

/** Every width the designer can offer: 1/3, 1/2, 2/3 and full. */
export const SPAN_OPTIONS: readonly SpanOption[] = [
  { span: 2, label: '1/3' },
  { span: 3, label: '1/2' },
  { span: 4, label: '2/3' },
  { span: 6, label: 'Full' },
]

/**
 * Input types whose widget carries inline buttons (password: generate / copy;
 * DN lookup: browse), so it stops being usable below half width. These are
 * limited to 1/2 or Full. Every other type — multi-line text included — takes
 * whatever width the admin picks.
 */
const HALF_OR_FULL_INPUT_TYPES = new Set(['PASSWORD', 'DN_LOOKUP'])

/** The widths the designer offers for a field of this input type. */
export function spanOptionsFor(inputType?: string | null): readonly SpanOption[] {
  if (inputType && HALF_OR_FULL_INPUT_TYPES.has(inputType)) {
    return SPAN_OPTIONS.filter(o => o.span === 3 || o.span === 6)
  }
  return SPAN_OPTIONS
}

/**
 * The width a field actually renders at: the saved span, falling back to
 * `fallback` when unset, then snapped to what the input type allows (a
 * half-or-full type renders anything short of full as 1/2).
 */
export function effectiveSpan(
  inputType: string | null | undefined,
  columnSpan: number | null | undefined,
  fallback: number,
): number {
  const span = columnSpan || fallback
  if (inputType && HALF_OR_FULL_INPUT_TYPES.has(inputType)) return span >= 6 ? 6 : 3
  return span
}
