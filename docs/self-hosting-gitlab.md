# Self-hosting LDAPPortal on GitLab (fork, build, publish, deploy internally)

- **Date:** 2026-06-23
- **Status:** In progress (starter pipeline ported to full parity; per-fork
  registry/runner/mirror wiring remains, 2026-06-23).
- **Audience:** A team forking this repo into their own GitLab to build it on
  GitLab CI, publish images to their **GitLab Container Registry** (and,
  optionally, `core` + addons to their **GitLab Maven Package Registry**), and
  deploy internally. Self-contained; paths are relative to the repo root.
- **Companion files:**
  - [`.gitlab-ci.yml`](../.gitlab-ci.yml) — the full pipeline described here.
  - [`renovate.json`](../renovate.json) — dependency automation (Dependabot port).
  - [`deploy/aws/eks/gitlab-ci.ecr-eks.example.yml`](../deploy/aws/eks/gitlab-ci.ecr-eks.example.yml)
    + [`deployment-eks-gitlab-ecr.md`](deployment-eks-gitlab-ecr.md) — an
    ECR/EKS publish+deploy variant.

> The repo is **Apache-2.0** (`LICENSE`) — `core` *and* `addons/isva`. You may
> fork, modify, build, and run the **community** and **community-plus-isva**
> editions internally with no license key and no call-home.

> **Scope decisions baked into the committed pipeline** (see §4, §13):
> Maven **Central** publishing is **dropped** (no GPG/Central secrets).
> Container images go to the **GitLab Container Registry**, not GHCR.
> Internal Maven publishing is repointed to the **GitLab Maven Package
> Registry**. Dependabot is replaced by **Renovate**.

---

## 1. Licensing & editions — what you can build

- `distribution/community` — `core` only (Apache-2.0).
- `distribution/community-plus-isva` — `core` + `addons/isva` (Apache-2.0).
- Both build and run with **no license key, no call-home, and no license
  server** — there is nothing to license-gate.
