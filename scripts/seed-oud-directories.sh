#!/usr/bin/env bash
# Seed the six OUD (OpenDJ) fixture instances as SIX independent directory
# connections in LDAP Portal via the REST API — every primary and every
# alternate gets its own entry — then seed baseline LDAP entries on each
# instance and three provisioning profiles on the OUD{1,2,3} primaries.
#
# Hostnames / ports / base DNs / credentials are taken from compose.yaml:
#
#   | entry          | service        | published | internal | base DN                   |
#   |----------------|----------------|-----------|----------|---------------------------|
#   | OUD1 Primary   | oud1-primary   | 3389      | 1389     | dc=oud1,dc=example,dc=com |
#   | OUD1 Alternate | oud1-alternate | 4389      | 1389     | dc=oud1,dc=example,dc=com |
#   | OUD2 Primary   | oud2-primary   | 5389      | 1389     | dc=oud2,dc=example,dc=com |
#   | OUD2 Alternate | oud2-alternate | 6389      | 1389     | dc=oud2,dc=example,dc=com |
#   | OUD3 Primary   | oud3-primary   | 8389      | 1389     | dc=oud3,dc=example,dc=com |
#   | OUD3 Alternate | oud3-alternate | 9389      | 1389     | dc=oud3,dc=example,dc=com |
#
# All fixtures: bind cn=Directory Manager / admin, TLS off (sslMode NONE).
#
# What it does, in three phases (each idempotent):
#   1. Directory connections — one per instance (display name is just the
#      label, e.g. "OUD1 Primary"; no "(OpenDJ fixture)" suffix).
#   2. Baseline entries on ALL six instances, under each instance's own base DN:
#        ou=People                         (top, organizationalUnit)
#        ou=Groups                         (top, organizationalUnit)
#        cn=AllEmployees,ou=Groups         (top, groupOfUniqueNames)
#        secAuthority=Default              (top, secAuthorityInfo)
#      The groupOfUniqueNames MUST carry a uniqueMember, so a clearly-labeled
#      placeholder member is seeded; replace/clear it once real members exist.
#      secAuthorityInfo / secAuthority are IBM (TAM/SDS) schema — if that schema
#      isn't loaded on the OpenDJ fixtures the add fails; it's reported but is
#      NOT fatal to the run.
#   3. Provisioning profiles on the OUD{1,2,3} primaries:
#        OUD1 Primary -> "PROD (OUD1)"  targetOuDn ou=People,dc=oud1,...
#        OUD2 Primary -> "QA (OUD2)"    targetOuDn ou=People,dc=oud2,...
#        OUD3 Primary -> "INT (OUD3)"   targetOuDn ou=People,dc=oud3,...
#      (Users are created directly under ou=People; cn=AllEmployees is the
#      all-employees group under ou=Groups.)
#
# Host resolution mode:
#   MODE=network (default) — host=<service name>, port=1389 (internal).
#       Correct when the portal runs as a compose service (it resolves the
#       fixture service names over the compose network).
#   MODE=host — host=host.docker.internal, port=<published port>.
#       For a portal running OUTSIDE Docker, on Docker Desktop (macOS/Win).
#
# Requires: curl, jq.
#
# Usage (from repo root):
#   SUPERADMIN_PASSWORD=... ./scripts/seed-oud-directories.sh
#   MODE=host SUPERADMIN_PASSWORD=... ./scripts/seed-oud-directories.sh
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:9080}"     # frontend (proxies /api/v1); or :9090 for the backend
SUPERADMIN_USER="${SUPERADMIN_USER:-superadmin}"
MODE="${MODE:-network}"                            # network | host
INTERNAL_PORT="${INTERNAL_PORT:-1389}"             # container LDAP port (compose: right side of "NNNN:1389")
HOST_GATEWAY="${HOST_GATEWAY:-host.docker.internal}"
BIND_DN="${BIND_DN:-cn=Directory Manager}"
BIND_PW="${BIND_PW:-admin}"

