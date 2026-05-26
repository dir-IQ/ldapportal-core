#!/usr/bin/env bash
# Phase 5b core-snapshot manifest + leak scan (dry-run helper).
#
# Computes the file list that would seed the PUBLIC `ldapportal-core` repo —
# tracked files only (git ls-files), minus the commercial/secret deny-list —
# and scans that set for anything that must never reach a public Apache-2.0
# repo. Reversible: by default it only reports; pass --materialize <dir> to
# copy the manifest into a staging tree for a real extraction dry-run.
#
#   ./scripts/5b-core-snapshot.sh                 # report + scan
#   ./scripts/5b-core-snapshot.sh --materialize /tmp/ldapportal-core
#
# Exit non-zero if the scan finds a leak. Frontend counterpart of the JAR
# `verify-no-ee-bytecode` check; see docs/5b-repo-split.md §5.
set -euo pipefail

MATERIALIZE=""
[ "${1:-}" = "--materialize" ] && MATERIALIZE="${2:?--materialize needs a target dir}"

# ── LICENSE/SECRET-CRITICAL deny-list ────────────────────────────────────────
# These MUST be excluded from public core. Unambiguous; enforced + scanned.
# (Editorial trims — docs curation, fly configs, CLAUDE.md, ee e2e specs,
#  terraform — are a separate decision; see the doc / PR discussion.)
DENY=(
  # ── license/secret-critical (a leak here is a real problem) ──
  'ee/'                       # commercial module (code + LICENSE-EE)
  'distribution/commercial/'  # commercial assembly
  'frontend/src/ee/'          # commercial frontend
  'dev/'                      # dev signing keypair (PRIVATE KEY) + ENTERPRISE license
  'stale-docs/'               # historical; CLAUDE.md says never reference

  # ── editorial (product/ops decisions; confirmed 2026-05) ──
  # NOT denied (kept in core): terraform/ (community provider), ISVA e2e specs
  # (ISVA is the Apache addon), examples/, testdata/, scripts/, .github/
  # (except the 3 ee-harness-coupled workflows denied at the tail below).
  #
  # Commercial / strategy / governance docs (community operator+tech docs kept):
  'docs/edition-boundary.md'
  'docs/enterprise-roadmap.md'
  'docs/core-ee-refactor-plan.md'
  'docs/5b-repo-split.md'
  'docs/product-overview.md'
  'docs/licensing-runbook.md'
  'docs/compliance-reports.md'
  'docs/cross-campaign-report.md'
  'docs/feature-2.'           # feature-2.1..2.5 (governance specs)
  'docs/superpowers/'         # per-feature ee spec/plan artefacts
  'docs/ux-backlog.md'
  # Internal ops / dev:
  '.claude/'
  'CLAUDE.md'
  'GraalJS_INTEGRATION.plan'
  'Dockerfile'                # root image builds the COMMERCIAL JAR by default
  'docker-compose.yml'        # commercial-default stack
  'fly/'
  'app-c.fly.toml'
  'app-ci.fly.toml'
  'app-e.fly.toml'
  'frontend-c.fly.toml'
  'frontend-ci.fly.toml'
  'frontend-e.fly.toml'
  'ldap-acmecorp.fly.toml'
  'ldap-globex.fly.toml'
  # ee-feature e2e specs (governance/hybrid/hr/alerting/events):
  'frontend/tests/e2e/spec/access-reviews.spec.ts'
  'frontend/tests/e2e/spec/alerting.spec.ts'
  'frontend/tests/e2e/spec/cross-directory-search.spec.ts'
  'frontend/tests/e2e/spec/hr-hire.spec.ts'
  'frontend/tests/e2e/spec/hr-termination.spec.ts'
  'frontend/tests/e2e/spec/outbound-events.spec.ts'
  # ee-test-harness-coupled CI: these run `mvn -pl ee` / spring-boot:test-run
  # against the e2e Testcontainers harness (TestcontainersConfiguration,
  # application-e2e.yml) which lives in ee/src/test (denied via 'ee/' above) —
  # they can't run in core until that harness is relocated core-ward. See
  # docs/5b-repo-split.md ("e2e/axe CI in core").
  '.github/workflows/e2e-smoke.yml'
  '.github/workflows/e2e-full.yml'
  '.github/workflows/a11y.yml'
  # Internal dir-IQ fly.io deploy: targets specific apps (ldapportal-c/ci/e),
  # the enterprise edition, and per-tenant LDAPs, using the deny-listed
  # *.fly.toml configs + dir-IQ fly secrets. Useless and broken in public core.
  '.github/workflows/deploy-fly.yml'
)

is_denied() {
  local f="$1" d
  for d in "${DENY[@]}"; do
    [[ "$f" == "$d"* ]] && return 0
  done
  return 1
}