- Two guardrails worth **keeping** (don't delete them when adapting CI):
  - `scripts/check-addons-license-headers.sh` — fails the build if a file under
    `addons/` loses its `// SPDX-License-Identifier: Apache-2.0` first line.
    (Wired into `backend-test`.)
  - the community-bundle boundary scans (`npm run scan:community-bundle` for the
    frontend; the bytecode-boundary antrun check in
    `distribution/community/pom.xml` for the JAR) — both assert the community
    artifact stays inside its Apache-2.0 module boundary. (Wired into
    `frontend-community-build` and `package-backend` respectively.)

## 2. Toolchain prerequisites (CI runners / build images)

| Component | Requirement |
|---|---|
| Backend | **JDK 21** (Temurin). Multi-module Maven reactor; the pipeline uses a preinstalled `mvn` from `maven:3.9-eclipse-temurin-21` (matching upstream CI, which avoids the wrapper bootstrap download). |
| Frontend | **Node 20** (CI baseline; `package.json` engines allow `^20.19 \|\| >=22.12`; the frontend image builds on `node:22-alpine`). npm + Vite. |
| Tests | Backend tests use in-memory **H2** (PostgreSQL-compat, Flyway disabled) — **no Postgres in CI**. Postgres is only needed at **runtime**. |
| Image jobs | The `security` (Trivy) and `publish` (image) jobs need a **Docker-capable runner**: Docker-in-Docker (privileged) or Kaniko. Tag a privileged runner and pin the jobs to it if your instance segregates runners. |

There are no GitHub-specific Maven repositories in `pom.xml`; dependencies
resolve from **Maven Central** and the **npm registry** under normal config.

## 3. Dependency mirroring (the main work in a locked-down network)

Point the build at the team's internal proxies:

- **Maven Central** → Nexus/Artifactory mirror via `~/.m2/settings.xml`
  (`<mirror>`). Mount it into the `.maven` jobs (CI/CD file/variable, or a
  runner-baked image). The **Maven wrapper** would download a Maven distribution
  from `.mvn/wrapper/maven-wrapper.properties` — the pipeline sidesteps this by
  using the preinstalled `mvn` in the build image; if you switch to `./mvnw`,
  repoint that URL.
- **npm registry** → `.npmrc` `registry=` override. CI uses `npm ci`, so the
  committed `frontend/package-lock.json` must be honored.
- **Container base images** to mirror into the registry:
  `eclipse-temurin:21-jre-alpine`, `node:22-alpine`, `nginx:alpine`,
  `postgres:16.x-alpine`, plus the CI images this pipeline pulls
  (`maven:3.9-eclipse-temurin-21`, `node:20`, `docker:27`, `docker:27-dind`,
  `zricethezav/gitleaks`). The Dockerfiles also run `apk upgrade` → needs an
  **Alpine package mirror** in an air-gapped build.
- **Trivy DB + the trivy binary**: `trivy-scan` installs Trivy
  (`raw.githubusercontent.com/.../install.sh`) and downloads the vuln DB
  (`ghcr.io/aquasecurity/trivy-db`) at runtime — mirror both, or swap in GitLab
  **Container Scanning** (§7).
- **Renovate**: if self-hosting the Renovate runner, it needs egress to the
  registries it queries (or your mirrors) — see §8.
- **Playwright browsers** (only if you run e2e: `npm run e2e:install` fetches
  Chromium) — needs a mirror, or skip e2e (not in the starter pipeline).
- The directory-server images in `compose.yaml` (`osixia/openldap`,
  `openidentityplatform/opendj`, `nowsci/samba-domain`, `elestio/pgadmin`) are
  **dev/test only** — production points at the real directories.

## 4. CI: map GitHub Actions → GitLab CI

The `.github/workflows/` files don't run on GitLab. `.gitlab-ci.yml` is a parity
port. Mapping:

| GitHub workflow | What it does | In `.gitlab-ci.yml` |
|---|---|---|
| `ci.yml` | backend `mvn -T 1C test` + addons SPDX check; frontend `typecheck` / `lint:a11y` / `test:unit` / `build` / `build:community` + bundle scan | **`test`** stage: `backend-test` + the five `frontend-*` jobs. |
| `trivy-scan.yml` | build JAR → image → scan HIGH/CRITICAL, fail on findings | **`security`** stage: `trivy-build-jar` → `trivy-scan`. |
| `.gitleaks.toml` | secret-scan allowlist/config | **`security`** stage: `gitleaks` job (reuses the same `.gitleaks.toml`). |
| `ghcr-publish.yml` | on `v*`: build edition JARs + frontend image; push to GHCR | **`package`** + **`publish`** stages → **GitLab Container Registry** (`$CI_REGISTRY_IMAGE`). |
| `publish-internal.yml` | publish `core`+addons to **GitHub Packages** (Maven) | **`publish`** stage: `publish-maven` (manual) → **GitLab Maven Package Registry** via the `gitlab-publish` profile. See §6. |
| `release.yml` | publish to **Maven Central** (GPG-signed) | **Dropped.** No `release:` job, no GPG/Central secrets. (Re-add only if you genuinely need Central — out of scope here.) |
| `dependabot-automerge.yml` + `.github/dependabot.yml` | Dependabot + patch automerge | **`renovate.json`** (§8). GitHub-only files stay inert on GitLab. |

### 4.1 Stages & what gates them

```
test ──▶ security ──▶ package ──▶ publish
(MR + default branch)            (tags v* only; publish-maven is manual)
```

- `test` + `security` run on **merge requests** and the **default branch**.
- `package` + the image `publish-*` jobs run **only on `v*` tags**.
- `publish-maven` is **manual** (a "play" button) on `v*` tags and the default
  branch — the inner-loop, on-demand Maven publish.

### 4.2 One pipeline per change (`workflow:` + `interruptible`)

The top-level `workflow:rules` run **one** pipeline per change — MR pipelines for
branches with an open MR, branch pipelines for the default branch, tag pipelines
for releases — and suppress the duplicate branch pipeline that otherwise runs
beside an MR pipeline. Consequence: **a feature branch with no MR yet runs no
pipeline** (it starts when you open the MR). This is the standard MR-only dedup
pattern.

`default: interruptible: true` is GitLab's analogue of GitHub's
`concurrency: cancel-in-progress`. **Turn on "Auto-cancel redundant pipelines"**
in *Settings → CI/CD → General pipelines* for it to take effect. The `package` /
`publish` jobs set `interruptible: false` so an in-flight release push is never
cancelled.

### 4.3 Change detection (`rules:changes:` — replaces the `github-script` guard)

`ci.yml` uses an `actions/github-script` job calling `pulls.listFiles` to skip
the build on docs-only PRs — a workaround for GitHub branch-protection treating a
non-firing workflow as "no check". GitLab has no such constraint, so the pipeline
uses native **`rules:changes:`** (the `.rules-backend` / `.rules-frontend` /
`.rules-code` anchors, pulled into jobs via `!reference`):

- **Default branch always runs the full suite** — the canonical green signal,
  never narrowed.
- **On an MR**, a job runs only if the MR touches its tree (`rules:changes`
  diffs against the MR target). A frontend-only MR skips the backend job and
  vice-versa; docs-only / deploy-only MRs skip both.
- This is *slightly* coarser than `ci.yml`'s "ambient changes (Dockerfiles,
  root configs) trigger both" rule. If you want a shared infra change to force
  the whole suite on an MR, add the path to **both** `.rules-backend` and
  `.rules-frontend` (`.gitlab-ci.yml` is already in both).

