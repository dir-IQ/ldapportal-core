# LDAP Portal

A self-hosted web portal for managing LDAP directories — browse, search, and
edit entries, provision users and groups through configurable profiles with
approval workflows, run bulk CSV/LDIF imports, and keep multiple directories
(OpenLDAP, Active Directory, Oracle Unified Directory) under one pane of glass
with drift detection, audit trails, and operational reports.

The backend is Spring Boot 3.5 / Java 21; the frontend is Vue 3 + Vite +
Tailwind. Everything in this repository is Apache-2.0.

## Features

- **Directory management** — multiple `DirectoryConnection`s with pooling,
  TLS, capability probing, and vendor-aware behavior (OpenLDAP, AD, OUD).
- **Provisioning profiles** — attribute templates, DIT placement rules, and
  approval workflows that gate who may create/update/delete what.
- **Self-service** — end users authenticate against the directory to manage
  their own profile and reset passwords.
- **Bulk operations** — CSV user/group import/export and LDIF import with a
  server-side diff preview before anything is applied.
- **Directory sync & drift** — scheduled reconciliation with changelog
  readers (`cn=changelog`, slapo-accesslog) and Entra ID sync.
- **Observability** — Prometheus/OTLP metrics via Actuator, structured JSON
  logging, SIEM export. See [docs/observability.md](docs/observability.md).
- **IBM Security Verify Access (ISVA)** — optional addon that provisions
  secUser overlays alongside portal accounts. See
  [docs/isva-integration.md](docs/isva-integration.md).

## Quick start (Docker Compose)

```bash
cp .env.example .env    # fill in the required secrets (see file comments)
docker compose up -d db app frontend
```

Then open <http://localhost:9080> and sign in with the bootstrap superadmin
credentials you set in `.env`. The compose file can also stand up sample
OpenLDAP and Samba AD directories for experimentation; `make help` lists the
developer redeploy targets.

The three secrets in `.env` are mandatory — the app refuses to start without
`ENCRYPTION_KEY`, `JWT_SECRET`, and `BOOTSTRAP_SUPERADMIN_PASSWORD` (and a
`DB_PASSWORD`/`POSTGRES_PASSWORD`), by design.

## Repository layout

| Path | Contents |
|------|----------|
| `core/` | The application library: controllers, services, LDAP layer, JPA entities, Flyway migrations. |
| `addons/isva/` | Optional Apache-2.0 ISVA integration module. |
| `distribution/community` | Runnable fat JAR: core only. |
| `distribution/community-plus-isva` | Runnable fat JAR: core + ISVA addon. |
| `frontend/` | Vue 3 SPA (Vite, Pinia, Tailwind; Vitest + Playwright tests). |
| `docker/`, `compose.yaml`, `Makefile` | Container images and the local stack. |
| `deploy/` | Terraform (AWS ECS) and Kustomize (EKS) deployment configs. |
| `docs/` | Design docs, operations guides, deployment walkthroughs. |
| `testdata/` | Generated LDIF fixtures and their generator scripts. |

## Building and testing

```bash
./mvnw -pl core test                      # backend unit + integration tests
./mvnw package                            # build all modules + runnable JARs
(cd frontend && npm ci && npm run test:unit)   # frontend unit tests
(cd frontend && npm run e2e:smoke)        # Playwright E2E (needs Docker)
```

Backend integration tests that use Testcontainers (and the E2E suite's
backing services) require a Docker daemon. See
[docs/testing.md](docs/testing.md) for the full testing story.

## Deploying

- Docker images: multi-stage builds under `docker/` (backend) and
  `frontend/Dockerfile`, published to GHCR by CI.
- AWS ECS via Terraform: [docs/deployment-aws.md](docs/deployment-aws.md)
- EKS via Kustomize (+ GitLab/ECR): [docs/deployment-eks-gitlab-ecr.md](docs/deployment-eks-gitlab-ecr.md)
- Fly.io: [docs/deployment-fly.md](docs/deployment-fly.md)
- Declarative bootstrap (directories/admins as config): [docs/iac/README.md](docs/iac/README.md)

## Documentation

Start with the [admin quick-start guide](docs/admin-quick-start-guide.md) and
the [setup wizard walkthrough](docs/setup-wizard.md). Architecture decisions
live under [docs/architecture/](docs/architecture/), frontend conventions in
[docs/frontend-conventions.md](docs/frontend-conventions.md), and API
compatibility guarantees in [docs/api-compatibility.md](docs/api-compatibility.md).

## Security

Please report vulnerabilities privately — see [SECURITY.md](SECURITY.md).

## License

[Apache License 2.0](LICENSE). Third-party notices are listed in
[THIRD-PARTY-LICENSES](THIRD-PARTY-LICENSES).