# From compose.yaml: "label|serviceName|publishedPort|baseDn"
INSTANCES=(
  "OUD1 Primary|oud1-primary|3389|dc=oud1,dc=example,dc=com"
  "OUD1 Alternate|oud1-alternate|4389|dc=oud1,dc=example,dc=com"
  "OUD2 Primary|oud2-primary|5389|dc=oud2,dc=example,dc=com"
  "OUD2 Alternate|oud2-alternate|6389|dc=oud2,dc=example,dc=com"
  "OUD3 Primary|oud3-primary|8389|dc=oud3,dc=example,dc=com"
  "OUD3 Alternate|oud3-alternate|9389|dc=oud3,dc=example,dc=com"
)

# Provisioning profiles: "directory displayName|profile name|targetOuDn"
PROFILES=(
  "OUD1 Primary|PROD (OUD1)|ou=People,dc=oud1,dc=example,dc=com"
  "OUD2 Primary|QA (OUD2)|ou=People,dc=oud2,dc=example,dc=com"
  "OUD3 Primary|INT (OUD3)|ou=People,dc=oud3,dc=example,dc=com"
)

die() { echo "ERROR: $*" >&2; exit 1; }
command -v curl >/dev/null || die "curl not found"
command -v jq   >/dev/null || die "jq not found (brew install jq)"
case "$MODE" in network|host) ;; *) die "MODE must be 'network' or 'host' (got '$MODE')";; esac

PW="${SUPERADMIN_PASSWORD:-}"
if [ -z "$PW" ]; then read -r -s -p "Password for $SUPERADMIN_USER: " PW; echo; fi
[ -n "$PW" ] || die "no password provided"

COOKIES="$(mktemp)"; trap 'rm -f "$COOKIES"' EXIT
api="$BASE_URL/api/v1"

# ── Login (captures the httpOnly jwt cookie into the jar) ───────────────────
echo "==> Logging in to $api as $SUPERADMIN_USER (mode=$MODE) ..."
login_body="$(jq -n --arg u "$SUPERADMIN_USER" --arg p "$PW" '{username:$u,password:$p}')"
code="$(curl -sS -o /dev/null -w '%{http_code}' -c "$COOKIES" \
  -X POST "$api/auth/login" -H 'Content-Type: application/json' -d "$login_body")"
[ "$code" = "200" ] || die "login failed (HTTP $code) — check credentials / that the app is up."

# ── Existing directories (for idempotency by displayName) ───────────────────
existing="$(curl -sS -b "$COOKIES" "$api/superadmin/directories")"
has_display() { jq -e --arg n "$1" 'any(.[]; .displayName == $n)' >/dev/null 2>&1 <<<"$existing"; }

# ════════════════════════════════════════════════════════════════════════════
# Phase 1 — directory connections
# ════════════════════════════════════════════════════════════════════════════
echo
echo "==> Phase 1: directory connections"
created=0; skipped=0; failed=0
for spec in "${INSTANCES[@]}"; do
  IFS='|' read -r label service published baseDn <<<"$spec"
  display="$label"

  if [ "$MODE" = "host" ]; then
    host="$HOST_GATEWAY"; port="$published"
  else
    host="$service";      port="$INTERNAL_PORT"
  fi

  if has_display "$display"; then
    echo "==> $display — already exists, skipping."
    skipped=$((skipped + 1)); continue
  fi

  payload="$(jq -n \
    --arg displayName "$display" \
    --arg host "$host" \
    --arg bindDn "$BIND_DN" \
    --arg bindPassword "$BIND_PW" \
    --arg baseDn "$baseDn" \
    --argjson port "$port" \
    '{
      directoryType: "ORACLE_UNIFIED_DIRECTORY",
      displayName: $displayName,
      host: $host, port: $port, sslMode: "NONE", trustAllCerts: false,
      bindDn: $bindDn, bindPassword: $bindPassword, baseDn: $baseDn,
      pagingSize: 1000, poolMinSize: 1, poolMaxSize: 8,
      poolConnectTimeoutSeconds: 10, poolResponseTimeoutSeconds: 30,
      enabled: true, selfServiceEnabled: false,
      userBaseDns:  [ { dn: $baseDn, displayOrder: 0 } ],
      groupBaseDns: [ { dn: $baseDn, displayOrder: 0 } ],
      replicationEnabled: false
    }')"

  resp="$(mktemp)"
  code="$(curl -sS -b "$COOKIES" -o "$resp" -w '%{http_code}' \
    -X POST "$api/superadmin/directories" \
    -H 'Content-Type: application/json' -d "$payload")"

  case "$code" in
    201)
      id="$(jq -r '.id // empty' "$resp")"
      echo "==> $display — created (id ${id:-?})  [$host:$port]"
      created=$((created + 1))
      # Live reachability probe — informational only, never fails the run.
      if [ -n "$id" ]; then
        st="$(curl -sS -b "$COOKIES" "$api/superadmin/directories/$id/status" || true)"
        ok="$(jq -r '.success // empty'  <<<"$st" 2>/dev/null || true)"
        msg="$(jq -r '.message // empty' <<<"$st" 2>/dev/null || true)"
        echo "      reachability: ${ok:-unknown}${msg:+ — $msg}"
      fi
      ;;
    402)
      echo "==> $display — REJECTED by license limit (HTTP 402): $(jq -r '.detail // .message // .' "$resp" 2>/dev/null)"
      failed=$((failed + 1))
      ;;
    *)
      echo "==> $display — FAILED (HTTP $code): $(jq -r '.detail // .message // .' "$resp" 2>/dev/null | head -c 300)"
      failed=$((failed + 1))
      ;;
  esac
  rm -f "$resp"
