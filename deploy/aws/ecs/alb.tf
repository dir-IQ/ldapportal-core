# SPDX-License-Identifier: Apache-2.0
#
# Public ALB with two target groups. HTTPS-only listener with the
# customer-supplied ACM cert; the plain HTTP listener redirects to
# HTTPS so a typo in a URL doesn't 502.
#
# Routing:
#   /api/v1*  → backend target group  (the Spring Boot API)
#   *         → frontend target group (the nginx-served SPA)
#
# The SPA is served same-origin, so its /api/v1 XHRs land on the same
# hostname and the ALB rule forwards them to the backend — no CORS
# round-trip, and the frontend image's own nginx /api/v1 proxy block
# is never reached.
#
# Backend target-group health check hits /actuator/health; with the
# container-level health check in ecs.tf the ALB takes ~60s to mark a
# fresh task healthy on a cold start (JVM warm-up + Flyway migrations
# + Spring context). The frontend health check hits / (nginx serves
# index.html immediately). Both groups deregister with a 30s delay so
# a graceful task replacement doesn't drop in-flight requests.

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

resource "aws_lb_target_group" "backend" {
  name        = "${var.name}-backend"
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

resource "aws_lb_target_group" "frontend" {
  name        = "${var.name}-frontend"
  port        = 8080
  protocol    = "HTTP"
  vpc_id      = var.vpc_id
  target_type = "ip"

  health_check {
    enabled             = true
    path                = "/"
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

  # Default: everything that isn't an API call goes to the SPA.
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.frontend.arn
  }
}

# API traffic takes priority over the SPA default.
resource "aws_lb_listener_rule" "api" {
  listener_arn = aws_lb_listener.https.arn
  priority     = 10

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.backend.arn
  }

  condition {
    path_pattern {
      # Covers every controller (@RequestMapping("/api/v1/…")) and the
      # springdoc OpenAPI doc at /api/v1/openapi.
      values = ["/api/v1", "/api/v1/*"]
    }
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
