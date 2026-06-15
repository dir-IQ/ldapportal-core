# SPDX-License-Identifier: Apache-2.0
#
# Single-AZ RDS Postgres for the application.
#
# Why Postgres 16: matches the version the project's docker-compose
# stack and Fly demos pin (pg 16.13). Schema migrations are version-
# tested against this line; cross-major upgrades belong in a planned
# maintenance window, not in this module.
#
# Why not Aurora: same workload, double the per-hour cost, no
# concrete benefit for a single-AZ write-light directory-admin app.
# Customers who want Aurora can swap this resource locally.
#
# Multi-AZ is intentionally OFF by default — flip via the input if
# the customer's compliance posture requires it; the extra ~$0.04/h
# is small but irrelevant for a single-region eval deployment.

resource "aws_db_subnet_group" "this" {
  name        = "${var.name}-db-subnet-group"
  description = "Subnets for the LDAPPortal Postgres instance"
  subnet_ids  = var.private_subnet_ids
  tags        = local.base_tags
}

resource "aws_db_instance" "this" {
  identifier     = "${var.name}-db"
  engine         = "postgres"
  engine_version = "16.13"
  instance_class = var.db_instance_class

  allocated_storage     = var.db_allocated_storage
  max_allocated_storage = var.db_allocated_storage * 4 # storage autoscaling ceiling
  storage_type          = "gp3"
  storage_encrypted     = true

  db_name  = "ldapportal"
  username = "ldapportal"
  password = random_password.db.result

  vpc_security_group_ids = [aws_security_group.db.id]
  db_subnet_group_name   = aws_db_subnet_group.this.name
  multi_az               = false
  publicly_accessible    = false

  backup_retention_period = var.db_backup_retention_days
  backup_window           = "03:00-04:00"     # UTC
  maintenance_window      = "Sun:04:00-Sun:05:00"

  # Deletion protection on by default — customers who really want
  # to drop the DB flip this off, run apply, then destroy. Surprise-
  # destroying the audit log is bad.
  deletion_protection = true

  # Avoid leaving a snapshot at delete time for evaluation deployments;
  # production customers can set this via -var if they want one.
  skip_final_snapshot = false
  final_snapshot_identifier = "${var.name}-db-final-${formatdate("YYYY-MM-DD", timestamp())}"

  # Performance Insights is free for the t4g/m6g classes' 7-day
  # window and trivially useful for "why is the app slow?" triage.
  performance_insights_enabled          = true
  performance_insights_retention_period = 7

  apply_immediately = false
  tags              = local.base_tags

  lifecycle {
    ignore_changes = [
      # `formatdate(timestamp())` evaluates on every plan; without
      # this the snapshot id drifts and forces a replace on every
      # apply. The id is only consulted on actual delete.
      final_snapshot_identifier,
    ]
  }
}
