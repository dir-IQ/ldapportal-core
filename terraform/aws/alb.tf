# SPDX-License-Identifier: Apache-2.0
#
# Public ALB → app target group. HTTPS-only listener with the
# customer-supplied ACM cert; the plain HTTP listener redirects to
# HTTPS so a typo in a URL doesn't 502.
#
# Target-group health check hits /actuator/health every 30s. With
# the container-level health check in ecs.tf the ALB takes ~60s to
# mark a fresh task healthy on a cold start (JVM warm-up + Flyway
# migrations + Spring boot context); the deregistration delay is
# 30s so a graceful task replacement doesn't drop in-flight
# requests.

resource "aws_lb" "this" {
  name               = var.name
  internal           = false
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = var.public_subnet_ids

  # Drop misformed Host headers + delete request smuggling vectors.
  drop_invalid_header_fields = true
  idle_timeout               = 60

  tags = local.base_tags
}

resource "aws_lb_target_group" "this" {
  name        = "${var.name}-tg"
  port        = 8080
  protocol    = "HTTP"
  vpc_id      = var.vpc_id
  target_type = "ip" # Fargate tasks register by ENI IP

  health_check {
    enabled             = true
    path                = "/actuator/health"
    protocol            = "HTTP"
    matcher             = "200"
    interval            = 30
    timeout             = 10
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  deregistration_delay = 30
  tags                 = local.base_tags
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.certificate_arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.this.arn
  }
}

resource "aws_lb_listener" "http_redirect" {
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "redirect"
    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}