done

# Refresh the directory list so phases 2 & 3 can resolve ids (incl. ones that
# already existed before this run).
dirs_json="$(curl -sS -b "$COOKIES" "$api/superadmin/directories")"
id_for_display() { jq -r --arg n "$1" 'map(select(.displayName==$n)) | .[0].id // empty' <<<"$dirs_json"; }

# ════════════════════════════════════════════════════════════════════════════
# Phase 2 — baseline entries on every instance
# ════════════════════════════════════════════════════════════════════════════
echo
echo "==> Phase 2: baseline LDAP entries (all six instances)"
ent_created=0; ent_skipped=0; ent_failed=0

# POST one entry; classify the result. Never fatal (secAuthority schema may be
# absent on stock OpenDJ; an already-present entry is treated as a skip).
create_entry() {
  local dirId="$1" dn="$2" attrs="$3"
  local body resp code msg
  body="$(jq -n --arg dn "$dn" --argjson attributes "$attrs" '{dn:$dn, attributes:$attributes}')"
  resp="$(mktemp)"
  code="$(curl -sS -b "$COOKIES" -o "$resp" -w '%{http_code}' \
    -X POST "$api/superadmin/directories/$dirId/browse" \
    -H 'Content-Type: application/json' -d "$body")"
  case "$code" in
    201) echo "      + $dn"; ent_created=$((ent_created + 1)) ;;
    *)
      msg="$(jq -r '.detail // .message // .' "$resp" 2>/dev/null | head -c 200)"
      if printf '%s' "$msg" | grep -qiE 'exist|already'; then
        echo "      = $dn (exists)"; ent_skipped=$((ent_skipped + 1))
      else
        echo "      ! $dn — HTTP $code: $msg"; ent_failed=$((ent_failed + 1))
      fi
      ;;
  esac
  rm -f "$resp"
}

for spec in "${INSTANCES[@]}"; do
  IFS='|' read -r label service published baseDn <<<"$spec"
  id="$(id_for_display "$label")"
  if [ -z "$id" ]; then
    echo "==> $label — no directory id, skipping entries."; continue
  fi
  echo "==> $label — entries under $baseDn (dir $id)"

  # Parents first, then their children.
  create_entry "$id" "ou=People,$baseDn" \
    '{"objectClass":["top","organizationalUnit"],"ou":["People"]}'
  create_entry "$id" "ou=Groups,$baseDn" \
    '{"objectClass":["top","organizationalUnit"],"ou":["Groups"]}'
  # groupOfUniqueNames requires uniqueMember (MUST) — seed a placeholder.
  create_entry "$id" "cn=AllEmployees,ou=Groups,$baseDn" \
    "$(jq -n --arg ph "uid=seed-placeholder,ou=People,$baseDn" \
        '{objectClass:["top","groupOfUniqueNames"],cn:["AllEmployees"],uniqueMember:[$ph]}')"
  # IBM schema — non-fatal if secAuthorityInfo/secAuthority aren't loaded.
  create_entry "$id" "secAuthority=Default,$baseDn" \
    '{"objectClass":["top","secAuthorityInfo"],"secAuthority":["Default"]}'
