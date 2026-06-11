<!-- SPDX-License-Identifier: Apache-2.0 -->
# Prod overlay — EKS + ECR + AWS Secrets Manager (community-plus-ISVA)

A Kustomize overlay over the [base](../../eks) for an internal-fork production
deploy that pulls images from **ECR**, sources secrets from **AWS Secrets
Manager** (Secrets Store CSI driver), and runs the **community-plus-isva**
backend with **Directory Sync** enabled.

Keeping these edits in the overlay (not the base) means upstream merges of the
base manifests stay clean. Full runbook: [`docs/deployment-eks-gitlab-ecr.md`](../../../docs/deployment-eks-gitlab-ecr.md).

## Files

| File | Purpose |
|---|---|
| `kustomization.yaml` | Includes the base, repoints images to ECR, pins the tag, lists the patches. |
| `configmap-patch.yaml` | `DB_URL`, `CORS_ALLOWED_ORIGIN`, and the `DIRECTORY_SYNC` entitlement grant. |
| `serviceaccount-patch.yaml` | IRSA role annotation on the `ldapportal` SA. |
| `secretproviderclass.yaml` | Syncs the four Secrets Manager entries → `ldapportal-secrets`. |
| `backend-csi-patch.yaml` | Mounts the SecretProviderClass on the backend pod. |
| `ingress-patch.yaml` | ACM certificate ARN + public hostname. |

## Edit before applying

Replace these placeholders (search for them across the overlay):

- `ACCOUNT_ID` — your AWS account id (ECR registry, IRSA role ARN, secret ARNs, cert ARN).
- `us-east-1` — your region, if different.
- `v1.0.0` (`kustomization.yaml` `newTag`) — the released image tag to deploy.
- `REPLACE_RDS_ENDPOINT` (`configmap-patch.yaml`) — your RDS endpoint host.
- `ldapportal.example.com` — your public hostname (`configmap-patch.yaml` CORS, `ingress-patch.yaml` ×2).
- `REPLACE_ME` (`ingress-patch.yaml`) — your ACM certificate id.

## Prerequisites

See [`docs/deployment-eks-gitlab-ecr.md`](../../../docs/deployment-eks-gitlab-ecr.md) §1–§4:
EKS + OIDC provider, AWS Load Balancer Controller, Secrets Store CSI driver +
AWS provider (`syncSecret.enabled=true`), the four `ldapportal/*` secrets in
Secrets Manager, the IRSA role, ECR repos, and RDS.

## Apply

```bash
kubectl apply -f deploy/aws/eks/namespace.yaml      # once
kubectl apply -k deploy/aws/eks-overlays/prod       # this overlay
kubectl -n ldapportal rollout status deploy/ldapportal-backend
```

CI/CD: [`../../eks/gitlab-ci.ecr-eks.example.yml`](../../eks/gitlab-ci.ecr-eks.example.yml)
builds + pushes to ECR and applies this overlay from a GitLab pipeline.
