#!/usr/bin/env node
/**
 * Utility-class compliance checker for .vue files.
 *
 * Catches the three failure modes that bypass compact density mode:
 *
 *   A. <input>/<select>/<textarea> hand-rolled with border + rounded +
 *      padding utilities, missing the .input class.
 *   B. <button> hand-rolled with a bg-* fill + rounded + padding,
 *      missing any .btn-* class.
 *   C. h-[Npx] arbitrary-value heights on elements that DO use a
 *      .btn-* or .input class (the pixel-fixed height blocks compact
 *      mode's padding-driven shrinkage).
 *
 * Exit codes:
 *   0 — no violations
 *   1 — violations found (printed with file:line)
 *
 * Usage:
 *   node scripts/check-utility-classes.mjs
 *   node scripts/check-utility-classes.mjs --quiet      # only summary on success
 *   node scripts/check-utility-classes.mjs --json       # machine-readable
 *
 * Run from the `frontend/` directory.
 *
 * Why a Node.js script and not ESLint: the project has no ESLint
 * setup. Adding ESLint just for these three rules is a much larger
 * PR. This script is purpose-built and wired into CI directly.
 *
 * Suppression: append `// utility-class-lint:disable` on the offending
 * line for genuine intentional cases (checkbox/radio inputs, color
 * swatches, bespoke chip controls). Add a comment explaining why.
 */

