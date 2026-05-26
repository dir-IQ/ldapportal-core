# SPDX-License-Identifier: Apache-2.0
#
# ECS Fargate cluster, task definition, service.
#
# One cluster per `var.name`. Customers consolidating multiple
# environments into one cluster pass the same `name` to all
# modules — Terraform's idempotency handles the no-op upsert.
#
# The task definition pulls every secret via the ECS-native
# `secrets` field rather than reading them at startup from an
# init container. That gives the JVM the values as plain env
# vars and avoids holding the ciphertext in process memory.

resource "aws_ecs_cluster" "this" {
  name = var.name
  tags = local.base_tags

  setting {
    name  = "containerInsights"
    value = "enhanced"
  }
}

# ── IAM ──────────────────────────────────────────────────────────
#
# Two roles, by AWS's split:
#   execution_role — used by the ECS agent to pull the image
#                    + read secrets at task-start time
#   task_role      — used by the app itself at runtime (none of
#                    the API endpoints currently call AWS, so the
#                    task role is empty; kept for future SDK use)

data "aws_iam_policy_document" "ecs_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "execution" {
  name               = "${var.name}-ecs-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
  tags               = local.base_tags
}

resource "aws_iam_role_policy_attachment" "execution_managed" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# Read the four Secrets Manager entries this task needs at start time.
data "aws_iam_policy_document" "execution_secrets" {
  statement {
    actions = [
      "secretsmanager:GetSecretValue",
      "secretsmanager:DescribeSecret",
    ]
    resources = concat(
      [
        aws_secretsmanager_secret.encryption_key.arn,
        aws_secretsmanager_secret.jwt_secret.arn,
        aws_secretsmanager_secret.bootstrap_superadmin.arn,
        aws_secretsmanager_secret.db_password.arn,
      ],
      var.image_pull_secret_arn == null ? [] : [var.image_pull_secret_arn],
    )
  }
}

resource "aws_iam_role_policy" "execution_secrets" {
  name   = "${var.name}-secrets-read"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.execution_secrets.json
}

resource "aws_iam_role" "task" {
  name               = "${var.name}-ecs-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
  tags               = local.base_tags
}

# ── Logs ─────────────────────────────────────────────────────────

resource "aws_cloudwatch_log_group" "app" {
  name              = "/aws/ecs/${var.name}"
  retention_in_days = var.log_retention_days
  tags              = local.base_tags
}

# ── Task definition ──────────────────────────────────────────────

resource "aws_ecs_task_definition" "this" {
  family                   = var.name
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn

  container_definitions = jsonencode([
    {
      name      = "app"
      image     = var.image_uri
      essential = true
      portMappings = [
        { containerPort = 8080, protocol = "tcp" }
      ]
      environment = [
        # JDBC URL composed from RDS attributes. Postgres on RDS
        # accepts TLS by default; we pin `sslmode=require` so a
        # mis-routed connection can't downgrade to plaintext.
        { name = "DB_URL", value = "jdbc:postgresql://${aws_db_instance.this.endpoint}/${aws_db_instance.this.db_name}?sslmode=require" },
        { name = "DB_USERNAME", value = aws_db_instance.this.username },
        { name = "BOOTSTRAP_SUPERADMIN_USERNAME", value = var.bootstrap_superadmin_username },
        { name = "CORS_ALLOWED_ORIGIN", value = "https://${var.hostname}" },
        { name = "APP_LOG_LEVEL", value = "INFO" },
      ]
      secrets = [
        { name = "ENCRYPTION_KEY", valueFrom = aws_secretsmanager_secret.encryption_key.arn },
        { name = "JWT_SECRET", valueFrom = aws_secretsmanager_secret.jwt_secret.arn },
        { name = "BOOTSTRAP_SUPERADMIN_PASSWORD", valueFrom = aws_secretsmanager_secret.bootstrap_superadmin.arn },
        { name = "DB_PASSWORD", valueFrom = aws_secretsmanager_secret.db_password.arn },
      ]
      repositoryCredentials = var.image_pull_secret_arn == null ? null : {
        credentialsParameter = var.image_pull_secret_arn
      }
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.app.name
          awslogs-region        = data.aws_region.current.name
          awslogs-stream-prefix = "app"
        }
      }
      healthCheck = {
        # Container-level liveness — independent of the ALB target-
        # group health check. Both have to pass for the task to be
        # considered healthy; both pointing at the same endpoint
        # means a JVM in restart-loop is caught by ECS before the
        # ALB notices.
        command     = ["CMD-SHELL", "wget -qO- http://localhost:8080/actuator/health || exit 1"]
        interval    = 30
        timeout     = 10
        retries     = 3
        startPeriod = 60
      }
    }
  ])

  tags = local.base_tags
}

data "aws_region" "current" {}

# ── Service ──────────────────────────────────────────────────────

resource "aws_ecs_service" "this" {
  name            = var.name
  cluster         = aws_ecs_cluster.this.id
  task_definition = aws_ecs_task_definition.this.arn
  desired_count   = var.desired_count
  launch_type     = "FARGATE"

  deployment_maximum_percent         = 200
  deployment_minimum_healthy_percent = 100
  health_check_grace_period_seconds  = 60

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [aws_security_group.app.id]
    assign_public_ip = false # private subnets — egress via NAT
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.this.arn
    container_name   = "app"
    container_port   = 8080
  }

  depends_on = [
    aws_lb_listener.https,
    aws_iam_role_policy.execution_secrets,
  ]

  lifecycle {
    # External tooling (deploy-aws.yml workflow,
    # `aws ecs update-service --force-new-deployment`) bumps the
    # task def revision out of band. Don't fight it on subsequent
    # `terraform apply`s.
    ignore_changes = [task_definition, desired_count]
  }

  tags = local.base_tags
}
