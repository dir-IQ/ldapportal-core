# SPDX-License-Identifier: Apache-2.0
#
# Inputs to the LDAPPortal AWS ECS Fargate stack.
#
# The module assumes the customer already operates a VPC with at least
# two private subnets (for the app + RDS) and two public subnets (for
# the ALB). VPC provisioning is the network team's territory, not this
# module's.

variable "name" {
  description = "Prefix for all created resources (e.g. \"ldapportal\" or \"ldapportal-prod\"). Must be unique within the AWS account for ALB / RDS naming."
  type        = string
  default     = "ldapportal"
}

variable "vpc_id" {
  description = "ID of the customer's existing VPC."
  type        = string
}

variable "private_subnet_ids" {
  description = "Two or more private subnet IDs in distinct AZs for the app + RDS. The app egresses through these subnets' route tables, so they need a NAT gateway / interface endpoint to reach ghcr.io for image pulls."
  type        = list(string)
  validation {
    condition     = length(var.private_subnet_ids) >= 2
    error_message = "At least two private subnets in distinct AZs are required (for RDS multi-AZ-aware placement and ECS task spreading)."
  }
}

variable "public_subnet_ids" {
  description = "Two or more public subnet IDs in distinct AZs for the ALB."
  type        = list(string)
  validation {
    condition     = length(var.public_subnet_ids) >= 2
    error_message = "ALBs require at least two public subnets in distinct AZs."
  }
}

variable "backend_image_uri" {
  description = "Backend (Spring Boot) container image. Defaults to the published ghcr.io community-plus-isva tag (core + ISVA addon); set to ldapportal-community for the addon-free OSS build. Pin to a digest in production."
  type        = string
  default     = "ghcr.io/dir-iq/ldapportal-community-plus-isva:latest"
}

variable "frontend_image_uri" {
  description = "Frontend (nginx-served Vue SPA) container image. Defaults to the published ghcr.io frontend tag. Pin to a digest in production. The ALB serves this for all paths except /api/v1*, which it routes to the backend target group."
  type        = string
  default     = "ghcr.io/dir-iq/ldapportal-frontend:latest"
}

variable "image_pull_secret_arn" {
  description = "ARN of a Secrets Manager secret holding ghcr.io credentials in the {\"username\":\"…\",\"password\":\"…\"} shape, used by both task execution roles. Required when pulling private tags; leave null for public tags (no auth needed). Set up an ECR pull-through cache instead for sub-region latency without ghcr.io egress on every cold start."
  type        = string
  default     = null
}

variable "certificate_arn" {
  description = "ACM certificate ARN (in the same region as the ALB) for the HTTPS listener. Must cover the hostname operators reach the app at."
  type        = string
}

variable "hostname" {
  description = "Public hostname operators reach the app at (e.g. ldapportal.example.com). Used to set CORS_ALLOWED_ORIGIN on the task and to validate the ACM cert match at apply time."
  type        = string
}

variable "task_cpu" {
  description = "Backend Fargate task CPU units. 512 = 0.5 vCPU and is enough for small directories; bump to 1024 for >5k users or heavy approval traffic."
  type        = number
  default     = 512
}

variable "task_memory" {
  description = "Backend Fargate task memory in MiB. Match to task_cpu per the Fargate sizing table (https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task_definition_parameters.html#task_size). 1024 MiB matches the JVM's -Xms256m -Xmx512m baseline with headroom."
  type        = number
  default     = 1024
}

variable "desired_count" {
  description = "Number of backend ECS tasks. 1 is fine for prod with brief restart downtime; bump to 2 with an ALB-managed rolling deploy for zero-downtime updates."
  type        = number
  default     = 1
}

variable "frontend_task_cpu" {
  description = "Frontend Fargate task CPU units. The nginx-served static SPA is light; 256 (0.25 vCPU) is plenty."
  type        = number
  default     = 256
}

variable "frontend_task_memory" {
  description = "Frontend Fargate task memory in MiB. Match to frontend_task_cpu per the Fargate sizing table. 512 MiB is ample for nginx serving static assets."
  type        = number
  default     = 512
}

variable "frontend_desired_count" {
  description = "Number of frontend ECS tasks. 1 is fine for prod with brief restart downtime; bump to 2 for zero-downtime frontend rollouts."
  type        = number
  default     = 1
}

variable "db_instance_class" {
  description = "RDS instance class for the Postgres backing store. db.t4g.micro fits a small directory; db.t4g.small or m6g.large for >5k users + significant write load."
  type        = string
  default     = "db.t4g.micro"
}

variable "db_allocated_storage" {
  description = "RDS storage in GB. Audit log + outbox grow with operator activity; start at 20, monitor via CloudWatch RDS.FreeStorageSpace."
  type        = number
  default     = 20
}

variable "db_backup_retention_days" {
  description = "Days of automated RDS snapshot retention. 7 is the AWS default; bump to 30 for compliance-driven deployments."
  type        = number
  default     = 7
}

variable "bootstrap_superadmin_username" {
  description = "Initial superadmin username, written to the BOOTSTRAP_SUPERADMIN_USERNAME env var on the task. Only used on the first start when no superadmin row exists in the DB."
  type        = string
  default     = "superadmin"
}

variable "log_retention_days" {
  description = "CloudWatch Logs retention for the app log group. 30 is the audit-friendly default; lower for cost-sensitive demo environments."
  type        = number
  default     = 30
}

variable "tags" {
  description = "Tags applied to every resource the module creates. Customers typically inject `{ Environment, Owner, CostCenter }` etc."
  type        = map(string)
  default     = {}
}
