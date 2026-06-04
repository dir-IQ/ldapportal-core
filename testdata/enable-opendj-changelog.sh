#!/usr/bin/env bash
# Enable OpenDJ's external change log (cn=changelog, DSEE/draft-compat form with
# integer changeNumber) on a single standalone fixture instance, so it can be
# used as a CHANGELOG-mode source for directory-sync replication links.
#
# The openidentityplatform/opendj image ships the external changelog DISABLED
# (it's part of the replication subsystem, off for a single-node fixture — see
# testdata/README-oud.md). This wires the documented standalone-server path:
# one replication server (hosts the changelog) + one replication domain on the
# data backend (feeds it). With a single domain, OpenDJ's change-number indexer
# is on by default, so cn=changelog entries carry the integer changeNumber the
# DSEE_CHANGELOG strategy reads.
#
# The changelog only records changes made AFTER enablement; existing entries are
# backfilled by reconciliation, not replayed.
#
# Usage:
#   testdata/enable-opendj-changelog.sh <container> <baseDn>
#   testdata/enable-opendj-changelog.sh ldap-oud1-primary dc=oud1,dc=example,dc=com
#
# Overridable env: BIND_DN, BIND_PW, ADMIN_PORT (4444), LDAP_PORT (1389),
#   REPL_PORT (8989), RS_ID (1), DS_ID (2), RESTART (1), VERIFY (1).
#
# NOTE: worked out against the documented OpenDJ standalone-ECL sequence but not
# executed against a live container here — if a step fails, the most likely
# culprits are the admin connector (4444) being disabled (see OFFLINE note at the
# bottom) or the synchronization provider being disabled.
set -euo pipefail

# Stop Git Bash (MSYS) rewriting the in-container /opt/... paths to Windows paths.
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'

CONTAINER="${1:?Usage: $0 <container> <baseDn>}"
BASE_DN="${2:?Usage: $0 <container> <baseDn>}"

BIND_DN="${BIND_DN:-cn=Directory Manager}"
BIND_PW="${BIND_PW:-admin}"
ADMIN_PORT="${ADMIN_PORT:-4444}"
LDAP_PORT="${LDAP_PORT:-1389}"
REPL_PORT="${REPL_PORT:-8989}"
RS_ID="${RS_ID:-1}"          # replication-server-id (unique among repl servers)
DS_ID="${DS_ID:-2}"          # domain server-id (must differ from RS_ID)
RESTART="${RESTART:-1}"
VERIFY="${VERIFY:-1}"

# A label for the domain; derive from the base DN's first RDN value (e.g. oud1).
DOMAIN_NAME="$(printf '%s' "$BASE_DN" | sed -E 's/^[a-zA-Z]+=([^,]+).*/\1/')"
[ -n "$DOMAIN_NAME" ] || DOMAIN_NAME="repl"

DSCONFIG=/opt/opendj/bin/dsconfig
LDAPMODIFY=/opt/opendj/bin/ldapmodify
LDAPSEARCH=/opt/opendj/bin/ldapsearch

die() { echo "ERROR: $*" >&2; exit 1; }
command -v docker >/dev/null 2>&1 || die "docker not found"
docker inspect "$CONTAINER" >/dev/null 2>&1 || die "container '$CONTAINER' not found (is the stack up?)"

# Admin-connector args shared by every dsconfig call.
admin_args=(--hostname localhost --port "$ADMIN_PORT"
            --bindDN "$BIND_DN" --bindPassword "$BIND_PW" --trustAll --no-prompt)

# Run a dsconfig create that is benign to repeat: a non-zero exit usually means
# "already exists", which we tolerate; a connection failure is surfaced loudly.
run_create() {
  local label="$1"; shift
  echo "==> $label"
  local out rc=0
  out="$(docker exec "$CONTAINER" "$@" 2>&1)" || rc=$?
  if [ "$rc" -ne 0 ]; then
    if printf '%s' "$out" | grep -qiE 'already exists|already configured|duplicate'; then
      echo "    (already configured — skipping)"
    elif printf '%s' "$out" | grep -qiE 'connect|refused|unable to bind|administration connector'; then
      echo "$out" >&2
      die "couldn't reach the admin connector on $ADMIN_PORT. It may be disabled — see the OFFLINE note in this script's header/footer."
    else
      echo "$out" >&2
      die "$label failed (exit $rc)."
    fi
  fi
}

