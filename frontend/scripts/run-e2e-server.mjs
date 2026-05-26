#!/usr/bin/env node
/**
 * Standalone launcher for the Spring Boot e2e backend.
 *
 * Why this exists: when iterating on Playwright tests locally we often want
 * the backend running long-term (so `reuseExistingServer: true` in
 * playwright.config.ts kicks in and each test run is fast). Running
 * `./mvnw spring-boot:test-run -pl ee -Dspring-boot.run.profiles=e2e` by
 * hand is two layers of platform-specific quoting; this wrapper does the
 * right thing on Windows / macOS / Linux without the operator caring.
 *
 * Usage (from frontend/):
 *   npm run e2e:server         # foreground; Ctrl-C to stop
 *
 * After it logs "Started ... in ... seconds", the backend is up at
 * http://localhost:8080. Then in another shell run `npm run e2e:smoke`.
 *
 * Stays in sync with the same command Playwright's webServer config uses
 * — both go through ./mvnw / ./mvnw.cmd in the project root.
 */
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import { execFileSync } from 'node:child_process'

const here = path.dirname(fileURLToPath(import.meta.url))
const projectRoot = path.resolve(here, '..', '..')
const isWin = process.platform === 'win32'

// Prefer the system-installed `mvn` over the wrapper when one is on PATH.
// The wrapper bootstraps by downloading the Maven distribution from
// repo.maven.apache.org, which intermittently 403s and takes down CI's
// webServer launch (setup-java caches ~/.m2/repository but not the
// wrapper distribution). CI runners ship Maven preinstalled, so they
// take this path; a developer machine without `mvn` falls back to the
// committed wrapper. Either way `.mvn/extensions.xml` is honored.
function hasSystemMvn() {
  try {
    execFileSync(isWin ? 'where' : 'which', ['mvn'], { stdio: 'ignore' })
    return true
  } catch {
    return false
  }
}
// For the wrapper fallback, use the absolute path to mvnw / mvnw.cmd. We
// tried `./mvnw.cmd` from the project-root cwd but cmd.exe (which Node
// spawns through when shell:true on Windows) parses the leading `.` as a
// token separate from `/mvnw.cmd` and fails. Absolute paths sidestep that.
const wrapper = hasSystemMvn()
  ? 'mvn'
  : path.join(projectRoot, isWin ? 'mvnw.cmd' : 'mvnw')

const args = [
  'spring-boot:test-run',
  '-pl', 'ee',
  '-Dspring-boot.run.profiles=e2e',
]

console.log(`[e2e:server] cwd=${projectRoot}`)
console.log(`[e2e:server] running: "${wrapper}" ${args.join(' ')}`)
console.log()
// Surface the e2e profile's hardcoded credentials so a developer who
// shells in via the browser doesn't have to grep application-e2e.yml.
// These are intentionally separate from the docker stack's .env values
// — different environments, different fixtures.
console.log('[e2e:server] e2e profile credentials (from ee/src/test/resources/application-e2e.yml):')
console.log('[e2e:server]   username: superadmin')
console.log('[e2e:server]   password: test-superadmin-pw')
console.log('[e2e:server]   (NOTE: differs from .env BOOTSTRAP_SUPERADMIN_PASSWORD used by the docker stack.)')
console.log('[e2e:server] waiting for backend on http://localhost:8080 …')
console.log()

// On Windows we MUST go through a shell because .cmd files can't be
// executed directly by Node's spawn (CreateProcess refuses non-PE binaries).
// Wrapping the absolute path in quotes keeps spaces in the path (e.g. a
// project under "Program Files") from breaking arg parsing.
//
// On POSIX, mvnw is a regular shell script with #!/usr/bin/env sh — Node
// can spawn it directly without going through a shell, which avoids the
// DEP0190 deprecation warning Node emits when shell: true is combined
// with an args array.
const child = process.platform === 'win32'
  ? spawn(`"${wrapper}" ${args.join(' ')}`, {
      cwd: projectRoot,
      shell: true,
      stdio: 'inherit',
    })
  : spawn(wrapper, args, {
      cwd: projectRoot,
      stdio: 'inherit',
    })

// Forward signals so Ctrl-C cleanly stops the JVM rather than orphaning it.
//
// Windows is the tricky case. `child.kill()` on Windows ultimately calls
// TerminateProcess on the cmd.exe shell PID only — Node has no Unix-style
// process-group semantics on Windows, so the JVM child of cmd.exe survives
// and keeps port 8080 bound. The standard fix is `taskkill /T` which walks
// the process tree and terminates every descendant. Without this, every
// Ctrl-C of `npm run e2e:server` would orphan a Java process the operator
// would then have to hunt down via netstat.
function killProcessTree() {
  if (!child.pid) return
  if (isWin) {
    try {
      execFileSync('taskkill', ['/PID', String(child.pid), '/T', '/F'], { stdio: 'ignore' })
    } catch {
      // taskkill returns non-zero if the process is already gone — fine.
    }
  } else {
    try { child.kill('SIGINT') } catch { /* already dead */ }
  }
}

const forward = (sig) => () => {
  killProcessTree()
  // Don't immediately exit — let the child's 'exit' handler below
  // propagate the real exit code. If the child takes too long, the
  // user can Ctrl-C twice (Node's default behaviour kills us on the
  // second one).
  void sig
}
process.on('SIGINT', forward('SIGINT'))
process.on('SIGTERM', forward('SIGTERM'))

child.on('exit', (code, signal) => {
  if (signal) {
    console.error(`[e2e:server] terminated by signal ${signal}`)
    process.exit(1)
  }
  process.exit(code ?? 0)
})

child.on('error', (err) => {
  console.error(`[e2e:server] failed to spawn: ${err.message}`)
  process.exit(1)
})
