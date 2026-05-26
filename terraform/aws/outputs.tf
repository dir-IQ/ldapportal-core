# SPDX-License-Identifier: Apache-2.0

output "alb_dns_name" {
  description = "ALB hostname. Create a CNAME / Route 53 ALIAS from `var.hostname` to this value."
  value       = aws_lb.this.dns_name
}

output "alb_zone_id" {
  description = "ALB hosted-zone ID — pass to a Route 53 ALIAS A-record's `alias { zone_id = ... }`."
  value       = aws_lb.this.zone_id
}

output "ecs_cluster_name" {
  description = "ECS cluster name. Used by deploy workflows (`aws ecs update-service --cluster …`)."
  value       = aws_ecs_cluster.this.name
}

output "ecs_service_name" {
  description = "ECS service name. Used by deploy workflows."
  value       = aws_ecs_service.this.name
}

output "rds_endpoint" {
  description = "Postgres endpoint host:port. Diagnostics-only — the app reaches this via the DB_URL secret."
  value       = aws_db_instance.this.endpoint
}

output "bootstrap_superadmin_secret_arn" {
  description = "Secrets Manager ARN of the initial superadmin password. Operator retrieves once via `aws secretsmanager get-secret-value` after first apply; password is inert after a permanent superadmin exists in the DB."
  value       = aws_secretsmanager_secret.bootstrap_superadmin.arn
}
