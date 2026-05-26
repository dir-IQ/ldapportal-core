# Fly.io demo deployment runbook

Steps to stand up an LDAP Portal demo on Fly.io with **all three
distributions side-by-side**. Auto-scales to zero when idle
(~$8/month resting), wakes on demand for prospects.

Public URLs (each distribution gets its own):

| Distribution         | URL                                  | Use case                                                                |
|----------------------|--------------------------------------|-------------------------------------------------------------------------|
| Community            | https://ldapportal-c.fly.dev          | Apache-2.0 baseline; "what you get for free"                            |
| Community + ISVA     | https://ldapportal-ci.fly.dev         | Apache-2.0 + ISVA full-mode integration (still no governance / HR / alerting) |
| Enterprise           | https://ldapportal-e.fly.dev          | Full feature set; sales conversations                                    |

Stack (8 Fly apps + 3 Postgres clusters):

| App                          | Role                                        |
|------------------------------|---------------------------------------------|
| `ldapportal-c`                | Community frontend (public)                 |
| `ldapportal-c-app`            | Community backend (private 6PN only)        |
| `ldapportal-ci`               | Community+ISVA frontend (public)            |
| `ldapportal-ci-app`           | Community+ISVA backend (private 6PN only)   |
| `ldapportal-e`                | Enterprise frontend (public)                |
| `ldapportal-e-app`            | Enterprise backend (private 6PN only)       |
| `ldapportal-ldap-acmecorp`    | Seeded LDAP test directory (shared)         |
| `ldapportal-ldap-globex`      | Seeded LDAP test directory (shared)         |
| `ldapportal-db-c` (Fly Postgres)  | Community-edition DB                    |
| `ldapportal-db-ci` (Fly Postgres) | Community+ISVA DB                       |
| `ldapportal-db-e` (Fly Postgres)  | Enterprise-edition DB                   |

LDAP test directories are shared because the seed fixture is the
same for every distribution. Postgres is per-distribution so a
prospect's session in one demo can't see / affect data from
another demo.

GitHub Actions handles every deploy after the one-time setup below
(`.github/workflows/deploy-fly.yml`, manually triggered).

## Prerequisites

