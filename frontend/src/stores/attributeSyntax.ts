// SPDX-License-Identifier: Apache-2.0
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getAttributeSyntaxHints } from '@/api/attributeSyntax'
import {
  resolveSyntaxKind,
  type AttributeSyntaxHints,
  type SyntaxKind,
} from '@/utils/attributeValidation'

/**
 * Caches the built-in attribute-syntax hints from
 * {@code GET /api/v1/attribute-syntax} so the admin create/edit forms can mirror
 * the server's DN / email / boolean checks from a single source of truth. The
 * payload is small, static, and directory-agnostic, so one fetch per session is
 * plenty. Fetch failures degrade gracefully to input-type-only resolution — the
 * server stays authoritative either way.
 */
export const useAttributeSyntaxStore = defineStore('attributeSyntax', () => {
  const hints = ref<AttributeSyntaxHints | null>(null)
  let inflight: Promise<void> | null = null

  async function load(): Promise<void> {
    try {
      const { data } = await getAttributeSyntaxHints()
      hints.value = data
    } catch {
      // Best-effort: leave hints null; resolveKind falls back to input type.
      hints.value = { wellKnownAttributes: {}, inputTypeSyntax: {} }
    }
  }

  /** Fetches the hints once; concurrent callers share the in-flight request. */
  async function ensureLoaded(): Promise<void> {
    if (hints.value) return
    if (!inflight) inflight = load().finally(() => { inflight = null })
    await inflight
  }

  /** Resolve a field's intrinsic syntax kind against the cached hints. */
  function kindFor(inputType: string | null | undefined, attributeName: string): SyntaxKind | null {
    return resolveSyntaxKind(inputType, attributeName, hints.value)
  }

  return { hints, ensureLoaded, kindFor }
})