# ── Compute manifest (tracked files minus deny-list) ─────────────────────────
mapfile -t TRACKED < <(git ls-files)
MANIFEST=()
EXCLUDED=()
for f in "${TRACKED[@]}"; do
  if is_denied "$f"; then EXCLUDED+=("$f"); else MANIFEST+=("$f"); fi
done

printf 'tracked files: %d | core manifest: %d | excluded: %d\n' \
  "${#TRACKED[@]}" "${#MANIFEST[@]}" "${#EXCLUDED[@]}"
echo "excluded by deny-list (top-level breakdown):"
printf '%s\n' "${EXCLUDED[@]}" | cut -d/ -f1 | sort | uniq -c | sed 's/^/  /'

# ── Leak scan over the manifest ──────────────────────────────────────────────
# Each content check runs as a SINGLE repo-wide `git grep`, then keeps only the
# hits that are in the manifest. A per-file `git grep -- "$f"` loop here spawned
# ~2 git processes per manifest file (~1750 total) — a few seconds on a plain
# box, but minutes-to-hours behind a corporate AV/EDR that hooks every process
# spawn (reads as a hang). One pass per pattern is ~70x faster and identical:
# git grep over the whole tree also matches deny-listed files (ee/, dev/), but
# those aren't in the manifest, so the membership filter drops them.
leaks=0
report() { echo "[LEAK] $1"; leaks=$((leaks + 1)); }

declare -A IN_MANIFEST
for f in "${MANIFEST[@]}"; do IN_MANIFEST["$f"]=1; done