done

# ════════════════════════════════════════════════════════════════════════════
# Phase 3 — provisioning profiles on the OUD{1,2,3} primaries
# ════════════════════════════════════════════════════════════════════════════
echo
echo "==> Phase 3: provisioning profiles"

# The three profiles are identical except for name + targetOuDn, so the
# attribute layout is defined once and parameterized per profile.
read -r -d '' ATTR_CONFIGS <<'JSON' || true
[
  {"attributeName":"uid","customLabel":"User ID","inputType":"TEXT","requiredOnCreate":true,"editableOnCreate":true,"editableOnUpdate":false,"selfServiceEdit":false,"selfRegistrationEdit":false,"defaultValue":"","computedExpression":"","validationRegex":"","validationMessage":"","allowedValues":"","minLength":null,"maxLength":null,"sectionName":"Identity","columnSpan":3,"hidden":false,"registrationSectionName":null,"registrationColumnSpan":null,"registrationDisplayOrder":null,"selfServiceSectionName":null,"selfServiceColumnSpan":null,"selfServiceDisplayOrder":null},
  {"attributeName":"cn","customLabel":"Common Name","inputType":"TEXT","requiredOnCreate":true,"editableOnCreate":true,"editableOnUpdate":true,"selfServiceEdit":true,"selfRegistrationEdit":true,"defaultValue":"","computedExpression":"","validationRegex":"","validationMessage":"","allowedValues":"","minLength":null,"maxLength":null,"sectionName":"Identity","columnSpan":3,"hidden":false,"registrationSectionName":null,"registrationColumnSpan":null,"registrationDisplayOrder":null,"selfServiceSectionName":null,"selfServiceColumnSpan":null,"selfServiceDisplayOrder":null},
  {"attributeName":"givenName","customLabel":"First Name","inputType":"TEXT","requiredOnCreate":false,"editableOnCreate":true,"editableOnUpdate":true,"selfServiceEdit":true,"selfRegistrationEdit":true,"defaultValue":"","computedExpression":"","validationRegex":"","validationMessage":"","allowedValues":"","minLength":null,"maxLength":null,"sectionName":"Identity","columnSpan":3,"hidden":false,"registrationSectionName":null,"registrationColumnSpan":null,"registrationDisplayOrder":null,"selfServiceSectionName":null,"selfServiceColumnSpan":null,"selfServiceDisplayOrder":null},
  {"attributeName":"sn","customLabel":"Last Name","inputType":"TEXT","requiredOnCreate":true,"editableOnCreate":true,"editableOnUpdate":true,"selfServiceEdit":true,"selfRegistrationEdit":true,"defaultValue":"","computedExpression":"","validationRegex":"","validationMessage":"","allowedValues":"","minLength":null,"maxLength":null,"sectionName":"Identity","columnSpan":3,"hidden":false,"registrationSectionName":null,"registrationColumnSpan":null,"registrationDisplayOrder":null,"selfServiceSectionName":null,"selfServiceColumnSpan":null,"selfServiceDisplayOrder":null},
  {"attributeName":"displayName","customLabel":"Display Name","inputType":"TEXT","requiredOnCreate":false,"editableOnCreate":true,"editableOnUpdate":true,"selfServiceEdit":true,"selfRegistrationEdit":true,"defaultValue":"","computedExpression":"","validationRegex":"","validationMessage":"","allowedValues":"","minLength":null,"maxLength":null,"sectionName":"Identity","columnSpan":6,"hidden":false,"registrationSectionName":null,"registrationColumnSpan":null,"registrationDisplayOrder":null,"selfServiceSectionName":null,"selfServiceColumnSpan":null,"selfServiceDisplayOrder":null},
  {"attributeName":"initials","customLabel":"Initials","inputType":"TEXT","requiredOnCreate":false,"editableOnCreate":true,"editableOnUpdate":true,"selfServiceEdit":false,"selfRegistrationEdit":false,"defaultValue":"","computedExpression":"","validationRegex":"","validationMessage":"","allowedValues":"","minLength":null,"maxLength":null,"sectionName":"Identity","columnSpan":2,"hidden":false,"registrationSectionName":null,"registrationColumnSpan":null,"registrationDisplayOrder":null,"selfServiceSectionName":null,"selfServiceColumnSpan":null,"selfServiceDisplayOrder":null},
  {"attributeName":"employeeNumber","customLabel":"Employee Number","inputType":"TEXT","requiredOnCreate":false,"editableOnCreate":true,"editableOnUpdate":true,"selfServiceEdit":false,"selfRegistrationEdit":false,"defaultValue":"","computedExpression":"","validationRegex":"","validationMessage":"","allowedValues":"","minLength":null,"maxLength":null,"sectionName":"Identity","columnSpan":2,"hidden":false,"registrationSectionName":null,"registrationColumnSpan":null,"registrationDisplayOrder":null,"selfServiceSectionName":null,"selfServiceColumnSpan":null,"selfServiceDisplayOrder":null},
  {"attributeName":"employeeType","customLabel":"Employee Type","inputType":"TEXT","requiredOnCreate":false,"editableOnCreate":true,"editableOnUpdate":true,"selfServiceEdit":false,"selfRegistrationEdit":false,"defaultValue":"","computedExpression":"","validationRegex":"","validationMessage":"","allowedValues":"","minLength":null,"maxLength":null,"sectionName":"Identity","columnSpan":2,"hidden":false,"registrationSectionName":null,"registrationColumnSpan":null,"registrationDisplayOrder":null,"selfServiceSectionName":null,"selfServiceColumnSpan":null,"selfServiceDisplayOrder":null},
  {"attributeName":"mail","customLabel":"Email","inputType":"TEXT","requiredOnCreate":false,"editableOnCreate":true,"editableOnUpdate":true,"selfServiceEdit":true,"selfRegistrationEdit":true,"defaultValue":"","computedExpression":"","validationRegex":"","validationMessage":"","allowedValues":"","minLength":null,"maxLength":null,"sectionName":"Contact","columnSpan":6,"hidden":false,"registrationSectionName":null,"registrationColumnSpan":null,"registrationDisplayOrder":null,"selfServiceSectionName":null,"selfServiceColumnSpan":null,"selfServiceDisplayOrder":null},
  {"attributeName":"telephoneNumber","customLabel":"Phone","inputType":"TEXT","requiredOnCreate":false,"editableOnCreate":true,"editableOnUpdate":true,"selfServiceEdit":true,"selfRegistrationEdit":true,"defaultValue":"","computedExpression":"","validationRegex":"","validationMessage":"","allowedValues":"","minLength":null,"maxLength":null,"sectionName":"Contact","columnSpan":2,"hidden":false,"registrationSectionName":null,"registrationColumnSpan":null,"registrationDisplayOrder":null,"selfServiceSectionName":null,"selfServiceColumnSpan":null,"selfServiceDisplayOrder":null},
  {"attributeName":"mobile","customLabel":"Mobile","inputType":"TEXT","requiredOnCreate":false,"editableOnCreate":true,"editableOnUpdate":true,"selfServiceEdit":true,"selfRegistrationEdit":true,"defaultValue":"","computedExpression":"","validationRegex":"","validationMessage":"","allowedValues":"","minLength":null,"maxLength":null,"sectionName":"Contact","columnSpan":2,"hidden":false,"registrationSectionName":null,"registrationColumnSpan":null,"registrationDisplayOrder":null,"selfServiceSectionName":null,"selfServiceColumnSpan":null,"selfServiceDisplayOrder":null},
  {"attributeName":"pager","customLabel":"Pager","inputType":"TEXT","requiredOnCreate":false,"editableOnCreate":true,"editableOnUpdate":true,"selfServiceEdit":true,"selfRegistrationEdit":true,"defaultValue":"","computedExpression":"","validationRegex":"","validationMessage":"","allowedValues":"","minLength":null,"maxLength":null,"sectionName":"Contact","columnSpan":2,"hidden":false,"registrationSectionName":null,"registrationColumnSpan":null,"registrationDisplayOrder":null,"selfServiceSectionName":null,"selfServiceColumnSpan":null,"selfServiceDisplayOrder":null},
  {"attributeName":"facsimileTelephoneNumber","customLabel":"Fax","inputType":"TEXT","requiredOnCreate":false,"editableOnCreate":true,"editableOnUpdate":true,"selfServiceEdit":true,"selfRegistrationEdit":true,"defaultValue":"","computedExpression":"","validationRegex":"","validationMessage":"","allowedValues":"","minLength":null,"maxLength":null,"sectionName":"Contact","columnSpan":2,"hidden":false,"registrationSectionName":null,"registrationColumnSpan":null,"registrationDisplayOrder":null,"selfServiceSectionName":null,"selfServiceColumnSpan":null,"selfServiceDisplayOrder":null},
  {"attributeName":"homePhone","customLabel":"Home Phone","inputType":"TEXT","requiredOnCreate":false,"editableOnCreate":true,"editableOnUpdate":true,"selfServiceEdit":true,"selfRegistrationEdit":true,"defaultValue":"","computedExpression":"","validationRegex":"","validationMessage":"","allowedValues":"","minLength":null,"maxLength":null,"sectionName":"Contact","columnSpan":2,"hidden":false,"registrationSectionName":null,"registrationColumnSpan":null,"registrationDisplayOrder":null,"selfServiceSectionName":null,"selfServiceColumnSpan":null,"selfServiceDisplayOrder":null},
  {"attributeName":"postalAddress","customLabel":"Postal Address","inputType":"TEXTAREA","requiredOnCreate":false,"editableOnCreate":true,"editableOnUpdate":true,"selfServiceEdit":true,"selfRegistrationEdit":true,"defaultValue":"","computedExpression":"","validationRegex":"","validationMessage":"","allowedValues":"","minLength":null,"maxLength":null,"sectionName":"Contact","columnSpan":6,"hidden":false,"registrationSectionName":null,"registrationColumnSpan":null,"registrationDisplayOrder":null,"selfServiceSectionName":null,"selfServiceColumnSpan":null,"selfServiceDisplayOrder":null},
  {"attributeName":"street","customLabel":"Street Address","inputType":"TEXT","requiredOnCreate":false,"editableOnCreate":true,"editableOnUpdate":true,"selfServiceEdit":true,"selfRegistrationEdit":true,"defaultValue":"","computedExpression":"","validationRegex":"","validationMessage":"","allowedValues":"","minLength":null,"maxLength":null,"sectionName":"Contact","columnSpan":6,"hidden":false,"registrationSectionName":null,"registrationColumnSpan":null,"registrationDisplayOrder":null,"selfServiceSectionName":null,"selfServiceColumnSpan":null,"selfServiceDisplayOrder":null},
  {"attributeName":"l","customLabel":"City","inputType":"TEXT","requiredOnCreate":false,"editableOnCreate":true,"editableOnUpdate":true,"selfServiceEdit":true,"selfRegistrationEdit":true,"defaultValue":"","computedExpression":"","validationRegex":"","validationMessage":"","allowedValues":"","minLength":null,"maxLength":null,"sectionName":"Contact","columnSpan":2,"hidden":false,"registrationSectionName":null,"registrationColumnSpan":null,"registrationDisplayOrder":null,"selfServiceSectionName":null,"selfServiceColumnSpan":null,"selfServiceDisplayOrder":null},
  {"attributeName":"st","customLabel":"State/Province","inputType":"TEXT","requiredOnCreate":false,"editableOnCreate":true,"editableOnUpdate":true,"selfServiceEdit":true,"selfRegistrationEdit":true,"defaultValue":"","computedExpression":"","validationRegex":"","validationMessage":"","allowedValues":"","minLength":null,"maxLength":null,"sectionName":"Contact","columnSpan":2,"hidden":false,"registrationSectionName":null,"registrationColumnSpan":null,"registrationDisplayOrder":null,"selfServiceSectionName":null,"selfServiceColumnSpan":null,"selfServiceDisplayOrder":null},
  {"attributeName":"c","customLabel":"Country Code","inputType":"TEXT","requiredOnCreate":false,"editableOnCreate":true,"editableOnUpdate":true,"selfServiceEdit":false,"selfRegistrationEdit":false,"defaultValue":"","computedExpression":"","validationRegex":"","validationMessage":"","allowedValues":"","minLength":null,"maxLength":null,"sectionName":"Contact","columnSpan":2,"hidden":false,"registrationSectionName":null,"registrationColumnSpan":null,"registrationDisplayOrder":null,"selfServiceSectionName":null,"selfServiceColumnSpan":null,"selfServiceDisplayOrder":null},
  {"attributeName":"postalCode","customLabel":"Postal Code","inputType":"TEXT","requiredOnCreate":false,"editableOnCreate":true,"editableOnUpdate":true,"selfServiceEdit":true,"selfRegistrationEdit":true,"defaultValue":"","computedExpression":"","validationRegex":"","validationMessage":"","allowedValues":"","minLength":null,"maxLength":null,"sectionName":"Contact","columnSpan":2,"hidden":false,"registrationSectionName":null,"registrationColumnSpan":null,"registrationDisplayOrder":null,"selfServiceSectionName":null,"selfServiceColumnSpan":null,"selfServiceDisplayOrder":null},
  {"attributeName":"title","customLabel":"Job Title","inputType":"TEXT","requiredOnCreate":false,"editableOnCreate":true,"editableOnUpdate":true,"selfServiceEdit":true,"selfRegistrationEdit":true,"defaultValue":"","computedExpression":"","validationRegex":"","validationMessage":"","allowedValues":"","minLength":null,"maxLength":null,"sectionName":"Organization","columnSpan":3,"hidden":false,"registrationSectionName":null,"registrationColumnSpan":null,"registrationDisplayOrder":null,"selfServiceSectionName":null,"selfServiceColumnSpan":null,"selfServiceDisplayOrder":null},
  {"attributeName":"ou","customLabel":"Organizational Unit","inputType":"TEXT","requiredOnCreate":false,"editableOnCreate":true,"editableOnUpdate":true,"selfServiceEdit":false,"selfRegistrationEdit":false,"defaultValue":"","computedExpression":"","validationRegex":"","validationMessage":"","allowedValues":"","minLength":null,"maxLength":null,"sectionName":"Organization","columnSpan":3,"hidden":false,"registrationSectionName":null,"registrationColumnSpan":null,"registrationDisplayOrder":null,"selfServiceSectionName":null,"selfServiceColumnSpan":null,"selfServiceDisplayOrder":null},
  {"attributeName":"o","customLabel":"Organization","inputType":"TEXT","requiredOnCreate":false,"editableOnCreate":true,"editableOnUpdate":true,"selfServiceEdit":false,"selfRegistrationEdit":false,"defaultValue":"","computedExpression":"","validationRegex":"","validationMessage":"","allowedValues":"","minLength":null,"maxLength":null,"sectionName":"Organization","columnSpan":3,"hidden":false,"registrationSectionName":null,"registrationColumnSpan":null,"registrationDisplayOrder":null,"selfServiceSectionName":null,"selfServiceColumnSpan":null,"selfServiceDisplayOrder":null},
  {"attributeName":"departmentNumber","customLabel":"Department Number","inputType":"TEXT","requiredOnCreate":false,"editableOnCreate":true,"editableOnUpdate":true,"selfServiceEdit":false,"selfRegistrationEdit":false,"defaultValue":"","computedExpression":"","validationRegex":"","validationMessage":"","allowedValues":"","minLength":null,"maxLength":null,"sectionName":"Organization","columnSpan":3,"hidden":false,"registrationSectionName":null,"registrationColumnSpan":null,"registrationDisplayOrder":null,"selfServiceSectionName":null,"selfServiceColumnSpan":null,"selfServiceDisplayOrder":null},
  {"attributeName":"manager","customLabel":"Manager","inputType":"DN_LOOKUP","requiredOnCreate":false,"editableOnCreate":true,"editableOnUpdate":true,"selfServiceEdit":false,"selfRegistrationEdit":false,"defaultValue":"","computedExpression":"","validationRegex":"","validationMessage":"","allowedValues":"","minLength":null,"maxLength":null,"sectionName":"Organization","columnSpan":6,"hidden":false,"registrationSectionName":null,"registrationColumnSpan":null,"registrationDisplayOrder":null,"selfServiceSectionName":null,"selfServiceColumnSpan":null,"selfServiceDisplayOrder":null},
  {"attributeName":"description","customLabel":"Description","inputType":"TEXTAREA","requiredOnCreate":false,"editableOnCreate":true,"editableOnUpdate":true,"selfServiceEdit":true,"selfRegistrationEdit":true,"defaultValue":"","computedExpression":"","validationRegex":"","validationMessage":"","allowedValues":"","minLength":null,"maxLength":null,"sectionName":"Organization","columnSpan":6,"hidden":false,"registrationSectionName":null,"registrationColumnSpan":null,"registrationDisplayOrder":null,"selfServiceSectionName":null,"selfServiceColumnSpan":null,"selfServiceDisplayOrder":null},
  {"attributeName":"userPassword","customLabel":"Password","inputType":"PASSWORD","requiredOnCreate":true,"editableOnCreate":true,"editableOnUpdate":false,"selfServiceEdit":false,"selfRegistrationEdit":false,"defaultValue":"","computedExpression":"","validationRegex":"","validationMessage":"","allowedValues":"","minLength":null,"maxLength":null,"sectionName":"Account","columnSpan":6,"hidden":false,"registrationSectionName":null,"registrationColumnSpan":null,"registrationDisplayOrder":null,"selfServiceSectionName":null,"selfServiceColumnSpan":null,"selfServiceDisplayOrder":null}
]
JSON

