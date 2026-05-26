# AWS deployment runbook

How to run LDAP Portal in a customer-managed AWS account. Targets
ECS Fargate as the closest "single-container app" model to the
Fly demo deployment, with RDS Postgres backing the audit log and
configuration store, an internet-facing ALB terminating TLS, and
Secrets Manager holding the application secrets.

The accompanying Terraform module lives at
[`terraform/aws/`](../terraform/aws/) and is the recommended
provisioning path. The runbook below explains both:

- **Path A — Terraform module.** One `terraform apply` from a
  ~30-line root config. The right choice when the customer's
  platform team already runs Terraform.
- **Path B — manual via console + CLI.** Read the same recipe,
  click through it. Useful for the platform team that wants to
  understand the moving parts before adopting the module.

Either path produces the same stack:

```
                       ┌─────────────────┐
  HTTPS (443) ─────────►   ALB           │  public subnets
                       │   ACM cert      │
                       └────────┬────────┘
                                │ HTTP 8080
                       ┌────────▼────────┐
                       │  ECS Fargate    │  private subnets
                       │  task (the app) │
                       └────┬───────┬────┘
                            │       │
              5432 (TLS)    │       │   reads on cold start
                 to RDS     │       │
                       ┌────▼───┐ ┌─▼──────────────┐
                       │  RDS   │ │ Secrets        │
                       │  PG 16 │ │ Manager        │
                       └────────┘ └────────────────┘
```

## Container images

The project publishes three flavours on GitHub Container Registry:

| Tag | Contents | When |
|---|---|---|
| `ghcr.io/dir-iq/ldapportal-community:<version>` | `core/` only. Apache-2.0. | Community baseline. |
| `ghcr.io/dir-iq/ldapportal-community-plus-isva:<version>` | `core/` + `addons/isva`. Apache-2.0. | OSS deployments that want the ISVA full-mode integration. |
| `ghcr.io/dir-iq/ldapportal-commercial:<version>` | `core/` + `ee/` + every addon. Proprietary licence. | Paid customers. |

The default in the Terraform module is the commercial tag; flip
`image_uri` to the appropriate ghcr.io tag for the build the
customer is licensed for.

**Pin to a digest, not a tag, in production.** `ghcr.io/…:1.0.0`
is a moving target if a security patch rebuilds the same version;
`ghcr.io/…@sha256:…` is immutable. Look up the digest with
`docker manifest inspect ghcr.io/dir-iq/ldapportal-commercial:1.0.0`.

### Pulling from inside the VPC

The default config pulls from ghcr.io over the public internet
(via the private subnets' NAT gateway). For sub-region latency
and to avoid the egress charges, set up an **ECR pull-through
cache** that mirrors ghcr.io into a private ECR repository:

```bash
aws ecr create-pull-through-cache-rule \
  --ecr-repository-prefix dir-iq \
  --upstream-registry-url ghcr.io \
  --credential-arn arn:aws:secretsmanager:…:secret:ecr-pullthroughcache/ghcr-XXXXXX
```

Then point `image_uri` at
`<account>.dkr.ecr.<region>.amazonaws.com/dir-iq/ldapportal-commercial:1.0.0`.
First pull populates the cache; subsequent pulls hit ECR
directly. No need for `image_pull_secret_arn` in this case —
the ECS task execution role's `AmazonECSTaskExecutionRolePolicy`
already grants ECR read.

## Prerequisites

- AWS account with **IAM permission to provision VPC SGs, RDS,
  ALB, ECS, Secrets Manager, IAM roles, CloudWatch Logs**. The
  Terraform identity the operator uses needs the union of these.
- **An existing VPC** with two public subnets (for the ALB) and
  two private subnets in distinct AZs (for the app + RDS). The
  private subnets need a NAT gateway or VPC endpoints for the
  container pull / Secrets Manager / CloudWatch Logs traffic.
  If the customer doesn't have a VPC, they create one first —
  this module deliberately doesn't.
- **An ACM certificate** in the target region, covering the
  hostname operators will use (`ldapportal.example.com` or
  similar).
- **A DNS zone** the operator controls, where they can point a
  CNAME / ALIAS record at the ALB.
- **`terraform` ≥ 1.6** locally (Path A) or **`aws` CLI v2**
  (both paths).

## Path A — Terraform

### 1. Author the root config

Create a `main.tf` somewhere the customer's platform team owns:

```hcl
provider "aws" {
  region = "us-east-1"   # whatever region matches the ACM cert + VPC
}

module "ldapportal" {
  source = "git::https://github.com/dir-IQ/ldapportal-core.git//terraform/aws?ref=v1.0.0"

  name     = "ldapportal"                          # prefix for all resources
  hostname = "ldapportal.example.com"              # the URL operators visit

  vpc_id             = "vpc-…"
  public_subnet_ids  = ["subnet-…", "subnet-…"]
  private_subnet_ids = ["subnet-…", "subnet-…"]
  certificate_arn    = "arn:aws:acm:us-east-1:…:certificate/…"

  # Pin to a digest in production
  image_uri = "ghcr.io/dir-iq/ldapportal-commercial:1.0.0"

  tags = {
    Environment = "prod"
    Owner       = "platform"
  }
}

output "alb_dns_name" { value = module.ldapportal.alb_dns_name }
output "alb_zone_id"  { value = module.ldapportal.alb_zone_id }
output "bootstrap_superadmin_secret_arn" {
  value = module.ldapportal.bootstrap_superadmin_secret_arn
}
```

Use the customer's normal state backend (S3 + DynamoDB lock,
Terraform Cloud, etc.). The module doesn't dictate.