- [Install `flyctl`](https://fly.io/docs/flyctl/install/) locally.
- Fly.io account with billing enabled (the trial covers the first
  ~$5/month).
- Admin on the GitHub repo (for setting the Actions secret).

## One-time setup

### 1. Create the Fly apps

```bash
flyctl auth login

# Community-edition apps
flyctl apps create ldapportal-c-app
flyctl apps create ldapportal-c

# Community+ISVA apps
flyctl apps create ldapportal-ci-app
flyctl apps create ldapportal-ci

# Enterprise-edition apps
flyctl apps create ldapportal-e-app
flyctl apps create ldapportal-e

# Shared LDAP test directories
flyctl apps create ldapportal-ldap-acmecorp
flyctl apps create ldapportal-ldap-globex
```

If any name is taken (the public ones likely are), pick alternatives
and update them in the matching `*.fly.toml` (at the repo root) and
in `.github/workflows/deploy-fly.yml`.

### 2. Provision Postgres clusters and attach to each backend

```bash
# Community DB
flyctl postgres create \
  --name ldapportal-db-c \
  --region iad \
  --initial-cluster-size 1 \
  --vm-size shared-cpu-1x \
  --volume-size 1
flyctl postgres attach ldapportal-db-c --app ldapportal-c-app

# Community+ISVA DB
flyctl postgres create \
  --name ldapportal-db-ci \
  --region iad \
  --initial-cluster-size 1 \
  --vm-size shared-cpu-1x \
  --volume-size 1
flyctl postgres attach ldapportal-db-ci --app ldapportal-ci-app

# Enterprise DB
flyctl postgres create \
  --name ldapportal-db-e \
  --region iad \
  --initial-cluster-size 1 \
  --vm-size shared-cpu-1x \
  --volume-size 1
flyctl postgres attach ldapportal-db-e --app ldapportal-e-app
```

`attach` sets a `DATABASE_URL` secret on each backend, but the
LDAP Portal Spring Boot config wants `DB_URL` / `DB_USERNAME` /
`DB_PASSWORD` as three separate values. Two ways to recover the
URL parts post-attach — pick whichever matches your state:

**Path A — pre-deploy (no machine exists yet): detach + re-attach.**
The `attach` command prints the URL on stdout, so re-running it
surfaces the credentials. Run for each edition (replay for
`ldapportal-db-e` / `ldapportal-e-app`):

```bash
flyctl postgres detach ldapportal-db-c --app ldapportal-c-app
flyctl postgres attach ldapportal-db-c --app ldapportal-c-app
```

In the output, look for a line like:

```
Setting DATABASE_URL=postgres://USER:PASS@ldapportal-db-c.flycast:5432/DBNAME
```

**Path B — post-deploy (machine exists, even if crash-looping):
SSH and printenv.** Once a backend has been deployed at least once
the machine inherits its `DATABASE_URL` secret, and you can read
it directly. Useful if `DB_URL` is set but wrong (e.g. missing the
`sslmode=disable` suffix) and you'd rather rewrite it than walk
through detach/re-attach:

```bash
flyctl ssh console --app ldapportal-c-app -C 'sh -c "printenv DATABASE_URL"'
```

This prints the same `postgres://USER:PASS@…:5432/DBNAME` line.

Either way, copy `USER`, `PASS`, and `DBNAME` into:

```bash
flyctl secrets set --app ldapportal-c-app \
  DB_URL="jdbc:postgresql://ldapportal-db-c.flycast:5432/<DBNAME>?sslmode=disable" \
  DB_USERNAME="<USER>" \
  DB_PASSWORD="<PASS>"
```

The `?sslmode=disable` suffix is **required**. Fly Managed Postgres
on the `.flycast` private endpoint doesn't speak TLS — the 6PN
network itself is private + encrypted, so the cluster runs Postgres
unencrypted on the wire. pgjdbc defaults to attempting TLS; without
`sslmode=disable` the handshake fails with `SSL error: Remote host
terminated the handshake` and Flyway can't initialise.

The host is always `<postgres-app-name>.flycast` (Fly's internal
cluster DNS), so only the database name, user, and password vary
per attach. Repeat the detach + attach + secrets-set sequence for
`ldapportal-ci-app` against `ldapportal-db-ci` and for
`ldapportal-e-app` against `ldapportal-db-e`.

### 3. Set each backend's runtime secrets

`BOOTSTRAP_SUPERADMIN_PASSWORD` is the one secret you'll need to
type later (first superadmin login). Generate it into a variable so
the value is visible on the console before `flyctl` consumes it.

**CRITICAL:** pipe every `openssl rand -base64 …` through
`tr -d '\r\n'`. `openssl` always appends a newline, and on
Windows / Git Bash the CRLF (`\r\n`) gets embedded in the secret.
The first time the backend tries to decode the value it throws
`IllegalArgumentException: Illegal base64 character d` (`d` is the
hex of `\r`) — login fails immediately, and rotating just the
visibly-broken secret still leaves siblings poisoned by the same
copy/paste chain. Stripping at set time is the only reliable fix.

```bash
for APP in ldapportal-c-app ldapportal-ci-app ldapportal-e-app; do
  BOOTSTRAP_PW=$(openssl rand -base64 16 | tr -d '\r\n')
  echo "  $APP BOOTSTRAP_SUPERADMIN_PASSWORD = $BOOTSTRAP_PW"
  flyctl secrets set --app "$APP" \
    ENCRYPTION_KEY="$(openssl rand -base64 32 | tr -d '\r\n')" \
    JWT_SECRET="$(openssl rand -base64 64 | tr -d '\r\n')" \
    BOOTSTRAP_SUPERADMIN_PASSWORD="$BOOTSTRAP_PW"
done
```

Copy each printed `BOOTSTRAP_SUPERADMIN_PASSWORD` somewhere safe
(password manager) — you'll need them for the first superadmin
login on each distribution. Once a permanent superadmin exists in
that distribution's DB the bootstrap is inert (it's a one-shot
insert; later `BOOTSTRAP_SUPERADMIN_PASSWORD` changes have no
effect until the existing row is deleted from `accounts`).

