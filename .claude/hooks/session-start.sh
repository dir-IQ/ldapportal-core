#!/usr/bin/env bash
# SessionStart hook for ldapportal-core.
#
# Fires on session startup / resume / clear / compact. Its purpose is to give
# the session a verified baseline at moments where in-memory context and the
# actual working tree can drift apart (most importantly *after a compaction*).
#
# Design notes:
#   - Always exits 0. A session-start hook must never block or fail the session;
#     every check degrades to a printed note instead of a non-zero exit.
#   - Cheap by default. The git/tooling summary is instant. The heavier
#     compile sanity-check is opt-in via CLAUDE_SESSION_VERIFY=1 so it doesn't
#     add minutes to every single session start.
#   - stdout is fed back to the model as context, so keep output concise.

set -uo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root" || exit 0

echo "── ldapportal-core · session baseline ────────────────────────────────"

# ── Git state ────────────────────────────────────────────────────────────
if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    branch="$(git branch --show-current 2>/dev/null || echo '(detached)')"
    echo "branch:  ${branch}"

    # Ahead/behind vs origin/main (best-effort; no network).
    if git rev-parse --verify -q origin/main >/dev/null 2>&1; then
        counts="$(git rev-list --left-right --count origin/main...HEAD 2>/dev/null || echo '? ?')"
        behind="${counts%%	*}"; ahead="${counts##*	}"
        echo "vs main: ${ahead} ahead, ${behind} behind origin/main"
    fi

    dirty="$(git status --porcelain 2>/dev/null)"
    if [ -n "$dirty" ]; then
        n="$(printf '%s\n' "$dirty" | wc -l | tr -d ' ')"
        echo "tree:    DIRTY — ${n} uncommitted path(s):"
        printf '%s\n' "$dirty" | head -20 | sed 's/^/         /'
        [ "$n" -gt 20 ] && echo "         … (${n} total)"
        echo "         ↳ post-compaction? reconcile against git before editing."
    else
        echo "tree:    clean"
    fi

    # Recent commits unique to this branch — quick orientation.
    if git rev-parse --verify -q origin/main >/dev/null 2>&1; then
        log="$(git log --oneline -5 origin/main..HEAD 2>/dev/null)"
        if [ -n "$log" ]; then
            echo "recent (this branch):"
            printf '%s\n' "$log" | sed 's/^/         /'
        fi
    fi
else
    echo "(not a git work tree)"
fi

# ── Build / test cheat-sheet ──────────────────────────────────────────────
cat <<'EOF'
build/test:
         ./mvnw -q -pl core test-compile      # fast backend compile check
         ./mvnw -pl core test                 # backend unit/integration tests
         (cd frontend && npm ci && npm test)  # frontend (needs node_modules)
         make help                            # docker redeploy targets
note:    EventBackboneEndToEndTest needs Docker/Testcontainers; it errors
         where /var/run/docker.sock is unavailable (sandbox), not a regression.
EOF

# ── Opt-in compile sanity check ───────────────────────────────────────────
if [ "${CLAUDE_SESSION_VERIFY:-0}" = "1" ]; then
    echo "verify:  CLAUDE_SESSION_VERIFY=1 → running 'mvnw -q -pl core test-compile' …"
    if ./mvnw -q -pl core test-compile >/tmp/cc-session-verify.log 2>&1; then
        echo "         ✓ core test-compile OK"
    else
        echo "         ✗ core test-compile FAILED — see /tmp/cc-session-verify.log:"
        tail -15 /tmp/cc-session-verify.log | sed 's/^/         /'
    fi
fi

echo "──────────────────────────────────────────────────────────────────────"
exit 0