profile_body() {
  local name="$1" ou="$2"
  jq -n --arg name "$name" --arg ou "$ou" \
     --arg special '!@#$%^&*' --argjson attrs "$ATTR_CONFIGS" '{
    name: $name, description: "", targetOuDn: $ou,
    objectClassNames: ["inetOrgPerson"], rdnAttribute: "uid",
    showDnField: true, enabled: true, selfRegistrationAllowed: false,
    passwordLength: 16, passwordUppercase: true, passwordLowercase: true,
    passwordDigits: true, passwordSpecial: true, passwordSpecialChars: $special,
    emailPasswordToUser: false, autoIncludeGroups: false, excludeAutoIncludes: false,
    additionalProfileIds: [], attributeConfigs: $attrs, groupAssignments: []
  }'
}

prof_created=0; prof_skipped=0; prof_failed=0
for spec in "${PROFILES[@]}"; do
  IFS='|' read -r dirDisplay pname targetOu <<<"$spec"
  id="$(id_for_display "$dirDisplay")"
  if [ -z "$id" ]; then
    echo "==> profile '$pname' — directory '$dirDisplay' not found, skipping."
    prof_failed=$((prof_failed + 1)); continue
  fi

  existing_p="$(curl -sS -b "$COOKIES" "$api/directories/$id/profiles")"
  if jq -e --arg n "$pname" 'any(.[]?; .name == $n)' >/dev/null 2>&1 <<<"$existing_p"; then
    echo "==> profile '$pname' on $dirDisplay — already exists, skipping."
    prof_skipped=$((prof_skipped + 1)); continue
  fi

  body="$(profile_body "$pname" "$targetOu")"
  resp="$(mktemp)"
  code="$(curl -sS -b "$COOKIES" -o "$resp" -w '%{http_code}' \
    -X POST "$api/directories/$id/profiles" \
    -H 'Content-Type: application/json' -d "$body")"
  case "$code" in
    200|201)
      echo "==> profile '$pname' on $dirDisplay — created (id $(jq -r '.id // "?"' "$resp"))  targetOuDn=$targetOu"
      prof_created=$((prof_created + 1))
      ;;
    *)
      echo "==> profile '$pname' on $dirDisplay — FAILED (HTTP $code): $(jq -r '.detail // .message // .' "$resp" 2>/dev/null | head -c 300)"
      prof_failed=$((prof_failed + 1))
      ;;
  esac
  rm -f "$resp"
done

# ── Summary ─────────────────────────────────────────────────────────────────
echo
echo "==> Done."
echo "    directories: created=$created skipped=$skipped failed=$failed"
echo "    entries:     created=$ent_created skipped=$ent_skipped failed=$ent_failed (non-fatal)"
echo "    profiles:    created=$prof_created skipped=$prof_skipped failed=$prof_failed"
# Entry failures are non-fatal (e.g. missing IBM secAuthority schema); the run
# fails only on directory or profile errors.
[ "$failed" -eq 0 ] && [ "$prof_failed" -eq 0 ]
