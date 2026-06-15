#!/usr/bin/env bash
# Pull a Fly.io Postgres database down into the local compose stack so you
# have a working copy of the deployed data to develop against.
#
# What it does, end to end:
#   1. Reads the attached DB credentials from the *backend* app's
#      DATABASE_URL (the user the `flyctl postgres attach` created — it
#      can dump its own database).
#   2. Opens a `flyctl proxy` tunnel to the Fly Postgres cluster.
#   3. Dumps (custom format) using the LOCAL db container's pg_dump, so the
#      client version always matches the local server (postgres:16.x) and
#      you need no host-side Postgres client.
#   4. Restores into the local database with --clean, replacing its
#      contents with an exact copy.
#
# Editions (see docs/deployment-fly.md):
#   c   -> ldapportal-c-app   / ldapportal-db-c   (community)
#   ci  -> ldapportal-ci-app  / ldapportal-db-ci  (community + ISVA)  [default]
#   e   -> ldapportal-e-app   / ldapportal-db-e   (enterprise)
#
# The source edition must match the edition your local image was built
# from (the default compose stack is community-plus-isva = `ci`); a dump
# carries the addon's Flyway rows, so restoring an ISVA dump under a pure
# community image fails schema validation on the next app start.
#
# Assumes Docker Desktop (macOS/Windows), where the db container can reach
# the host's `flyctl proxy` via host.docker.internal.
#
# Usage (from repo root):
#   ./scripts/db-pull-from-fly.sh                 # pull the `ci` edition
#   EDITION=c ./scripts/db-pull-from-fly.sh        # pull community
#   FORCE=1 EDITION=e ./scripts/db-pull-from-fly.sh # no confirm prompt
#
# Overridable env: EDITION, PROXY_PORT, DB_SVC, POSTGRES_USER, POSTGRES_DB,
# KEEP_DUMP (1 = keep the intermediate .dump file), FORCE (1 = skip prompt).
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

EDITION="${EDITION:-ci}"
PROXY_PORT="${PROXY_PORT:-55432}"
DB_SVC="${DB_SVC:-db}"
PGUSER="${POSTGRES_USER:-ldapportal}"
PGDB="${POSTGRES_DB:-ldapportal}"

APP="ldapportal-${EDITION}-app"
PGAPP="ldapportal-db-${EDITION}"
DUMP_FILE="${ROOT}/fly-${EDITION}-$(date +%Y%m%d-%H%M%S).dump"

die() { echo "ERROR: $*" >&2; exit 1; }

# ── Preflight ───────────────────────────────────────────────────────────────
command -v flyctl >/dev/null 2>&1 || die "flyctl not found — install it: https://fly.io/docs/flyctl/install/"
docker compose version >/dev/null 2>&1 || die "'docker compose' not available."
docker compose ps --status running --services 2>/dev/null | grep -qx "$DB_SVC" \
  || die "local '$DB_SVC' container isn't running — start it first (make redeploy-fast / docker compose up -d $DB_SVC)."

echo "==> Source: $PGAPP (via $APP)   Target: local $DB_SVC db '$PGDB'"

# ── 1. Recover the attached DB credentials from the backend app ─────────────
echo "==> Reading DATABASE_URL from $APP ..."
SRC_RAW="$(flyctl ssh console --app "$APP" -C 'sh -c "printenv DATABASE_URL"' 2>/dev/null \
            | tr -d '\r' | grep -E '^postgres(ql)?://' | tail -n1 || true)"
[ -n "$SRC_RAW" ] || die "couldn't read DATABASE_URL from $APP. Is the app deployed and are you logged in (flyctl auth login)?"

# Rewrite the .flycast host:port to point at the local proxy, drop any
# existing query string, and force sslmode=disable (Fly's .flycast endpoint
# speaks plaintext — pgjdbc/libpq default to TLS and the handshake fails).
SRC_BASE="${SRC_RAW%%\?*}"
SRC_LOCAL="$(printf '%s' "$SRC_BASE" | sed -E "s#@[^/]+/#@host.docker.internal:${PROXY_PORT}/#")?sslmode=disable"

# ── 2. Open the proxy tunnel (cleaned up on exit) ───────────────────────────
echo "==> Opening proxy localhost:${PROXY_PORT} -> ${PGAPP}:5432 ..."
flyctl proxy "${PROXY_PORT}:5432" --app "$PGAPP" >/dev/null 2>&1 &
PROXY_PID=$!
cleanup() { kill "$PROXY_PID" 2>/dev/null || true; }
trap cleanup EXIT

# Wait for the tunnel to accept connections (max ~10s).
for _ in $(seq 1 20); do
  if (exec 3<>"/dev/tcp/127.0.0.1/${PROXY_PORT}") 2>/dev/null; then exec 3>&- 3<&-; break; fi
  kill -0 "$PROXY_PID" 2>/dev/null || die "flyctl proxy exited early — check 'flyctl proxy ${PROXY_PORT}:5432 --app ${PGAPP}' manually."
  sleep 0.5
done

# ── 3. Dump (custom format) using the LOCAL container's pg_dump ──────────────
echo "==> Dumping -> $(basename "$DUMP_FILE") ..."
docker compose exec -T "$DB_SVC" \
  pg_dump "$SRC_LOCAL" -Fc --no-owner --no-privileges > "$DUMP_FILE" \
  || die "pg_dump failed. On Docker Desktop the db container reaches the host via host.docker.internal; on plain Linux Docker that name may not resolve."
[ -s "$DUMP_FILE" ] || die "dump file is empty."
echo "    $(du -h "$DUMP_FILE" | cut -f1) written."

# ── 4. Restore into the local database (destructive) ────────────────────────
if [ "${FORCE:-0}" != "1" ]; then
  printf '==> This REPLACES local db "%s" with the Fly copy. Continue? [y/N] ' "$PGDB"
  read -r ans
  case "$ans" in [yY]|[yY][eE][sS]) ;; *) echo "Aborted (dump kept at $DUMP_FILE)."; exit 0;; esac
fi

echo "==> Stopping app so it doesn't race the restore ..."
docker compose stop app >/dev/null 2>&1 || true

echo "==> Restoring into local '$PGDB' ..."
docker compose exec -T "$DB_SVC" \
  pg_restore --clean --if-exists --no-owner --no-privileges -U "$PGUSER" -d "$PGDB" < "$DUMP_FILE"

echo "==> Restarting app ..."
docker compose start app >/dev/null 2>&1 || true

if [ "${KEEP_DUMP:-0}" != "1" ]; then rm -f "$DUMP_FILE"; else echo "    dump kept at $DUMP_FILE"; fi

echo
echo "==> Done. Local '$PGDB' now mirrors $PGAPP."
echo "    NOTE: encrypted columns (directory bind passwords, etc.) only decrypt"
echo "    if your local ENCRYPTION_KEY matches Fly's. Logins use the accounts"
echo "    from the Fly data now — the bootstrap superadmin is inactive while a"
echo "    permanent superadmin exists in the restored DB."