### 4.4 Maven caching (and why there's no build-cache job)

The pipeline caches the Maven **dependency repo** (`.m2/repository`, relocated
into `$CI_PROJECT_DIR` because GitLab only caches paths under the project dir).

It deliberately does **not** persist the per-module *build* cache: the
`maven-build-cache-extension` (`.mvn/extensions.xml`) is present but **disabled**
via `.mvn/maven.config` (`-Dmaven.build.cache.enabled=false`), matching upstream
CI. So `.m2/repository` is the only Maven cache worth keeping. **To opt in:**
remove that flag (or pass `-Dmaven.build.cache.enabled=true`) **and** point the
cache at the project dir (it defaults to `~/.m2/build-cache`, which GitLab can't
cache) via `-Dmaven.build.cache.location=$CI_PROJECT_DIR/.m2/build-cache`, then
add that path to the job `cache:`.

## 5. Container images & the build-order gotcha

- The **backend** Dockerfiles (`docker/community/Dockerfile`,
  `docker/community-plus-isva/Dockerfile`) **`COPY` a host-built JAR** from
  `distribution/<edition>/target/` — they are *not* multi-stage Maven builds. So
  the pipeline **`mvn package` first** (`package-backend`, producing the JAR as a
  CI artifact), **then `docker build`** (`publish-backend`). The `security` stage
  does the same for the scan (`trivy-build-jar` → `trivy-scan`).
- The **frontend** image *is* self-contained (node build → nginx).
  `frontend/Dockerfile` is a single portable image — an envsubst nginx template
  whose backend host is supplied at runtime via `BACKEND_APP` (see
  `frontend/nginx.conf.template`). The same image runs on docker-compose
  (`BACKEND_APP=app`, the default), Kubernetes/EKS (the backend Service name),
  and Fly (`<backend-app>.flycast`). Publish it directly.
- **Image refs** move from `ghcr.io/<owner>/ldapportal-*` →
  `$CI_REGISTRY_IMAGE/ldapportal-*`. CI auth is the predefined
  `$CI_REGISTRY_USER` / `$CI_REGISTRY_PASSWORD` (job token) — **no PAT to
  manage**. Enable the **Container Registry** on the project (*Settings →
  General → Visibility*).
- **Tag scheme** mirrors `ghcr-publish.yml`'s `docker/metadata-action`: a
  `vX.Y.Z` tag publishes `:X.Y.Z` (immutable), `:X.Y` (moving minor), and
  `:latest`.
