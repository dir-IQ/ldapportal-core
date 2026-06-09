<!-- SPDX-License-Identifier: Apache-2.0 -->
# LDAP Portal on AWS EKS

Kustomize manifests for running LDAP Portal on an EKS cluster from the
public GHCR images. Two Deployments (backend + nginx SPA) behind one
ALB ingress, with Postgres on managed RDS.

This is the Kubernetes counterpart to the ECS module in
[`terraform/aws/`](../../terraform/aws/) — same topology (ALB serves the
SPA, routes `/api/v1*` to the backend; secrets out of the image; RDS for
state), expressed as plain manifests instead of Fargate task definitions.

> **Status:** In progress (initial EKS manifests pulling GHCR images, 2026-06-09).

## What's here

| File | Purpose |
|---|---|
| `kustomization.yaml` | Base — ties the manifests together; pin image tags here. |
| `namespace.yaml` | The `ldapportal` namespace. |
| `serviceaccount.yaml` | Shared SA (IRSA annotation placeholder for the CSI path). |
| `configmap.yaml` | Non-secret backend env (DB host, CORS origin, cookie, logging). |
| `secret.example.yaml` | **Template** for the four secrets — created out of band, not in git. |
| `backend-deployment.yaml` / `-service.yaml` / `-hpa.yaml` / `-pdb.yaml` | The Spring Boot backend. |
| `frontend-deployment.yaml` / `-service.yaml` / `-pdb.yaml` | The nginx-served Vue SPA. |
| `frontend-nginx-configmap.yaml` | EKS nginx template (overrides the image's Fly-specific one). |
| `ingress.yaml` | Internet-facing ALB (AWS Load Balancer Controller). |
| `secretproviderclass.example.yaml` | Optional — sync secrets from AWS Secrets Manager via the CSI driver. |
| `optional/postgres-dev.yaml` | Optional — in-cluster Postgres for dev/test (not for prod). |

## Images

Pulled from GHCR (public — no pull secret needed for the default tags):

- Backend: `ghcr.io/dir-iq/ldapportal-community-plus-isva`
  (swap to `…-community` for the pure Apache-2.0 build).
- Frontend: `ghcr.io/dir-iq/ldapportal-frontend`.

Both are published by the repo's [`ghcr-publish.yml`](../../.github/workflows/ghcr-publish.yml)
workflow on every `vX.Y.Z` tag (tags: `X.Y.Z` and `X.Y`). **Pin a release
tag** in `kustomization.yaml` (`images:`) — don't run `:latest` in prod.

> The frontend GHCR image bakes a **Fly.io** nginx template
> (`${BACKEND_APP}.flycast`, Fly DNS resolver). `frontend-nginx-configmap.yaml`
> overrides it with a Kubernetes-correct template that proxies to the backend
> Service. You don't need to rebuild the image.

## Prerequisites

- An EKS cluster (1.27+) with worker nodes, and `kubectl` context set to it.
- **AWS Load Balancer Controller** installed (provisions the ALB from the
  Ingress): https://kubernetes-sigs.github.io/aws-load-balancer-controller/
- Public subnets tagged `kubernetes.io/role/elb=1` (or pin subnets via the
  ingress annotation).
- An **ACM certificate** for your hostname, in the cluster's region.
- A reachable **Postgres** — managed RDS recommended (the `terraform/aws`
  module provisions one), or `optional/postgres-dev.yaml` for testing.
- For autoscaling: **metrics-server** (EKS add-on). Drop `backend-hpa.yaml`
  if you don't run it.

## Quick start

```bash
# 1. Namespace
kubectl apply -f deploy/eks/namespace.yaml

# 2. Secrets — created directly, never committed. (Or use the CSI path,
#    see "Secrets" below.)
kubectl create secret generic ldapportal-secrets -n ldapportal \
  --from-literal=ENCRYPTION_KEY="$(openssl rand -base64 32)" \
  --from-literal=JWT_SECRET="$(openssl rand -base64 64)" \
  --from-literal=BOOTSTRAP_SUPERADMIN_PASSWORD="$(openssl rand -base64 24)" \
  --from-literal=DB_PASSWORD="<your RDS master password>"

# 3. Edit the placeholders:
#    - configmap.yaml : DB_URL (RDS endpoint), CORS_ALLOWED_ORIGIN
#    - ingress.yaml   : certificate-arn, host (×2)
#    - kustomization.yaml : image tags

# 4. Apply everything
kubectl apply -k deploy/eks

# 5. Watch it come up (first boot runs Flyway migrations)
kubectl -n ldapportal rollout status deploy/ldapportal-backend
kubectl -n ldapportal get ingress ldapportal   # ADDRESS = the ALB DNS name
```

Point your hostname's DNS at the ALB (Route 53 ALIAS or a CNAME to the
ingress `ADDRESS`), then browse to `https://<hostname>` and log in as
`superadmin` with the `BOOTSTRAP_SUPERADMIN_PASSWORD` you generated. The
bootstrap is inert once a permanent local superadmin exists.

