# SPDX-License-Identifier: Apache-2.0
#
# Three application secrets + the DB credentials, all in Secrets
# Manager. Generated with `random_password` on first apply so the
# customer never has to invent base64 values by hand. The task
# definition (ecs.tf) references each by ARN — the ECS task
# execution role gets read permission via the inline policy in
# the same file.
#
# Rotation: replace the secret value out-of-band via the AWS
# console / `aws secretsmanager update-secret` and force a new
# deployment (`aws ecs update-service --force-new-deployment`).
# The task picks up the new value on cold start. Rotating
# ENCRYPTION_KEY without re-encrypting the bind passwords stored
# in directory_connections rows will brick existing directories —
# the runbook covers the rotation procedure.

resource "random_password" "encryption_key" {
  # 32 raw bytes = AES-256 key. Base64-encoded by the task's
  # application.yml binding; we store the raw base64 here.
  length      = 44 # 32 bytes -> 44 base64 chars including padding
  special     = false
  min_upper   = 1
  min_lower   = 1
  min_numeric = 1
}

resource "random_password" "jwt_secret" {
  length      = 88 # 64 bytes -> 88 base64 chars
  special     = false
  min_upper   = 1
  min_lower   = 1
  min_numeric = 1
}

resource "random_password" "bootstrap_superadmin" {
  length  = 24
  special = true
  # Avoid characters that break shell quoting in the runbook's
  # "log in via curl" example.
  override_special = "!@#%^&*+_-=?"
}

resource "random_password" "db" {
  length      = 32
  special     = false
  min_upper   = 1
  min_lower   = 1
  min_numeric = 1
}

# ── App secrets ─────────────────────────────────────────────────

resource "aws_secretsmanager_secret" "encryption_key" {
  name        = "${var.name}/encryption-key"
  description = "AES-256 key for encrypting bind passwords. Rotating without re-encrypting existing directory_connections.bind_password_encrypted rows will lock out every directory; follow docs/deployment-aws.md → 'Rotating ENCRYPTION_KEY'."
  tags        = local.base_tags
}
resource "aws_secretsmanager_secret_version" "encryption_key" {
  secret_id     = aws_secretsmanager_secret.encryption_key.id
  secret_string = random_password.encryption_key.result
}

resource "aws_secretsmanager_secret" "jwt_secret" {
  name        = "${var.name}/jwt-secret"
  description = "HMAC signing secret for JWTs. Rotating invalidates every in-flight session; operators must re-login. Tokens issued after rotation use the new key."
  tags        = local.base_tags
}
resource "aws_secretsmanager_secret_version" "jwt_secret" {
  secret_id     = aws_secretsmanager_secret.jwt_secret.id
  secret_string = random_password.jwt_secret.result
}

resource "aws_secretsmanager_secret" "bootstrap_superadmin" {
  name        = "${var.name}/bootstrap-superadmin-password"
  description = "First-superadmin password used by BootstrapService on first start. Inert once a LOCAL superadmin exists in `accounts`; rotating after that has no effect (see docs/deployment-aws.md → 'Resetting the bootstrap superadmin')."
  tags        = local.base_tags
}
resource "aws_secretsmanager_secret_version" "bootstrap_superadmin" {
  secret_id     = aws_secretsmanager_secret.bootstrap_superadmin.id
  secret_string = random_password.bootstrap_superadmin.result
}

# ── DB credentials ──────────────────────────────────────────────
#
# RDS gets the password directly in its master_user_password input
# (referenced in rds.tf). The application reads it back via
# Secrets Manager so a future rotation can update both in lock-step.

resource "aws_secretsmanager_secret" "db_password" {
  name        = "${var.name}/db-password"
  description = "Postgres master password. Rotating requires updating the RDS master user simultaneously — use the AWS Secrets Manager Rotation Lambda for the right interlock; raw `aws secretsmanager update-secret` only updates the secret store, not the DB."
  tags        = local.base_tags
}
resource "aws_secretsmanager_secret_version" "db_password" {
  secret_id     = aws_secretsmanager_secret.db_password.id
  secret_string = random_password.db.result
}