echo "==> Enabling external changelog on '$CONTAINER' for base DN '$BASE_DN' (domain '$DOMAIN_NAME')"

# 1) Replication server — maintains cn=changelog.
run_create "Create replication server (port $REPL_PORT, id $RS_ID)" \
  "$DSCONFIG" create-replication-server "${admin_args[@]}" \
    --provider-name 'Multimaster Synchronization' \
    --type generic \
    --set "replication-port:$REPL_PORT" \
    --set "replication-server-id:$RS_ID"

# 2) Replication domain on the data backend — publishes its changes to the log.
run_create "Create replication domain (base $BASE_DN, server-id $DS_ID)" \
  "$DSCONFIG" create-replication-domain "${admin_args[@]}" \
    --provider-name 'Multimaster Synchronization' \
    --type generic \
    --domain-name "$DOMAIN_NAME" \
    --set "base-dn:$BASE_DN" \
    --set "replication-server:localhost:$REPL_PORT" \
    --set "server-id:$DS_ID"

# 3) Restart so the changelog backend initializes.
if [ "$RESTART" = "1" ]; then
  echo "==> Restarting $CONTAINER ..."
  docker restart "$CONTAINER" >/dev/null
  # Wait for LDAP to answer again before probing.
  for _ in $(seq 1 30); do
    if docker exec "$CONTAINER" "$LDAPSEARCH" -h localhost -p "$LDAP_PORT" \
         -D "$BIND_DN" -w "$BIND_PW" -b '' -s base '(objectClass=*)' 1.1 >/dev/null 2>&1; then
      break
    fi
    sleep 1
  done
fi

# 4) Make one write + confirm the changelog now records it.
if [ "$VERIFY" = "1" ]; then
  echo "==> Writing a probe change to seed the changelog ..."
  printf 'dn: %s\nchangetype: modify\nreplace: description\ndescription: changelog-enabled-%s\n' \
    "$BASE_DN" "$(date +%s)" \
  | docker exec -i "$CONTAINER" "$LDAPMODIFY" \
      -h localhost -p "$LDAP_PORT" -D "$BIND_DN" -w "$BIND_PW" >/dev/null \
  || echo "    (probe write failed — not fatal; you can make any change instead)"

  echo "==> Reading cn=changelog ..."
  if docker exec "$CONTAINER" "$LDAPSEARCH" \
       -h localhost -p "$LDAP_PORT" -D "$BIND_DN" -w "$BIND_PW" \
       -b 'cn=changelog' -s one '(objectClass=changeLogEntry)' \
       changeNumber targetDN changeType; then
    echo
    echo "==> Done. cn=changelog is live — the link's Test-changelog probe should now pass."
  else
    echo
    die "cn=changelog still not readable — the domain may not have initialized. Check 'docker exec $CONTAINER /opt/opendj/bin/status -D \"$BIND_DN\" -w \"$BIND_PW\" --trustAll'."
  fi
fi

# ── OFFLINE fallback ──────────────────────────────────────────────────────────
# If dsconfig can't reach the admin connector (4444 disabled in this build), run
# the two create-* commands with the server stopped instead:
#   docker stop  <container>
#   docker exec  <container> /opt/opendj/bin/dsconfig --offline create-replication-server \
#       --provider-name 'Multimaster Synchronization' --type generic \
#       --set replication-port:8989 --set replication-server-id:1 --no-prompt
#   docker exec  <container> /opt/opendj/bin/dsconfig --offline create-replication-domain \
#       --provider-name 'Multimaster Synchronization' --type generic --domain-name <name> \
#       --set base-dn:<baseDn> --set replication-server:localhost:8989 --set server-id:2 --no-prompt
#   docker start <container>
# (Note: the container must keep running for `docker exec`; if `docker stop` ends
#  it, use a `docker run … --entrypoint` shell or the image's offline tooling.)
#
# If you instead see "provider is disabled", prepend:
#   dsconfig set-synchronization-provider-prop --provider-name 'Multimaster Synchronization' \
#       --set enabled:true <admin args>