## Secrets

The backend reads four secret values as env (`envFrom: secretRef` on
`ldapportal-secrets`): `ENCRYPTION_KEY`, `JWT_SECRET`,
`BOOTSTRAP_SUPERADMIN_PASSWORD`, `DB_PASSWORD`.

- **Simple:** create the Secret with `kubectl create secret` (step 2 above).
- **Recommended (prod):** sync from AWS Secrets Manager with the Secrets
  Store CSI driver + IRSA — see `secretproviderclass.example.yaml`. It
  materialises a Secret of the same name/shape, so the Deployment is
  unchanged. This reuses the four `ldapportal/*` secrets the
  `terraform/aws` module already creates.

> **Rotating `ENCRYPTION_KEY`** without re-encrypting stored directory bind
> passwords locks out every configured directory. Treat it like the ECS
> runbook does (`docs/deployment-aws.md` → "Rotating ENCRYPTION_KEY").

## Configuration notes

- **Port:** both images bind container port `8080`. Backend health is
  Spring Actuator at `/actuator/health` (used by all probes and the
  backend ALB target group); the SPA returns `200` at `/`.
- **Cookie/CORS:** served over HTTPS at the ALB, so `COOKIE_SECURE=true`
  and `CORS_ALLOWED_ORIGIN` must equal the public origin.
- **DB TLS:** keep `?sslmode=require` on the RDS `DB_URL`.
- **JVM heap:** the backend image's ENTRYPOINT pins `-Xmx512m`. The pod
  memory limit (`1Gi`) leaves headroom for metaspace/threads/buffers;
  changing the heap means rebuilding the image.
- **Migrations:** Flyway runs at startup under a DB lock, so the 2 replicas
  (and any rollout) are safe; the backend `startupProbe` budgets ~5 minutes
  for a cold-DB migration before liveness kicks in.

## Scaling & availability

- `backend-hpa.yaml` autoscales the backend 2→6 on 70% CPU (needs
  metrics-server). The frontend is a fixed 2 replicas.
- PodDisruptionBudgets keep ≥1 of each serving through node drains.
- `maxUnavailable: 0` rollouts mean zero-downtime deploys (new pod up
  before old one leaves).

## Verifying

```bash
kubectl -n ldapportal get pods,svc,ingress,hpa
kubectl -n ldapportal logs deploy/ldapportal-backend | grep -i "Started .* in"
# health (from inside the cluster):
kubectl -n ldapportal exec deploy/ldapportal-backend -- wget -qO- localhost:8080/actuator/health
```

## Teardown

```bash
kubectl delete -k deploy/eks
# the ALB is reclaimed when the Ingress is deleted; the namespace delete
# removes the rest. RDS (if external) is managed by terraform, not here.
```
