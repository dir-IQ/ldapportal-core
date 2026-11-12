// SPDX-License-Identifier: Apache-2.0
// Highlighting for a content drift's expected (source) and actual (target)
// values: which values only one side has, and, for a single changed value,
// which characters differ.
import type { SyncDriftAttribute } from '@/api/sync'

export interface Segment {
  text: string
  /** Differs from the other side's value. */
  changed: boolean
}

export interface ShownValue {
  value: string
  /** Only this side has the value (the other side lacks it). */
  only: boolean
  /** Character-level segments when one value replaced another; null otherwise. */
  segments: Segment[] | null
}

/** Longest value compared character by character; longer ones highlight whole. */
export const CHAR_DIFF_MAX = 2000
/** Largest middle section (after common prefix and suffix) the character diff works on. */
const LCS_MAX_CELLS = 250_000
/** An unchanged run shorter than this between two changes is folded into them. */
const MIN_EQUAL_RUN = 3

const BINARY = /^\(binary, \d+ bytes\)$/

/**
 * Indexes of the values only this side has. Uses the flags the verify
 * recorded (compared the way the sync compares them); items stored before
 * those existed fall back to exact text comparison.
 */
export function onlyIndexes(attr: SyncDriftAttribute, side: 'expected' | 'actual'): Set<number> {
  const recorded = side === 'expected' ? attr.expectedOnly : attr.actualOnly
  if (recorded) return new Set(recorded)
  const mine = side === 'expected' ? attr.expected : attr.actual
  const other = new Set(side === 'expected' ? attr.actual : attr.expected)
  const out = new Set<number>()
  mine.forEach((v, i) => { if (!other.has(v)) out.add(i) })
  return out
}

/**
 * The side's values, each flagged when only this side has it. When exactly one
 * value on each side differs (a single-valued attribute that changed), both
 * also get character-level segments.
 */
export function shownValues(attr: SyncDriftAttribute, side: 'expected' | 'actual'): ShownValue[] {
  const mine = side === 'expected' ? attr.expected : attr.actual
  const other = side === 'expected' ? attr.actual : attr.expected
  const myOnly = onlyIndexes(attr, side)
  const otherOnly = onlyIndexes(attr, side === 'expected' ? 'actual' : 'expected')
  let segments: Segment[] | null = null
  if (myOnly.size === 1 && otherOnly.size === 1 && !attr.truncated) {
    const a = mine[[...myOnly][0]]
    const b = other[[...otherOnly][0]]
    const d = a !== undefined && b !== undefined
      ? (side === 'expected' ? charDiff(a, b)?.expected : charDiff(b, a)?.actual)
      : undefined
    segments = d ?? null
  }
  return mine.map((value, i) => ({
    value,
    only: myOnly.has(i),
    segments: myOnly.has(i) ? segments : null,
  }))
}

type Op = { kind: 'eq' | 'del' | 'ins'; text: string }

/**
 * Character-level difference between an expected and an actual value, as each
 * side's segments; null when the values are too long, binary, or identical.
 */
export function charDiff(expected: string, actual: string): { expected: Segment[]; actual: Segment[] } | null {
  if (expected === actual) return null
  if (expected.length > CHAR_DIFF_MAX || actual.length > CHAR_DIFF_MAX) return null
  if (BINARY.test(expected) || BINARY.test(actual)) return null
  const ops = cleanup(diffOps(expected, actual))
  const exp: Segment[] = []
  const act: Segment[] = []
  for (const op of ops) {
    if (op.kind !== 'ins') push(exp, op.text, op.kind === 'del')
    if (op.kind !== 'del') push(act, op.text, op.kind === 'ins')
  }
  return { expected: exp, actual: act }
}

/** Ops turning expected (del) into actual (ins), with common prefix and suffix kept whole. */
function diffOps(a: string, b: string): Op[] {
  let pre = 0
  while (pre < a.length && pre < b.length && a[pre] === b[pre]) pre++
  let suf = 0
  while (suf < a.length - pre && suf < b.length - pre && a[a.length - 1 - suf] === b[b.length - 1 - suf]) suf++
  const am = a.slice(pre, a.length - suf)
  const bm = b.slice(pre, b.length - suf)
  const ops: Op[] = []
  if (pre) ops.push({ kind: 'eq', text: a.slice(0, pre) })
  if (am.length * bm.length > LCS_MAX_CELLS) {
    if (am) ops.push({ kind: 'del', text: am })
    if (bm) ops.push({ kind: 'ins', text: bm })
  } else {
    ops.push(...lcsOps(am, bm))
  }
  if (suf) ops.push({ kind: 'eq', text: a.slice(a.length - suf) })
  return merge(ops)
}

function lcsOps(a: string, b: string): Op[] {
  const n = a.length
  const m = b.length
  // len[i][j]: LCS length of a[i:] and b[j:]
  const len: Uint32Array[] = Array.from({ length: n + 1 }, () => new Uint32Array(m + 1))
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      len[i][j] = a[i] === b[j] ? len[i + 1][j + 1] + 1 : Math.max(len[i + 1][j], len[i][j + 1])
    }
  }
  const ops: Op[] = []
  let i = 0
  let j = 0
  while (i < n && j < m) {
    if (a[i] === b[j]) { ops.push({ kind: 'eq', text: a[i] }); i++; j++ }
    else if (len[i + 1][j] >= len[i][j + 1]) { ops.push({ kind: 'del', text: a[i] }); i++ }
    else { ops.push({ kind: 'ins', text: b[j] }); j++ }
  }
  if (i < n) ops.push({ kind: 'del', text: a.slice(i) })
  if (j < m) ops.push({ kind: 'ins', text: b.slice(j) })
  return ops
}

/** Joins adjacent ops of a kind, keeping each run of changes as its deletion then its insertion. */
function merge(ops: Op[]): Op[] {
  const out: Op[] = []
  let del = ''
  let ins = ''
  const flush = () => {
    if (del) out.push({ kind: 'del', text: del })
    if (ins) out.push({ kind: 'ins', text: ins })
    del = ''
    ins = ''
  }
  for (const op of ops) {
    if (op.kind === 'del') del += op.text
    else if (op.kind === 'ins') ins += op.text
    else {
      flush()
      const last = out[out.length - 1]
      if (last?.kind === 'eq') last.text += op.text
      else out.push({ ...op })
    }
  }
  flush()
  return out
}

/**
 * Folds a short unchanged run sitting between two changes into them, so
 * "Engineer" → "Lead" reads as one change rather than scattered letters.
 */
function cleanup(ops: Op[]): Op[] {
  const out = ops.map(op => ({ ...op }))
  for (let k = 1; k < out.length - 1; k++) {
    const op = out[k]
    if (op.kind === 'eq' && op.text.length < MIN_EQUAL_RUN && out[k - 1].kind !== 'eq' && out[k + 1].kind !== 'eq') {
      out.splice(k, 1, { kind: 'del', text: op.text }, { kind: 'ins', text: op.text })
      k++
    }
  }
  return merge(out)
}

function push(segs: Segment[], text: string, changed: boolean) {
  const last = segs[segs.length - 1]
  if (last && last.changed === changed) last.text += text
  else segs.push({ text, changed })
}

/** A changed segment that is only whitespace, drawn so it can be seen. */
export function visibleWhitespace(text: string): string {
  if (/\S/.test(text)) return text
  return text.replace(/ /g, '·').replace(/\t/g, '→').replace(/\r?\n/g, '⏎')
}