import { readdir, readFile } from 'node:fs/promises'
import { join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'
import { dirname } from 'node:path'

const __dirname = dirname(fileURLToPath(import.meta.url))
const FRONTEND_ROOT = join(__dirname, '..')
const SRC_ROOT = join(FRONTEND_ROOT, 'src')

// Class-name patterns ─────────────────────────────────────────────────────
const HAS_INPUT_CLASS    = /\b(input|input-sm)\b/
const HAS_BTN_CLASS      = /\bbtn-(primary|secondary|neutral|danger|warning|success|danger-soft|success-soft|sm|tab|tab-active)\b/
const HAS_BORDER         = /\bborder(-gray-\d+)?\b/
const HAS_ROUNDED        = /\brounded(-\w+)?\b/
const HAS_PADDING_X      = /\bpx-\d+(\.\d+)?\b/
const HAS_PADDING_Y      = /\bpy-\d+(\.\d+)?\b/
const HAS_BG_FILL        = /\bbg-(blue|red|green|amber|orange|yellow|purple|pink|gray|slate|zinc)-\d+\b/
const HAS_FIXED_HEIGHT   = /\bh-\[\d+px\]/
const SUPPRESS_MARKER    = /utility-class-lint:disable/

// Element matchers — capture opening tag with optional attributes ─────────
//
// These are deliberately loose — we don't need a real HTML parser, just
// a way to slice each opening tag out of the template so we can inspect
// its class attribute. Multi-line opening tags are common in this repo
// (especially for inputs with v-model + autocomplete + class) so the
// pattern allows newlines inside the tag.
const TAG_RX = {
  input:    /<input\b[^>]*?>/gms,
  select:   /<select\b[^>]*?>/gms,
  textarea: /<textarea\b[^>]*?>/gms,
  button:   /<button\b[^>]*?>/gms,
}

// Form-control input types that are intentionally NOT styled with .input
// (the framework's defaults govern these — no class swap warranted).
const SKIP_INPUT_TYPES = ['checkbox', 'radio', 'color', 'hidden', 'file', 'range']

const TYPE_ATTR_RX = /\btype=["']([^"']+)["']/

/**
 * Pull both the static `class="..."` and the dynamic `:class="..."`
 * attribute strings off an opening tag. The dynamic class is harder to
 * analyze (could be an array, object, or expression) so we sample it
 * coarsely — if it CONTAINS recognisable utility tokens we treat them
 * as effective on the element. False-positives are possible but rare
 * in practice; the alternative (ignoring :class entirely) misses the
 * tab-button pattern which is :class-only.
 */
function extractClasses(tag) {
  const staticMatch  = /\bclass=["']([^"']*)["']/.exec(tag)
  const dynamicMatch = /:class=["']([^"']*)["']/.exec(tag)
  const staticStr    = staticMatch  ? staticMatch[1]  : ''
  const dynamicStr   = dynamicMatch ? dynamicMatch[1] : ''
  return `${staticStr} ${dynamicStr}`
}

function inputType(tag) {
  const m = TYPE_ATTR_RX.exec(tag)
  return m ? m[1] : 'text'
}

function lineForOffset(source, offset) {
  let line = 1
  for (let i = 0; i < offset && i < source.length; i++) {
    if (source[i] === '\n') line++
  }
  return line
}

/**
 * Walk a directory recursively, returning all *.vue file paths under it.
 */
async function findVueFiles(dir) {
  const out = []
  async function walk(d) {
    let entries
    try { entries = await readdir(d, { withFileTypes: true }) }
    catch { return }
    for (const e of entries) {
      const full = join(d, e.name)
      if (e.isDirectory()) {
        if (e.name === 'node_modules' || e.name === 'dist') continue
        await walk(full)
      } else if (e.name.endsWith('.vue')) {
        out.push(full)
      }
    }
  }
  await walk(dir)
  return out
}

const violations = []

function record(file, line, rule, message, snippet) {
  violations.push({
    file: relative(FRONTEND_ROOT, file).replaceAll('\\', '/'),
    line,
    rule,
    message,
    snippet: snippet.replace(/\s+/g, ' ').trim().slice(0, 120),
  })
}

function checkFormControl(tag, file, source, tagName) {
  if (SUPPRESS_MARKER.test(tag)) return

  // Skip structural <input> types that don't get .input.
  if (tagName === 'input') {
    const t = inputType(tag)
    if (SKIP_INPUT_TYPES.includes(t)) return
  }

  const classes = extractClasses(tag)
  if (HAS_INPUT_CLASS.test(classes)) return

  if (HAS_BORDER.test(classes)
      && HAS_ROUNDED.test(classes)
      && HAS_PADDING_X.test(classes)
      && HAS_PADDING_Y.test(classes)) {
    const offset = source.indexOf(tag)
    const line   = lineForOffset(source, offset)
    record(
      file, line,
      'no-hand-rolled-form-control',
      `<${tagName}> uses border + rounded + padding utilities but no .input class — bypasses compact density mode. Use class="input" (or "input-sm") and let main.css govern the styling.`,
      tag,
    )
  }
}

function checkButton(tag, file, source) {
  if (SUPPRESS_MARKER.test(tag)) return

  const classes = extractClasses(tag)
  if (HAS_BTN_CLASS.test(classes)) return

  if (HAS_BG_FILL.test(classes)
      && HAS_ROUNDED.test(classes)
      && HAS_PADDING_X.test(classes)
      && HAS_PADDING_Y.test(classes)) {
    const offset = source.indexOf(tag)
    const line   = lineForOffset(source, offset)
    record(
      file, line,
      'no-hand-rolled-button',
      '<button> uses bg-* + rounded + padding utilities but no .btn-* class — bypasses compact density mode. See docs/frontend-conventions.md for the available utilities.',
      tag,
    )
  }
}

function checkFixedHeight(tag, file, source, tagName) {
  if (SUPPRESS_MARKER.test(tag)) return

  const classes = extractClasses(tag)
  if (!HAS_FIXED_HEIGHT.test(classes)) return
  if (!HAS_BTN_CLASS.test(classes) && !HAS_INPUT_CLASS.test(classes)) return

  const offset = source.indexOf(tag)
  const line   = lineForOffset(source, offset)
  record(
    file, line,
    'no-fixed-height-on-utility',
    `<${tagName}> has h-[Npx] arbitrary-value height on a .btn-* or .input class — blocks compact density mode's padding-driven shrinkage. Drop the height override and let padding govern size, or wrap the element with an invisible label spacer to align with neighbouring controls (see SodPoliciesView for the pattern).`,
    tag,
  )
}

async function checkFile(file) {
  const source = await readFile(file, 'utf8')

  for (const [tagName, rx] of Object.entries(TAG_RX)) {
    rx.lastIndex = 0
    let m
    while ((m = rx.exec(source)) !== null) {
      const tag = m[0]
      if (tagName === 'button') {
        checkButton(tag, file, source)
        checkFixedHeight(tag, file, source, tagName)
      } else {
        checkFormControl(tag, file, source, tagName)
        checkFixedHeight(tag, file, source, tagName)
      }
    }
  }
}

// Main ────────────────────────────────────────────────────────────────────
const argv = process.argv.slice(2)
const QUIET = argv.includes('--quiet')
const JSON_OUTPUT = argv.includes('--json')

const files = await findVueFiles(SRC_ROOT)
for (const f of files) {
  await checkFile(f)
}

if (JSON_OUTPUT) {
  console.log(JSON.stringify({ violationCount: violations.length, violations }, null, 2))
} else if (violations.length === 0) {
  if (!QUIET) {
    console.log(`✓ utility-class-lint clean (${files.length} .vue files checked)`)
  }
} else {
  console.log(`✗ utility-class-lint: ${violations.length} violation${violations.length === 1 ? '' : 's'} in ${files.length} files`)
  console.log('')
  for (const v of violations) {
    console.log(`${v.file}:${v.line}`)
    console.log(`  [${v.rule}] ${v.message}`)
    console.log(`  > ${v.snippet}`)
    console.log('')
  }
  console.log('To fix: replace hand-rolled classes with the utility classes')
  console.log('described in docs/frontend-conventions.md, or append')
  console.log('// utility-class-lint:disable on the offending tag with a')
  console.log('comment explaining why the case is genuinely intentional.')
}

process.exit(violations.length > 0 ? 1 : 0)