If you'd rather pick your own bootstrap password than wrangle the
generated one, swap the `BOOTSTRAP_PW=…` line for a literal string
— either works as long as it survives the openssl-redirection trap
(don't put a literal `openssl rand` call inside the `flyctl secrets
set` line; it executes silently and is consumed without ever being
visible).

### 4. ~~Provision volumes for the LDAP servers~~ (skip)

Fly machines support **only one mounted volume per machine**, and
osixia/openldap wants two (data + config). Rather than splitting,
the LDAP fly.toml files mount no volumes — the container re-seeds
from the baked-in bootstrap LDIFs on each cold start. ~30s for
acmecorp's 5000-user fixture, a couple of seconds for globex.

If you already ran the earlier `flyctl volumes create acmecorp_*`
/ `globex_*` commands (the runbook used to include them), destroy
them now — they'd otherwise sit unused on your bill:

```bash
for APP in ldapportal-ldap-acmecorp ldapportal-ldap-globex; do
  for VID in $(flyctl volumes list --app "$APP" --json | jq -r '.[].id'); do
    flyctl volumes destroy "$VID" --app "$APP" -y
  done
done
```

(If you skipped that step, this is already a no-op.)

If cold-start re-seeding turns out to be too slow for your demo
flow, switch the LDAP app(s) to always-on by setting
`min_machines_running = 1` in `ldap-acmecorp.fly.toml` /
`ldap-globex.fly.toml`. Cost is roughly $2/month per directory
kept warm.

### 5. Allocate a private IPv6 for each app on the private network

The frontends reach the backends at `<backend>.flycast:8080`, and the
backends reach the LDAP test directories at `<ldap>.flycast:389`.
`.flycast` hostnames only enter Fly's internal DNS when the target
app has a **private** IPv6 allocated — and `flyctl deploy` doesn't
auto-allocate one (it auto-allocates a *public* IPv6 instead). Skip
this step and you'll see one of two failures:

- `<host>.flycast could not be resolved (3: Host not found)`
  on the frontend → backend hop (nginx error log).
- `UnknownHostException(<host>.flycast: Name has no usable address)`
  on the backend → LDAP hop (wizard's Test Connection — the
  Java LDAP SDK can't resolve the address).

Allocate once per private app:

```bash
flyctl ips allocate-v6 --private --app ldapportal-c-app
flyctl ips allocate-v6 --private --app ldapportal-ci-app
flyctl ips allocate-v6 --private --app ldapportal-e-app
flyctl ips allocate-v6 --private --app ldapportal-ldap-acmecorp
flyctl ips allocate-v6 --private --app ldapportal-ldap-globex
```

Verify any one of them:

```bash
flyctl ips list --app ldapportal-c-app
# Expect a row of type `private_v6`.
```

The allocation persists across deploys; you only run this once
per app. The two Postgres clusters get a private endpoint
automatically when you `flyctl postgres attach`, so they don't
need an explicit allocation.

### 6. Add the GitHub Actions secret

Generate an **org-scoped** token. `flyctl tokens create deploy`
requires `--app <name>` and produces a single-app token; we need
one credential that covers all six apps, so use `create org` instead:

```bash
flyctl tokens create org --name "github-actions" -x 999999h
# Copy the long output string starting with 'FlyV1 fm2_…'.
```

In the GitHub repo:

```
Settings → Secrets and variables → Actions → New repository secret
  Name:  FLY_API_TOKEN
  Value: <the token from above>
```

The org token has access to **all** apps in your Fly org —
necessary because the workflow deploys six apps + scales each.
If the org later contains unrelated apps and you want a tighter
blast radius, swap for per-app deploy tokens
(`flyctl tokens create deploy --app <name>`), one GitHub secret
per app, and update the matching `env:` blocks in the workflow.

## First deploy

Trigger the workflow manually:

```
Actions → "Deploy to Fly.io" → Run workflow → leave "components" as "all"
```

Or via `gh`:

```bash
gh workflow run deploy-fly.yml -f components=all
```

The workflow runs six parallel jobs (two backends, two frontends,
two LDAP servers). Each `fly.toml` has `auto_stop_machines = "stop"`
+ `auto_start_machines = true` + `min_machines_running = 0`, so
Fly's proxy stops idle machines and wakes them on the next request
— idle cost stays at zero and the first hit to either public URL
brings the relevant frontend + backend back up.

Do **not** add a post-deploy `flyctl scale count 0` step: on modern
Fly Machines `scale count 0` destroys machines outright instead of
stopping them, leaving nothing for the proxy to wake (the URL just
returns `ERR_CONNECTION_CLOSED`).

After deploy:

- Community demo: <https://ldapportal-c.fly.dev>
- Enterprise demo: <https://ldapportal-e.fly.dev>
- LDAP servers: private-network-only at
  `ldapportal-ldap-acmecorp.flycast:389` and
  `ldapportal-ldap-globex.flycast:389`. We deliberately use
  `.flycast` rather than `.internal` so the LDAP machines can
  auto-wake on the first wizard probe — see the note in
  `ldap-acmecorp.fly.toml` for the routing rationale.

## Configure each demo

Repeat per edition (the two databases are independent, so each
needs its own first-run setup).

1. Visit the edition's URL — first hit wakes the frontend
   (cold start ~5s).
2. Log in with username `superadmin` / the
   `BOOTSTRAP_SUPERADMIN_PASSWORD` you set for that edition's backend.
3. Walk the first-run setup wizard. When it asks for a directory
   connection, use:

   | Field         | acmecorp value                                    | globex value                                       |
   |---------------|---------------------------------------------------|----------------------------------------------------|
   | Display Name  | `AcmeCorp (demo)`                                 | `Globex (demo)`                                    |
   | Type          | `OpenLDAP`                                        | `OpenLDAP`                                         |
   | Host          | `ldapportal-ldap-acmecorp.flycast`                 | `ldapportal-ldap-globex.flycast`                    |
   | Port          | `389`                                             | `389`                                              |
   | TLS           | None                                              | None                                               |
   | Bind DN       | `cn=admin,dc=acmecorp,dc=com`                     | `cn=admin,dc=globex,dc=com`                        |
   | Bind password | `admin`                                           | `admin`                                            |
   | Base DN       | `dc=acmecorp,dc=com`                              | `dc=globex,dc=com`                                 |

4. Save. The wizard probes the connection; you should see ~5,000
   users for acmecorp, ~250 for globex.

## Subsequent deploys

Triggered manually. Use the `components` input to deploy only what
changed:

```bash
# Frontend-only after a UI change, both editions
gh workflow run deploy-fly.yml -f components=frontend-c,frontend-e

# Whole community edition (backend + frontend) after a core change
gh workflow run deploy-fly.yml -f components=community

# Just the enterprise backend
gh workflow run deploy-fly.yml -f components=app-e

# Re-seed an LDAP server
gh workflow run deploy-fly.yml -f components=ldap-acmecorp
```

Accepted tokens: `all`, `community`, `community-plus-isva`,
`enterprise`, `app-c`, `frontend-c`, `app-ci`, `frontend-ci`,
`app-e`, `frontend-e`, `ldap-acmecorp`, `ldap-globex`.
Comma-separated.

## Cost expectations

Resting (everything stopped, only Postgres always-on):

| Item                                    | ~Cost (rest) | Notes                              |
|-----------------------------------------|--------------|------------------------------------|
| 6 main machines (3 backends, 3 frontends) | $0         | Auto-stop                          |
| 2 LDAP machines                         | $0           | Auto-stop; no volumes (re-seed)    |
| 3 Fly Postgres (`shared-cpu-1x`, 1GB)   | ~$8.40       | Always running per distribution    |
| HTTPS certs + bandwidth                 | $0           | Included                           |
| **Total resting**                       | **~$8.40/mo** |                                   |

Active demo overhead: ~$0.01/hour per running app, so 10 hours/mo
across the six distribution apps adds ~$0.60. Net under
**$10/month** for the three-distribution prospect-facing demo.

If cost matters more than carrying every distribution: drop the
community-plus-isva or community side (each is ~$3/mo with
Postgres). Keep enterprise alone and point community prospects at
the GitHub README.

## Tearing down

```bash
flyctl apps destroy ldapportal-c-app  -y
flyctl apps destroy ldapportal-c      -y
flyctl apps destroy ldapportal-ci-app -y
flyctl apps destroy ldapportal-ci     -y
flyctl apps destroy ldapportal-e-app  -y
flyctl apps destroy ldapportal-e      -y
flyctl apps destroy ldapportal-ldap-acmecorp -y
flyctl apps destroy ldapportal-ldap-globex   -y
flyctl postgres destroy ldapportal-db-c  -y
flyctl postgres destroy ldapportal-db-ci -y
flyctl postgres destroy ldapportal-db-e  -y
```

## Troubleshooting

**`deploy-app-ci` or `deploy-frontend-ci` reports `Could not find App
"ldapportal-ci-app"` / `"ldapportal-ci"`.** The community-plus-isva
demo stack is the newest one, and its Fly apps aren't created by
the deploy workflow — they're a one-time operator step. Run from
your laptop:

```bash
flyctl apps create ldapportal-ci-app
flyctl apps create ldapportal-ci

flyctl postgres create \
  --name ldapportal-db-ci \
  --region iad \
  --initial-cluster-size 1 \
  --vm-size shared-cpu-1x \
  --volume-size 1
flyctl postgres attach ldapportal-db-ci --app ldapportal-ci-app

flyctl ips allocate-v6 --private --app ldapportal-ci-app
```

Then run the secrets loop (`for APP in ldapportal-c-app ldapportal-ci-app
ldapportal-e-app …` — see "Set each backend's runtime secrets" above)
and the `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` extraction
(see "Provision Postgres clusters" → Path A or Path B). After
that, re-trigger the workflow.

As of the most recent workflow update, `deploy-app-ci` /
`deploy-frontend-ci` are **tolerant** of the apps not existing yet —
they call `flyctl status` first and skip with a GitHub Actions
warning instead of failing the whole `components=all` run. So you'll
see a yellow warning in the run summary until you provision them;
the rest of the deploy still succeeds. A targeted
`components=app-ci` run still produces the warning, just on its own
job.

**Cold start is slow (>15s).** Switch the backend's `auto_stop_machines`
from `"stop"` to `"suspend"` in `app-c.fly.toml` /
`app-ci.fly.toml` / `app-e.fly.toml`. Suspended machines resume
from RAM snapshot in ~1-2s but cost slightly more than stopped
ones. Apply per distribution — you can tune the cold demos for
cost and the hot one for speed.

**Frontend wakes but errors with `502 Bad Gateway` for ~10s.** The
backend is mid-cold-start. The frontend's nginx proxies retry
internally, but the first prospect to hit a fully-asleep stack
will see the gateway error briefly. Subsequent retries succeed.

**Backend wakes but errors with `could not connect to LDAP`.** The
LDAP servers' first wake takes 5-10s longer than the backend's. The
backend's connection pool retries — wait, then retry the action. If
it still fails after 30s, the LDAP server may have failed to seed;
check `flyctl logs --app ldapportal-ldap-acmecorp`.

**One edition's demo got corrupted; want a fresh DB.** Reset just
that edition's Postgres:

```bash
flyctl postgres destroy ldapportal-db-c -y
# Re-create per step 2 above, re-attach, re-set secrets.
gh workflow run deploy-fly.yml -f components=community
```

The other edition's demo is untouched.

**Want a fresh LDAP fixture for both editions.** Destroy the volume
and redeploy:

```bash
flyctl volumes list --app ldapportal-ldap-acmecorp
flyctl volumes destroy <id> --app ldapportal-ldap-acmecorp -y
gh workflow run deploy-fly.yml -f components=ldap-acmecorp
```

Both editions see the re-seeded directory because the LDAP servers
are shared.

**Custom domain.** Map `demo.example.com` to either edition's frontend:

```bash
flyctl certs create demo.example.com --app ldapportal-e
```

Add a CNAME from `demo.example.com` to `ldapportal-e.fly.dev`, then
update `CORS_ALLOWED_ORIGIN` on the enterprise backend to match:

```bash
flyctl secrets set --app ldapportal-e-app \
  CORS_ALLOWED_ORIGIN="https://demo.example.com"
```

**Bootstrap password got lost.** Resetting just
`BOOTSTRAP_SUPERADMIN_PASSWORD` is **not enough** — `BootstrapService`
is a one-shot `ApplicationRunner` that skips when any active LOCAL
superadmin already exists in `accounts`. To recover, delete the
existing row so the next start re-bootstraps:

```bash
flyctl secrets set BOOTSTRAP_SUPERADMIN_PASSWORD='<new-password>' \
  --app ldapportal-e-app
flyctl postgres connect --app ldapportal-db-e
# at the psql prompt:
\c ldapportal_e_app
DELETE FROM accounts WHERE role = 'SUPERADMIN' AND auth_type = 'LOCAL';
\q
flyctl machine restart --app ldapportal-e-app
```

The startup log will now show `Bootstrap: created LOCAL superadmin
[superadmin]` and the new password works. Default username is
`superadmin` (override with `BOOTSTRAP_SUPERADMIN_USERNAME`).

**Login fails with `Illegal base64 character d` (or any hex byte).**
One of the base64 secrets (`JWT_SECRET`, `ENCRYPTION_KEY`) was set
with a trailing `\r` — usually from `openssl rand -base64` piped
through a CRLF-aware shell. The hex code in the error message is
the offending byte (`d` = `0x0D` = `\r`). Re-set both secrets with
`tr -d '\r\n'`:

```bash
flyctl secrets set --app ldapportal-e-app \
  JWT_SECRET="$(openssl rand -base64 64 | tr -d '\r\n')" \
  ENCRYPTION_KEY="$(openssl rand -base64 32 | tr -d '\r\n')"
```

Rotating `ENCRYPTION_KEY` is destructive once you have LDAP
directories configured (their bind passwords are AES-encrypted with
it). On a fresh demo with no directories yet, it's a free rotation.