# 1. No commercial source path slipped through the deny-list (pure bash, no fork).
for f in "${MANIFEST[@]}"; do
  case "$f" in
    */com/ldapportal/ee/*|com/ldapportal/ee/*) report "ee source path: $f" ;;
  esac
done

# 2. No commercial SPDX *header* — line-anchored to a comment prefix, so a
#    prose mention of the identifier (e.g. THIRD-PARTY-LICENSES documenting
#    the dual-license scheme) is not a false positive.
while IFS= read -r f; do
  [[ -n "${IN_MANIFEST[$f]:-}" ]] && report "commercial SPDX header: $f"
done < <(git grep -lIE '^[[:space:]]*(//|#|\*|<!--)[[:space:]]*SPDX-License-Identifier: LicenseRef-LDAPPortal-Commercial' 2>/dev/null || true)

# 3. No embedded private key — a real PEM block header at line start. PEM
#    *parsing* code (`.replace("-----BEGIN PRIVATE KEY-----", "")`) and public
#    keys ("PUBLIC KEY") are correctly ignored.
while IFS= read -r f; do
  [[ -n "${IN_MANIFEST[$f]:-}" ]] && report "embedded private key: $f"
done < <(git grep -lIE '^-----BEGIN ([A-Z]+ )?PRIVATE KEY-----' 2>/dev/null || true)

# NOTE (not a leak): in the monorepo the root pom.xml legitimately lists every
# module. The snapshot/extraction step must adapt it to drop the `ee` and
# `distribution/commercial` modules. Surfaced so it isn't forgotten.
if git grep -qE '<module>(ee|distribution/commercial)</module>' -- pom.xml 2>/dev/null; then
  echo "[NOTE] pom.xml lists commercial modules — adapt (drop ee, distribution/commercial) when materializing the snapshot."
fi

if [ "$leaks" -gt 0 ]; then
  echo "FAIL — $leaks leak(s) in the core manifest."
  exit 1
fi
echo "OK — core manifest is free of commercial code, commercial SPDX, and private keys."

# ── Optional: materialize the snapshot + apply core-repo transforms ──────────
# The manifest is the monorepo's files verbatim; a few must be transformed so
# the public ldapportal-core repo builds the COMMUNITY edition standalone:
#   - root pom.xml: drop the ee + distribution/commercial modules (their dirs
#     are deny-listed, so leaving the <module> entries would break the build);
#   - frontend/vite.config.js: default VITE_EDITION to `community` (there is no
#     commercial edition in the public repo — src/ee isn't present);
#   - inject .github/workflows/release.yml (Maven Central publish on tag).
# These run only on the staging copy; the monorepo is never modified.
if [ -n "$MATERIALIZE" ]; then
  echo "materializing ${#MANIFEST[@]} files -> $MATERIALIZE"
  mkdir -p "$MATERIALIZE"
  # Copy the whole manifest with one tar-to-tar pipe instead of a mkdir + cp
  # per file (~2 forks/file) — the same process-spawn cost the leak scan used to
  # pay. tar recreates parent dirs and is POSIX-ubiquitous (unlike rsync); the
  # NUL-delimited file list (--null -T -) is safe for any path.
  printf '%s\0' "${MANIFEST[@]}" | tar -cf - --null -T - | tar -xf - -C "$MATERIALIZE"

  echo "transform: drop ee + distribution/commercial modules from pom.xml"
  sed -i -E '/<module>(ee|distribution\/commercial)<\/module>/d' "$MATERIALIZE/pom.xml"

  echo "transform: default frontend edition -> community"
  sed -i "s/|| 'commercial'/|| 'community'/" "$MATERIALIZE/frontend/vite.config.js"

  # vite already aliases @/ee -> src/ee-shim.js for the community *build*, but
  # vue-tsc resolves via tsconfig paths, where @/ee would fall through @/* to the
  # excluded src/ee and fail typecheck (TS2307). Map @/ee + @/ee/* to the shim
  # here so typecheck passes against the stub. Community-only — must NOT live in
  # the monorepo tsconfig, which still needs real src/ee types for the commercial
  # build.
  echo "transform: map @/ee -> src/ee-shim.js in frontend/tsconfig.json"
  sed -i 's#^\([[:space:]]*\)"paths": {#\1"paths": {\n\1  "@/ee": ["./src/ee-shim.js"],\n\1  "@/ee/*": ["./src/ee-shim.js"],#' "$MATERIALIZE/frontend/tsconfig.json"

  # The root Dockerfile (commercial image) is deny-listed; core ships the
  # community image under docker/community/. Point the Trivy scan at it so the
  # `docker build` step finds a Dockerfile. The job's `mvn clean package` already
  # yields distribution/community/target/*.jar in this tree (commercial excluded).
  echo "transform: trivy scans docker/community/Dockerfile (root Dockerfile is commercial)"
  sed -i 's#-f Dockerfile #-f docker/community/Dockerfile #' "$MATERIALIZE/.github/workflows/trivy-scan.yml"

  echo "transform: inject .github/workflows/release.yml"
  mkdir -p "$MATERIALIZE/.github/workflows"
  cat > "$MATERIALIZE/.github/workflows/release.yml" <<'YAML'
# Publishes com.ldapportal:ldapportal-core to Maven Central.
# TEMPLATE — validate end-to-end during the Phase 5b staging dry-run (the exact
# GPG / Central secret wiring can't be exercised until the repo + secrets exist).
# The -Prelease profile (flatten + sign + central-publishing) lives in core/pom.xml.
# Requires repo secrets: CENTRAL_TOKEN_USERNAME, CENTRAL_TOKEN_PASSWORD,
# GPG_PRIVATE_KEY, GPG_PASSPHRASE. autoPublish=false leaves a droppable staging
# deployment to verify before releasing.
#
# Versioning: the pom carries a permanent -SNAPSHOT for day-to-day dev, but a
# -SNAPSHOT deploy routes to Central's *snapshots* repo (separate endpoint, extra
# gating, not what we publish). So this workflow stamps a RELEASE version before
# deploying — from the vX.Y.Z tag, or the manual `version` input — and refuses to
# deploy a SNAPSHOT.
name: Release (Maven Central)
on:
  push:
    tags: ['v*']
  workflow_dispatch:
    inputs:
      version:
        description: "Release version, e.g. 0.0.1 (manual runs only; tag runs derive it from the vX.Y.Z tag)"
        required: false
        default: ''
jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: temurin
          cache: maven
          server-id: central
          server-username: CENTRAL_TOKEN_USERNAME
          server-password: CENTRAL_TOKEN_PASSWORD
          gpg-private-key: ${{ secrets.GPG_PRIVATE_KEY }}
          gpg-passphrase: MAVEN_GPG_PASSPHRASE
      - name: Determine release version
        id: ver
        run: |
          if [ "${{ github.event_name }}" = "push" ]; then
            V="${GITHUB_REF_NAME#v}"          # refs/tags/v0.0.1 -> 0.0.1
          else
            V="${{ github.event.inputs.version }}"
          fi
          if [ -z "$V" ] || printf '%s' "$V" | grep -q 'SNAPSHOT'; then
            echo "::error::No release version resolved (got '$V'). Push a vX.Y.Z tag or supply the 'version' input — refusing to deploy a SNAPSHOT (it 403s against Central's snapshots repo)."
            exit 1
          fi
          echo "version=$V" >> "$GITHUB_OUTPUT"
      - name: Stamp release version
        run: mvn -B -ntp versions:set -DnewVersion=${{ steps.ver.outputs.version }} -DgenerateBackupPoms=false
      - name: Deploy core to Central (staging)
        run: mvn -B -ntp -Prelease -DskipTests -pl core deploy
        env:
          CENTRAL_TOKEN_USERNAME: ${{ secrets.CENTRAL_TOKEN_USERNAME }}
          CENTRAL_TOKEN_PASSWORD: ${{ secrets.CENTRAL_TOKEN_PASSWORD }}
          MAVEN_GPG_PASSPHRASE: ${{ secrets.GPG_PASSPHRASE }}
YAML

  echo "done. NOTE: review $MATERIALIZE/.github/workflows/ (release.yml is a template;"
  echo "      the monorepo ci.yml is copied as-is and works once ee/commercial are dropped)."
fi