### 2. Apply

```bash
terraform init
terraform plan -out=tfplan
terraform apply tfplan
```

First apply takes ~10 minutes: ~6 minutes for RDS, 1 minute for
the ALB, the rest for ECS + IAM + secrets. The ECS service
starts a task immediately, but it'll fail health-checks until
Flyway has run the initial schema migrations against the new
RDS instance — give it another 60-90 seconds after apply
returns.

### 3. Point DNS at the ALB

Route 53 ALIAS (recommended — no extra DNS hop):

```bash
aws route53 change-resource-record-sets --hosted-zone-id ZXXXXX \
  --change-batch '{
    "Changes": [{"Action": "UPSERT", "ResourceRecordSet": {
      "Name": "ldapportal.example.com",
      "Type": "A",
      "AliasTarget": {
        "HostedZoneId": "'"$(terraform output -raw alb_zone_id)"'",
        "DNSName": "'"$(terraform output -raw alb_dns_name)"'",
        "EvaluateTargetHealth": true
      }
    }}]
  }'
```

CNAME for a non-Route-53 zone:

```
ldapportal.example.com.   CNAME   <alb_dns_name>
```

### 4. Retrieve the bootstrap superadmin password

```bash
aws secretsmanager get-secret-value \
  --secret-id $(terraform output -raw bootstrap_superadmin_secret_arn) \
  --query SecretString --output text
```

The first start writes a LOCAL superadmin row into `accounts`
with this password. Once that row exists, the secret is inert
— changing it does nothing until the row is deleted (see
"Resetting the bootstrap superadmin" below).

### 5. First login

Browse to `https://ldapportal.example.com`. Username
`superadmin`, password from step 4. The first-run setup wizard
walks you through adding your first directory.

## Path B — manual

Walk through Path A's steps in the AWS console (or via aws-cli)
without Terraform. The same resources, the same names. Useful
when the platform team wants to understand the moving parts
before adopting the module — or for an emergency repair when
Terraform state is unavailable.

The order is the same:

1. VPC / subnets / NAT / ACM cert — customer's responsibility,
   independent of LDAP Portal.
2. Three security groups (alb, app, db) — see [`terraform/aws/network.tf`](../terraform/aws/network.tf)
   for the exact ingress / egress rules.
