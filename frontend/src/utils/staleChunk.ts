// SPDX-License-Identifier: Apache-2.0
/**
 * Recovery from a stale bundle. Every build renames its lazy-loaded chunks
 * (content hashes), so a browser still holding the previous build's
 * index.html asks for chunks that no longer exist. The import rejects, the
 * router aborts the navigation, and the user is left stranded on whatever
 * page they were on (e.g. /login after a successful sign-in). Reloading the
 * target URL fetches the current index.html and its chunks.
 */
/** sessionStorage key holding the time of the last recovery reload. */
export const RELOAD_KEY = 'ldapportal-chunk-reload'

/**
 * A second failure inside this window means the reload didn't help (the
 * chunk is genuinely missing on the server), so stop instead of looping.
 */
export const RELOAD_WINDOW_MS = 10_000

// Chrome/Edge, Firefox, Safari, and the strict-MIME rejection when the
// server answers a missing chunk with index.html.
const CHUNK_ERROR = new RegExp([
  'Failed to fetch dynamically imported module',
  'error loading dynamically imported module',
  'Importing a module script failed',
  'Unable to preload CSS',
  'is not a valid JavaScript MIME type',
].join('|'), 'i')

/** Whether `err` is a failed dynamic import of a build chunk. */
export function isChunkLoadError(err: unknown): boolean {
  if (!err || typeof err !== 'object') return false
  const { name, message } = err as { name?: unknown, message?: unknown }
  return name === 'ChunkLoadError' || (typeof message === 'string' && CHUNK_ERROR.test(message))
}

interface ReloadDeps {
  storage?: Pick<Storage, 'getItem' | 'setItem'> | null
  navigate?: (url: string) => void
  now?: () => number
}

/**
 * Hard-navigate to `href` (the full in-app URL, base path included) so the
 * browser loads the current build. Returns false, without navigating, when a
 * recovery reload already happened within {@link RELOAD_WINDOW_MS}.
 */
export function reloadForStaleChunk(href: string, deps: ReloadDeps = {}): boolean {
  const storage = deps.storage !== undefined ? deps.storage : safeSessionStorage()
  const navigate = deps.navigate ?? ((url: string) => window.location.assign(url))
  const now = (deps.now ?? Date.now)()
  try {
    const last = Number(storage?.getItem(RELOAD_KEY))
    if (last && now - last < RELOAD_WINDOW_MS) return false
    storage?.setItem(RELOAD_KEY, String(now))
  } catch { /* storage blocked: reload anyway; a loop needs storage to detect */ }
  navigate(href)
  return true
}

function safeSessionStorage(): Storage | null {
  try { return window.sessionStorage } catch { return null }
}
