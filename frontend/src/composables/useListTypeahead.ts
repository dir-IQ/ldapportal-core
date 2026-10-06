// SPDX-License-Identifier: Apache-2.0
import { getCurrentInstance, onBeforeUnmount, ref, type Ref } from 'vue'

/**
 * Type-to-find for a focusable list (a `role="listbox"` element). Printable
 * keys accumulate into a short buffer that clears after a pause; each key
 * jumps to the first item starting with the buffer, case-insensitively —
 * like a native `<select>`.
 *
 * Usage: call {@link handleKey} from the list's `keydown` handler. It returns
 * the matched item, `null` when the buffer matches nothing, or `undefined`
 * when the key is not a type-to-find key (so the caller can handle arrows,
 * Enter, etc.).
 */
export interface ListTypeahead {
  /** Letters typed so far; '' when idle. Bind it to show what's being matched. */
  typed: Ref<string>
  handleKey: (e: KeyboardEvent, items: string[]) => string | null | undefined
  reset: () => void
}

export function useListTypeahead(timeoutMs = 1000): ListTypeahead {
  const typed = ref('')
  let timer: ReturnType<typeof setTimeout> | null = null

  function reset() {
    if (timer) clearTimeout(timer)
    timer = null
    typed.value = ''
  }

  function handleKey(e: KeyboardEvent, items: string[]): string | null | undefined {
    if (e.key === 'Escape' && typed.value) { e.preventDefault(); reset(); return undefined }
    if (e.key.length !== 1 || e.ctrlKey || e.metaKey || e.altKey) return undefined
    // Space starts nothing on its own (it usually activates), but may continue a buffer.
    if (e.key === ' ' && !typed.value) return undefined
    e.preventDefault()
    typed.value += e.key.toLowerCase()
    if (timer) clearTimeout(timer)
    timer = setTimeout(reset, timeoutMs)
    const p = typed.value
    return items.find(i => i.toLowerCase().startsWith(p)) ?? null
  }

  if (getCurrentInstance()) onBeforeUnmount(() => { if (timer) clearTimeout(timer) })
  return { typed, handleKey, reset }
}
