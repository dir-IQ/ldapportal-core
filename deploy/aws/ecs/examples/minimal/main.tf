# SPDX-License-Identifier: Apache-2.0
#
# Minimal example wiring of the LDAPPortal AWS module.
#
# Prerequisites the customer brings:
#   - An AWS account with an existing VPC, two public subnets, two
#     private subnets (with NAT egress).
#   - An ACM certificate in the same region as the ALB, covering
#     the hostname operators will reach the app at.
#   - The customer's own state backend (S3 + DynamoDB lock table,
#     or Terraform Cloud, etc.) configured in `backend.tf`.

terraform {
  required_version = ">= 1.6"
}

provider "aws" {
  region = "us-east-1"
}

module "ldapportal" {
  source = "../.."

  name     = "ldapportal"
  hostname = "ldapportal.example.com"

  vpc_id             = "vpc-0123456789abcdef0"
  public_subnet_ids  = ["subnet-aaa", "subnet-bbb"]
  private_subnet_ids = ["subnet-ccc", "subnet-ddd"]

  # ACM cert in the same region as the ALB, covering var.hostname.
  certificate_arn = "arn:aws:acm:us-east-1:123456789012:certificate/abcd-…"

  # Pin to a digest in production. The :latest tags are fine for
  # eval deployments; bump to a specific release before going live.
  backend_image_uri  = "ghcr.io/dir-iq/ldapportal-community-plus-isva:latest"
  frontend_image_uri = "ghcr.io/dir-iq/ldapportal-frontend:latest"

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
