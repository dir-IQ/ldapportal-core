# Self-hosting LDAPPortal on GitLab (fork, build, deploy internally)

- **Date:** 2026-06-01
- **Status:** Not started (reference guide, 2026-06-01).
- **Audience:** A customer who wants to fork this repo into their own GitLab,
  build it on GitLab CI, publish images to their GitLab Container Registry, and
  deploy internally. Self-contained; paths are relative to the repo root.
- **Companion file:** a ready-to-adapt starter pipeline lives at
  [`.gitlab-ci.yml`](../.gitlab-ci.yml).

> The repo is **Apache-2.0** (`LICENSE`) — `core` *and* `addons/isva`. You may
> fork, modify, build, and run the **community** and **community-plus-isva**
> editions internally with no license key and no call-home. The commercial
> `ee` module is not part of this repository.

## 1. Licensing & editions — what they can build

- `distribution/community` — `core` only (Apache-2.0).
- `distribution/community-plus-isva` — `core` + `addons/isva` (Apache-2.0).
- `distribution/commercial` (the `ee` module) is **not in this repo**, so there
  is nothing to license-gate. The entitlement / signed-license-JWT machinery
  only governs `ee`; community/isva builds run without any license server.
- Two guardrails worth **keeping** (don't delete them when adapting CI):
  - `scripts/check-addons-license-headers.sh` — fails the build if a file under
    `addons/` loses its `// SPDX-License-Identifier: Apache-2.0` first line.
  - the community-bundle ee-leak scans (`npm run scan:community-bundle` for the
    frontend; the `verify-no-ee-bytecode` antrun check in
    `distribution/community/pom.xml` for the JAR) — both assert no commercial
    code leaks into a community artifact.

## 2. Toolchain prerequisites (CI runners / build images)

| Component | Requirement |
|---|---|
| Backend | **JDK 21** (Temurin). Multi-module Maven reactor; `./mvnw` wrapper, or preinstalled `mvn` in a `maven:3.9-eclipse-temurin-21` image. |
| Frontend | **Node 20** (CI baseline; `package.json` engines allow `^20.19 || >=22.12`; the frontend image builds on `node:22-alpine`). npm + Vite. |
| Tests | Backend tests use in-memory **H2** (PostgreSQL-compat, Flyway disabled) — **no Postgres needed in CI**. Postgres is only needed at **runtime**. |

There are no GitHub-specific Maven repositories in `pom.xml`; dependencies
resolve from **Maven Central** and the **npm registry** under normal config.

## 3. Dependency mirroring (the main work in a locked-down network)

Point the build at the customer's internal proxies:

- **Maven Central** → Nexus/Artifactory mirror via `~/.m2/settings.xml`
  (`<mirror>`). Note the **Maven wrapper itself** downloads a Maven distribution
  from a URL in `.mvn/wrapper/maven-wrapper.properties` — repoint it, or use a
  preinstalled `mvn` (the starter pipeline does the latter, matching upstream
  CI which avoids the wrapper bootstrap download).
- **npm registry** → `.npmrc` `registry=` override. CI uses `npm ci`, so the
  committed `frontend/package-lock.json` must be honored.
- **Container base images** to mirror into their registry:
  `eclipse-temurin:21-jre-alpine`, `node:22-alpine`, `nginx:alpine`,
  `postgres:16.x-alpine`. The Dockerfiles also run `apk upgrade` → needs an
  **Alpine package mirror** in an air-gapped build.
- **Playwright browsers** (only if they run e2e: `npm run e2e:install` fetches
  Chromium) — needs a mirror, or they skip e2e.
- The directory-server images in `compose.yaml` (`osixia/openldap`,
  `openidentityplatform/opendj`, `nowsci/samba-domain`, `elestio/pgadmin`) are
  **dev/test only** — production points at their real directories instead.

## 4. CI: map GitHub Actions → GitLab CI

The `.github/workflows/` files don't run on GitLab. Mapping:

| GitHub workflow | What it does | GitLab equivalent |
|---|---|---|
| `ci.yml` | backend `mvn -T 1C test` (JDK 21) + SPDX check; frontend `typecheck` / `lint:a11y` / `test:unit` / `build` / `build:community` + ee-leak scan | Jobs in the **`test`** stage of `.gitlab-ci.yml` (provided). |
| `ghcr-publish.yml` | on `v*` tags: build community + isva JARs and the frontend image; push to GHCR | **`package`** + **`publish`** stages → push to **GitLab Container Registry** (`$CI_REGISTRY_IMAGE`). |
| `release.yml` | publish `core` to **Maven Central** (GPG-signed) | **Drop**, unless they want `core` in their **GitLab Maven Package Registry** (then repoint `distributionManagement` / the `-Prelease` profile in `core/pom.xml`). |
| `trivy-scan.yml` | container vuln scan | Keep Trivy as a job, or use GitLab **Container Scanning**. |
| `dependabot-automerge.yml` | Dependabot automerge | GitHub-only → GitLab **Dependency Scanning** + Renovate. |
| `.gitleaks.toml` | secret scan config | GitLab built-in **Secret Detection**, or keep gitleaks as a job. |

The change-detection guard job in `ci.yml` (docs-only PRs skip the build) is a
GitHub branch-protection workaround; on GitLab use `rules:changes:` instead (the
starter keeps it simple and runs the suite on every branch/MR).

## 5. Container images & the build-order gotcha

- The **backend** Dockerfiles (`docker/community/Dockerfile`,
  `docker/community-plus-isva/Dockerfile`) **`COPY` a host-built JAR** from
  `distribution/<edition>/target/` — they are *not* multi-stage Maven builds.
  So the pipeline must **`mvn package` first** (producing the JAR as a CI
  artifact), **then `docker build`**. The starter does this across two stages.
- The **frontend** image *is* self-contained (node build → nginx).
  `frontend/Dockerfile` is a single portable image — an envsubst nginx
  template whose backend host is supplied at runtime via `BACKEND_APP` (see
  `frontend/nginx.conf.template`). The same image runs on docker-compose
  (`BACKEND_APP=app`, the default), Kubernetes/EKS (the backend Service
  name), and Fly (`<backend-app>.flycast`). Publish this image directly.
- Image refs move from `ghcr.io/<owner>/ldapportal-*` →
  `$CI_REGISTRY_IMAGE/ldapportal-*`. CI auth is the predefined
  `$CI_REGISTRY_USER` / `$CI_REGISTRY_PASSWORD` (job token) — no PAT to manage.
- The `publish` stage needs a **Docker-capable runner** (docker-in-docker /
  privileged, or Kaniko if privileged isn't allowed).

## 6. Runtime / deploy

- **Postgres is required.** Flyway runs migrations at startup; Hibernate is
  `validate`-only. App listens on **:8080**, health at **`/actuator/health`**.
- **Required secrets** (`.env.example`, no defaults) — put in GitLab CI/CD
  variables or their secrets manager, never in the repo:
  - `ENCRYPTION_KEY` — AES-256, `openssl rand -base64 32`
  - `JWT_SECRET` — `openssl rand -base64 64`
  - `BOOTSTRAP_SUPERADMIN_PASSWORD` (+ `BOOTSTRAP_SUPERADMIN_USERNAME`)
  - `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD`
  - optional `APP_*` toggles and `LOG_LEVEL` (see `.env.example`).
- **Frontend → backend wiring:** the frontend nginx proxies the API to the
  backend; set the target for their topology (compose service `app:8080`, a k8s
  Service, or an ingress route).
- **`compose.yaml`** is a working **dev** stack (app, frontend, postgres,
  pgadmin, and several directory servers for testing). For production, reduce it
  to **app + frontend + postgres** and point at the real directories.
  `terraform/aws` + `terraform/deployment-aws.md` / `deployment-fly.md` are
  reference deployment patterns to adapt to their infra (k8s, Nomad, etc.).

## 7. Staying current with upstream

Keep this repo as a read-only **upstream remote** and merge release tags
periodically rather than doing a one-time copy. Keep the local delta small and
isolated — ideally just `.gitlab-ci.yml`, mirror/registry config (`settings.xml`
/ `.npmrc`), and deploy manifests — so upstream merges stay clean.

## 8. What to drop / not carry over

- `release.yml` and the Maven Central publishing path (unless hosting `core`
  internally, per §4).
- `dependabot-automerge.yml` and `.github/` workflows generally.
- GHCR-specific image names and the `GITHUB_TOKEN`-based login.