3. Four Secrets Manager entries — `ENCRYPTION_KEY`,
   `JWT_SECRET`, `BOOTSTRAP_SUPERADMIN_PASSWORD`, the DB password.
   Generate with `openssl rand -base64 32 | tr -d '\r\n'` etc.;
   the `tr` is critical — see the
   [Fly runbook's CRLF warning](deployment-fly.md#3-set-each-backends-runtime-secrets)
   for why. Same trap on every platform.
4. RDS Postgres 16.13 in the private subnets, attached to the
   db SG. Storage encrypted, deletion protection on, backup
   retention ≥ 7 days.
5. CloudWatch log group `/aws/ecs/ldapportal`.
6. ECS cluster, task definition, service — see
   [`terraform/aws/ecs.tf`](../terraform/aws/ecs.tf) for the
   container definition's env vars, secrets, and port mapping.
7. ALB with HTTPS listener using the ACM cert; target group with
   `/actuator/health` health check; HTTP → HTTPS redirect.
8. DNS record from your hostname to the ALB.

## Day-2 operations

### Rolling out a new image

```bash
# Bump the image_uri in your Terraform config, then either:
terraform apply

# …or, to skip the plan diff for a hotfix:
aws ecs update-service \
  --cluster ldapportal \
  --service ldapportal \
  --force-new-deployment
```

ECS pulls the new image (or the updated tag), spins up a new
task, drains the old one through the ALB, and reports `STEADY`.
~2-3 minutes end to end with `desired_count = 1` and the default
deployment percentages.

### Reading logs

```bash
aws logs tail /aws/ecs/ldapportal --follow --since 10m
```

Logs are JSON (Logback's `logstash-logback-encoder`). Pipe through
`jq` for filtering: `… | jq -r 'select(.level=="ERROR") | "\(.timestamp) \(.message)"'`.

### Scaling

The default is one task. For zero-downtime deploys or higher
throughput, bump `desired_count` in the Terraform input and
re-apply. ECS handles the rolling replacement; the ALB's
`deregistration_delay = 30` setting drains in-flight requests
before killing the old task.

LDAP Portal's outbound-event dispatcher uses `SKIP LOCKED` over the
event_outbox table, so multiple instances coordinate correctly
without a leader election step. Same for the audit-log writer.
There is **no shared in-memory state** between tasks that needs
sticky sessions, so a vanilla round-robin ALB is correct.

### Resetting the bootstrap superadmin

The bootstrap password is a one-shot insert — `BootstrapService`
skips when any active LOCAL superadmin already exists in
`accounts`. To recover when the operator has lost the password
*and* the existing superadmin row, delete the row and rotate the
secret:

```bash
# 1. Connect to the DB
DB_ENDPOINT=$(terraform output -raw rds_endpoint)
DB_PASSWORD=$(aws secretsmanager get-secret-value \
  --secret-id ldapportal/db-password --query SecretString --output text)

PGPASSWORD="$DB_PASSWORD" psql \
  -h ${DB_ENDPOINT%:*} -U ldapportal -d ldapportal \
  -c "DELETE FROM accounts WHERE role = 'SUPERADMIN' AND auth_type = 'LOCAL';"

# 2. Rotate the bootstrap secret
NEW_PW=$(openssl rand -base64 24 | tr -d '\r\n')
aws secretsmanager put-secret-value \
  --secret-id ldapportal/bootstrap-superadmin-password \
  --secret-string "$NEW_PW"
echo "New superadmin password: $NEW_PW"

# 3. Force-restart so the task picks up the new secret
aws ecs update-service --cluster ldapportal --service ldapportal \
  --force-new-deployment
```

### Rotating ENCRYPTION_KEY

**Warning:** rotating `ENCRYPTION_KEY` without re-encrypting the
`bind_password_encrypted` column on every row of
`directory_connections` will lock out every directory. The
service can't decrypt the existing ciphertext with the new key.

The supported rotation procedure is:

1. Start with both keys in scope — add the new key as
   `ENCRYPTION_KEY_NEXT` (env var) on the task; keep the old
   value in `ENCRYPTION_KEY`. The application's encryption
   service will accept either for decryption and use the
   primary for encryption. (Feature TBD — file a feature
   request if you need rotation today; v1.x doesn't ship the
   dual-key path yet.)
2. Once every directory_connections row has been re-saved
   (touching any field re-encrypts the bind password under
   the primary key), swap primary to the new value and drop
   the old.

Until the dual-key feature lands, the safe rotation procedure
is: snapshot the DB, drop and recreate every directory
connection through the UI under the new key.

### Backup / restore

RDS automated snapshots run daily inside `backup_window`. The
retention is `var.db_backup_retention_days` (default 7).
Point-in-time restore is enabled automatically.

To restore to a specific time:

```bash
aws rds restore-db-instance-to-point-in-time \
  --source-db-instance-identifier ldapportal-db \
  --target-db-instance-identifier ldapportal-db-restore \
  --restore-time "2026-05-20T12:00:00Z"
```

Then update the Terraform config to point at the restored
instance (or swap connection strings manually) before deleting
the original. The encrypted secrets in Secrets Manager are
unchanged, so the app can talk to the restored DB without
rotation.

### Tearing down

```bash
# Disable deletion protection on the DB
aws rds modify-db-instance --db-instance-identifier ldapportal-db \
  --no-deletion-protection --apply-immediately

# Now Terraform can destroy
terraform destroy
```

A final snapshot is taken (`final_snapshot_identifier` in the
RDS resource); keep or delete based on data-retention policy.

## Cost expectations

Per-month estimate, single-AZ, `db.t4g.micro`, `desired_count = 1`,
us-east-1 prices:

| Component | Cost |
|---|---|
| ECS Fargate (0.5 vCPU, 1 GiB, 730 hr) | ~$12 |
| ALB (730 hr, ~10 LCUs from typical demo traffic) | ~$25 |
| RDS db.t4g.micro + 20 GiB gp3 | ~$15 |
| CloudWatch Logs (1 GiB/mo + ingestion) | ~$1 |
| Secrets Manager (4 secrets) | ~$2 |
| NAT gateway egress for image pulls (~50 MB/mo with cache) | ~$1 |
| **Total** | **~$56/mo** |

Multi-AZ + bumping the DB to `db.t4g.small` adds ~$30/mo. A
production deployment with `desired_count = 2` adds another
~$12/mo for the second Fargate task.

The ALB is the largest fixed cost. For demo / eval deployments,
swapping the ALB for a CloudFront distribution + Lambda@Edge
redirect to the public ECS task's IP would halve the bill — but
that's an eval-grade shape, not production.

## Troubleshooting

**`Tasks are unhealthy` in the ALB target group within the first
2 minutes of apply.** Normal — Flyway is running migrations on
the new DB. Wait. If unhealthy persists past 5 minutes, check
CloudWatch Logs for `FlywayException` or `SchemaManagementException`.

**`Tasks failed to start: ResourceInitializationError: unable to
pull secrets or registry auth`.** The ECS task execution role
can't read one of the four secrets, or `image_pull_secret_arn`
points at a secret without ECR pull permission. Inspect the
role's `${name}-secrets-read` inline policy and confirm the
secret ARNs match what's in Secrets Manager.

**`502 Bad Gateway` from the ALB.** The target group has no
healthy targets. Either the task is mid-deploy (wait ~60s) or
the container is in restart-loop — `aws logs tail
/aws/ecs/ldapportal --follow` will show the JVM error.

**`SSL error: server name not present in cert`** when the app
tries to reach RDS. The JDBC URL has `sslmode=require` —
RDS's default cert chain is trusted by the JVM's truststore
since AWS publishes the root CA. If using a custom CA or an
older JVM, set `sslmode=verify-ca` and import the AWS RDS CA
into the truststore.

**`Bootstrap: skipping — superadmin already exists`** in the
app logs but no operator knows the password. See "Resetting
the bootstrap superadmin" above.

**Image pull rate-limited on cold start (`429 Too Many
Requests`).** Public ghcr.io has soft per-IP limits. The NAT
gateway's egress IP is shared across the VPC; on a hot day the
limits can bite. Set up the ECR pull-through cache (see
"Pulling from inside the VPC" above) to eliminate this entirely.

## See also

- [`deployment-fly.md`](deployment-fly.md) — the Fly demo deploy
  follows the same secret + Postgres + container shape; many of
  the gotchas (CRLF in base64 secrets, bootstrap password
  one-shot semantics) apply identically.
- [`edition-boundary.md`](edition-boundary.md) — what ships in
  each container tag.
- [`terraform/aws/README.md`](../terraform/aws/README.md) —
  the Terraform module's own README.
