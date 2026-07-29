#!/usr/bin/env bash
# Export a running LDAPPortal's own configuration (directories, admin accounts
# and their admin-wide permissions, and vendor config such as ISVA) as a
# declarative YAML file for disaster recovery and infrastructure-as-code.
#
# The output is the same `bootstrap-config.yml` shape the server reconciles at
# startup (BOOTSTRAP_CONFIG_FILE) and that the by-key REST upserts accept — so a
# dump feeds straight back into a fresh install. See docs/iac/README.md.
#
# Secrets are NOT exported. Each stored credential is emitted as a ${ENV_VAR}
# placeholder; the file header lists every variable you must supply at restore
# time (from your secret manager / vault). The dump is therefore safe to commit.
#
# What it does:
#   1. Authenticates to the API as a SUPERADMIN — either with an API token
#      (LDAP_PAT, sent as a bearer token) or by logging in with a username +
#      password (captures the jwt-token cookie).
#   2. GETs /api/v1/superadmin/config/export and writes the YAML to a file.
#
# Usage (from repo root or anywhere):
#   # With a SUPERADMIN API token (recommended for automation):
#   BASE_URL=https://portal.example.com LDAP_PAT=ldap_pat_xxx \
#     ./scripts/export-config.sh
#
#   # With a username + password (interactive / bootstrap):
#   BASE_URL=https://portal.example.com \
#   LDAP_USERNAME=superadmin LDAP_PASSWORD='...' \
#     ./scripts/export-config.sh
#
# Overridable env:
#   BASE_URL     API base URL (default http://localhost:9080)
#   OUT          output file (default bootstrap-config.<host>-<timestamp>.yml)
#   LDAP_PAT     SUPERADMIN API token (bearer); takes precedence over login
#   LDAP_USERNAME / LDAP_PASSWORD  SUPERADMIN credentials for cookie login
#   CURL_OPTS    extra curl options (e.g. --insecure for a self-signed cert)
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:9080}"
BASE_URL="${BASE_URL%/}"
EXPORT_PATH="/api/v1/superadmin/config/export"
# shellcheck disable=SC2206
CURL_EXTRA=(${CURL_OPTS:-})

host="$(printf '%s' "$BASE_URL" | sed -E 's#^https?://##; s#[:/].*$##; s#[^a-zA-Z0-9._-]#_#g')"
# Timestamp without Bash-only date extensions; falls back to epoch if needed.
stamp="$(date -u +%Y%m%dT%H%M%SZ 2>/dev/null || date -u +%s)"
OUT="${OUT:-bootstrap-config.${host}-${stamp}.yml}"

fail() { echo "error: $*" >&2; exit 1; }
command -v curl >/dev/null 2>&1 || fail "curl is required"

auth_header=()
cookie_jar=""
cleanup() { [ -n "$cookie_jar" ] && rm -f "$cookie_jar"; }
trap cleanup EXIT

if [ -n "${LDAP_PAT:-}" ]; then
  auth_header=(-H "Authorization: Bearer ${LDAP_PAT}")
elif [ -n "${LDAP_USERNAME:-}" ] && [ -n "${LDAP_PASSWORD:-}" ]; then
  cookie_jar="$(mktemp)"
  # The JWT is returned only as an httpOnly cookie; capture it into the jar.
  login_status="$(curl -sS "${CURL_EXTRA[@]}" -o /dev/null -w '%{http_code}' \
    -c "$cookie_jar" -X POST "${BASE_URL}/api/v1/auth/login" \
    -H 'Content-Type: application/json' \
    --data "$(printf '{"username":%s,"password":%s}' \
      "\"${LDAP_USERNAME}\"" "\"${LDAP_PASSWORD}\"")")"
  [ "$login_status" = "200" ] || fail "login failed (HTTP ${login_status})"
else
  fail "provide LDAP_PAT, or LDAP_USERNAME and LDAP_PASSWORD"
fi

cookie_opt=()
[ -n "$cookie_jar" ] && cookie_opt=(-b "$cookie_jar")

status="$(curl -sS "${CURL_EXTRA[@]}" "${auth_header[@]}" "${cookie_opt[@]}" \
  -o "$OUT" -w '%{http_code}' \
  -H 'Accept: application/yaml' "${BASE_URL}${EXPORT_PATH}")"

if [ "$status" != "200" ]; then
  echo "--- server response ---" >&2
  cat "$OUT" >&2 || true
  rm -f "$OUT"
  fail "export failed (HTTP ${status}) — need a SUPERADMIN credential"
fi

echo "Wrote configuration export to: ${OUT}"
echo "Review the header for the \${ENV_VAR} secrets required to restore it."