- **Optional — exercise the publish path without a release** (the GHCR
  `workflow_dispatch` equivalent): add a manual, non-tag rule to the image jobs.
  Note this also needs `package-backend` to run, so give it the same extra rule:

  ```yaml
  rules:
    - if: '$CI_COMMIT_TAG =~ /^v[0-9]/'
    - if: '$CI_PIPELINE_SOURCE != "merge_request_event"'
      when: manual
      allow_failure: true
  ```

  and branch the tag logic on `$CI_COMMIT_TAG` being empty (tag
  `manual-$CI_COMMIT_SHORT_SHA`, don't move `:latest`). Left out of the starter
  to keep the package/publish rules simple — tagging a `v0.0.0-rc1` pre-release
  exercises the same path.

## 6. Publishing `core` + addons to the GitLab Maven Package Registry

The GitLab analogue of `publish-internal.yml`. Use it so a **downstream**
(commercial/`ee`) build can resolve a pre-release pin of `core`/`addons` from a
registry instead of building them from source.

**Moving parts:**
1. **`pom.xml` `gitlab-publish` profile** (next to `internal-publish`). It points
   `distributionManagement` at
   `${env.CI_API_V4_URL}/projects/${env.CI_PROJECT_ID}/packages/maven` and reuses
   the same `flattenMode=ossrh` + source-jar so the published artifact resolves
   identically to a Central one. It has **no `<activation>`** — it is dormant
   unless you pass `-Pgitlab-publish`, so it never perturbs ordinary
   `test`/`package` builds. (Activation is by explicit `-P`, *not*
   `env.GITLAB_CI`, precisely so the flatten/source steps don't run on every
   GitLab build.)
2. **`publish-maven` job.** Manual. It writes a `ci_settings.xml` whose
   `gitlab-maven` server (id matches the profile's repository id) carries a
   `Job-Token: ${CI_JOB_TOKEN}` HTTP header, optionally stamps `PUBLISH_VERSION`,
   then runs:

   ```bash
   mvn -s ci_settings.xml -Pgitlab-publish -DskipTests -pl core,addons/isva deploy
   ```

**Auth needs no secret** — `CI_JOB_TOKEN` is predefined. Enable the **Package
Registry** on the project. To run it: open *CI/CD → Pipelines*, press ▶ on
`publish-maven`, and (optionally) set the **`PUBLISH_VERSION`** variable on the
run (e.g. `0.1.5-rc1` for an immutable pin, or `0.1.5-SNAPSHOT` to overwrite).
Leaving it blank publishes the pom's current version.

**Consuming downstream** (in the `ee` repo's `pom.xml` / `settings.xml`):

```xml
<repository>
  <id>gitlab-maven</id>
  <url>https://gitlab.example.com/api/v4/projects/<core-project-id>/packages/maven</url>
</repository>
```

with a `<server>` using a `Private-Token` / `Deploy-Token` / `CI_JOB_TOKEN`
header. For cross-project `CI_JOB_TOKEN` access, allow the downstream project in
*Settings → CI/CD → Token Access* of the `core` project.

## 7. Security scanning

The starter keeps the upstream tools as jobs (faithful to the curated configs):

- **`gitleaks`** — runs `gitleaks detect --no-git --source .` with the repo's
  `.gitleaks.toml` (its built-in-rules + the verified allowlist), emitting a
  SARIF artifact and failing on any finding.
- **`trivy-scan`** — builds the community image and scans for HIGH/CRITICAL
  (with or without an upstream fix), failing the job and uploading a table
  report. Locks the "0 HIGH/CRITICAL" floor.

**GitLab-native alternatives** (if you prefer the MR security widget / Vulnerability
Report over raw artifacts), swap either job for a template `include:`:

```yaml
include:
  - template: Security/Secret-Detection.gitlab-ci.yml
  - template: Security/Container-Scanning.gitlab-ci.yml   # scans a built image by ref
```

Container Scanning scans an image **already in a registry**, so it fits *after*
`publish-*` (post-tag) rather than pre-merge; the inline Trivy job above is what
gives you pre-merge coverage. (Note: the upstream `trivy-scan.yml` produces a
table-format artifact + job-log summary, **not** a SARIF/Security-tab upload —
so there is nothing GitHub-Security-tab-specific to reproduce here.)

## 8. Dependency automation: Dependabot → Renovate

`renovate.json` ports `.github/dependabot.yml` + `dependabot-automerge.yml`:

- **Ecosystems**: Maven, npm, Dockerfile/Compose, and GitLab CI image tags (the
  analogue of Dependabot's `github-actions`).
- **Grouping** (one PR per wave): `spring`, `maven test-tools`,
  `maven production-deps`; `vue-stack`, `npm test-tools`, `npm production-deps`;
  `docker-images`. *Renovate is **last-match-wins** (Dependabot is first-match),
  so the catch-all `production-deps` rules are listed **before** the specific
  groups, which then override them — don't reorder without re-checking this.*
- **Major bumps suppressed** (`matchUpdateTypes: ["major"] → enabled:false`),
  mirroring Dependabot's `ignore: semver-major`. Security advisories override it
  via `vulnerabilityAlerts` + `osvVulnerabilityAlerts`.
- **Patch/pin/digest auto-merge** (`platformAutomerge`), mirroring
  `dependabot-automerge.yml`. GitLab merges once the pipeline passes — so set up
  protected-branch "pipelines must succeed" (§9) for the gate to mean anything.
- **Schedule**: Monday morning, `America/Chicago` (matches Dependabot).

**Running it on GitLab:** either add the project to a hosted Renovate
(e.g. Mend) app, or self-host the `renovate/renovate` runner as a scheduled
pipeline (a separate project or a `schedule:`-triggered job with a bot token).
`renovate.json` is inert until a Renovate bot reads it.

**Coexistence:** `renovate.json` (GitLab) and `.github/dependabot.yml` (GitHub)
read on different platforms and don't collide. Keep Dependabot running on GitHub
until you decommission it; only then delete the `.github/` files.

## 9. Repo governance (GitLab project settings, not in-repo)

- **Protected branches** + **"Pipelines must succeed"** (and "Skipped pipelines
  are considered successful" → **off**, so a `rules`-skipped required job doesn't
  false-pass) — *Settings → Merge requests* + *Settings → Repository → Protected
  branches*. This replaces GitHub branch-protection required checks.
- **Merge request approvals** — *Settings → Merge requests → Approvals*.
- **CODEOWNERS** — none today. To add on GitLab, put it at `.gitlab/CODEOWNERS`
  (or repo root); wire it to approval rules.
- **MR / issue templates** — add under `.gitlab/merge_request_templates/` and
  `.gitlab/issue_templates/` (GitLab-only paths; inert on GitHub).
- **Releases (optional).** No GitHub Releases automation exists upstream to port.
  If you want GitLab Releases off the same `v*` tag, add:

  ```yaml
  release:
    stage: publish
    image: registry.gitlab.com/gitlab-org/release-cli:latest
    rules:
      - if: '$CI_COMMIT_TAG =~ /^v[0-9]/'
    needs: ["publish-backend", "publish-frontend"]
    script: ["echo release $CI_COMMIT_TAG"]
    release:
      tag_name: '$CI_COMMIT_TAG'
      name: 'Release $CI_COMMIT_TAG'
      description: 'Automated release for $CI_COMMIT_TAG.'
  ```

## 10. CI/CD variables

**Predefined — nothing to create:** `CI_REGISTRY`, `CI_REGISTRY_USER`,
`CI_REGISTRY_PASSWORD`, `CI_REGISTRY_IMAGE` (Container Registry auth + name),
`CI_JOB_TOKEN`, `CI_API_V4_URL`, `CI_PROJECT_ID` (Maven publish),
`CI_COMMIT_TAG`, `CI_DEFAULT_BRANCH`, `CI_PIPELINE_SOURCE`.

**Create as masked/protected** (*Settings → CI/CD → Variables*) only what your
fork actually uses:

| Variable | When you need it |
|---|---|
| `PUBLISH_VERSION` | Optional, set **per manual run** of `publish-maven` (don't store it). |
| `MAVEN_MIRROR` / npm registry creds | Only if your internal mirrors require auth (§3). |
| `AWS_REGION`, `ECR_REGISTRY`, `AWS_ROLE_ARN`, `EKS_CLUSTER` | Only for the ECR/EKS variant (`deploy/aws/eks/gitlab-ci.ecr-eks.example.yml`). |
| `ENCRYPTION_KEY`, `JWT_SECRET`, `BOOTSTRAP_SUPERADMIN_PASSWORD`, `POSTGRES_*` | Only if the **pipeline deploys** the app; otherwise set them on the deploy target (§11). |

**Not needed (dropped):** `CENTRAL_TOKEN_USERNAME` / `CENTRAL_TOKEN_PASSWORD` /
`GPG_PRIVATE_KEY` / `GPG_PASSPHRASE` (Maven Central) and any GHCR/`GITHUB_TOKEN`
wiring — the container registry and Maven publish both use the job token.

## 11. Runtime / deploy

- **Postgres is required.** Flyway runs migrations at startup; Hibernate is
  `validate`-only. App listens on **:8080**, health at **`/actuator/health`**.
- **Required runtime secrets** (`.env.example`, no defaults) — GitLab CI/CD
  variables or your secrets manager, never in the repo:
  - `ENCRYPTION_KEY` — AES-256, `openssl rand -base64 32`
  - `JWT_SECRET` — `openssl rand -base64 64`
  - `BOOTSTRAP_SUPERADMIN_PASSWORD` (+ `BOOTSTRAP_SUPERADMIN_USERNAME`)
  - `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD`
  - optional `APP_*` toggles and `LOG_LEVEL` (see `.env.example`).
- **Frontend → backend wiring:** the frontend nginx proxies the API to the
  backend; set `BACKEND_APP` for your topology (compose service `app:8080`, a
  k8s Service, or an ingress route).
- **`compose.yaml`** is a working **dev** stack (app, frontend, postgres,
  pgadmin, and directory servers for testing). For production reduce to **app +
  frontend + postgres** and point at the real directories. The
  [`deployment-aws.md`](deployment-aws.md) / [`deployment-fly.md`](deployment-fly.md)
  runbooks (and the [EKS+ECR GitLab variant](deployment-eks-gitlab-ecr.md)) are
  patterns to adapt to your infra (k8s, Nomad, etc.).

## 12. Staying current with upstream

Keep this repo as a read-only **upstream remote** and merge release tags
periodically rather than a one-time copy. Keep the local delta small and isolated
— ideally `.gitlab-ci.yml`, `renovate.json`, the `gitlab-publish` pom profile,
mirror/registry config (`settings.xml` / `.npmrc`), and deploy manifests — so
upstream merges stay clean.

### Dual-running GitHub + GitLab (mirror window)

If you mirror the repo and keep both platforms live during migration:

- **Idempotent jobs** (test, build, scan) double-run harmlessly.
- **Side-effecting publish jobs are the hazard.** A `v*` tag fires *both*
  platforms' publish paths. With Central dropped here, the remaining overlap is
  the container registries (GHCR vs GitLab) and the Maven registries (GitHub
  Packages vs GitLab) publishing the same coordinates. **Pick one owner per
  target during overlap** — the clean lever is *where the credentials live*, not
  YAML guards. The simplest split: GitHub keeps publishing to GHCR until cutover;
  GitLab runs CI-only (its publish jobs are tag-gated and `publish-maven` is
  manual, so they stay dormant unless you tag / press play).

## 13. What to drop / not carry over

- **`release.yml` + the Maven Central path** — dropped (no GPG/Central secrets).
- **`dependabot-automerge.yml` + `.github/dependabot.yml`** — replaced by
  `renovate.json`; the `.github/` files stay inert on GitLab (delete only after
  GitHub is decommissioned).
- **`.github/workflows/` generally** — GitLab never executes them.
- **GHCR image names + the `GITHUB_TOKEN`/`docker/login-action` login** —
  replaced by `$CI_REGISTRY_IMAGE` + the job-token registry login.
- **The `internal-publish` (GitHub Packages) pom profile** — leave it in place
  (it's harmless and keeps the GitHub path working during overlap); the parallel
  `gitlab-publish` profile is what GitLab uses.
