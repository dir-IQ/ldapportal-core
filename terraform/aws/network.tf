# SPDX-License-Identifier: Apache-2.0
#
# Security groups only — the VPC + subnets are inputs (customer-managed).
# Three SGs, each scoped to the smallest reachable surface:
#
#   alb_sg → 443 from the internet (ingress); egress to app_sg only
#   app_sg → 8080 from alb_sg; egress everywhere (so image pulls,
#            outbound LDAP to the customer's directory, SIEM webhooks
#            etc. all work)
#   db_sg  → 5432 from app_sg only; egress nowhere
#
# Tight egress on db_sg means the Postgres machine cannot phone home
# even if compromised — small attack-surface reduction worth the
# minute of configuration.

locals {
  base_tags = merge(var.tags, {
    Application = "ldapportal"
    ManagedBy   = "terraform"
  })
}

resource "aws_security_group" "alb" {
  name        = "${var.name}-alb"
  description = "LDAPPortal ALB — 443 from internet"
  vpc_id      = var.vpc_id
  tags        = merge(local.base_tags, { Name = "${var.name}-alb" })
}

resource "aws_vpc_security_group_ingress_rule" "alb_https" {
  security_group_id = aws_security_group.alb.id
  description       = "HTTPS from anywhere"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

resource "aws_vpc_security_group_egress_rule" "alb_to_app" {
  security_group_id            = aws_security_group.alb.id
  description                  = "Forward to app on container port"
  referenced_security_group_id = aws_security_group.app.id
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
}

resource "aws_security_group" "app" {
  name        = "${var.name}-app"
  description = "LDAPPortal app — port 8080 from ALB only, egress open"
  vpc_id      = var.vpc_id
  tags        = merge(local.base_tags, { Name = "${var.name}-app" })
}

resource "aws_vpc_security_group_ingress_rule" "app_from_alb" {
  security_group_id            = aws_security_group.app.id
  description                  = "Container port from ALB"
  referenced_security_group_id = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
}

resource "aws_vpc_security_group_egress_rule" "app_egress_v4" {
  security_group_id = aws_security_group.app.id
  description       = "App egress (image pulls, LDAP bind, webhooks)"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

resource "aws_security_group" "db" {
  name        = "${var.name}-db"
  description = "LDAPPortal Postgres — 5432 from app only"
  vpc_id      = var.vpc_id
  tags        = merge(local.base_tags, { Name = "${var.name}-db" })
}

resource "aws_vpc_security_group_ingress_rule" "db_from_app" {
  security_group_id            = aws_security_group.db.id
  description                  = "Postgres from app"
  referenced_security_group_id = aws_security_group.app.id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}
# No egress rule on db_sg → Postgres has no outbound network reach.
