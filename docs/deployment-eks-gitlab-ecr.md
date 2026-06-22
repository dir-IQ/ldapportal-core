# Deploying LDAPPortal to AWS EKS (community-plus-ISVA) from internal GitLab

- **Date:** 2026-06-11
- **Status:** Not started (reference runbook, 2026-06-11).
- **Audience:** A customer running an internal GitLab fork who will build the
  **community-plus-isva** edition, push images to **AWS ECR**, deploy to **AWS
  EKS**, and source runtime secrets from **AWS Secrets Manager**.
- **Companion files:** [`.gitlab-ci.yml`](../.gitlab-ci.yml),
  [`docs/self-hosting-gitlab.md`](self-hosting-gitlab.md), and the manifests
  under [`deploy/aws/eks/`](../deploy/aws/eks/).

> This repo is **Apache-2.0** (`core` + `addons/isva`). The community-plus-isva
> build runs with **no signed license key** and no call-home. Directory Sync is
> an open-source feature gated by an entitlement you self-grant via config
> (`LDAPPORTAL_ENTITLEMENTS_GRANT=DIRECTORY_SYNC`) — covered in §7.

## Fixed assumptions for this guide

| Decision | This guide assumes |
|---|---|
| Backend edition | `community-plus-isva` (Apache-2.0, includes the ISVA/IVIA addon) |
| Directory Sync | **Enabled** via the self-host entitlement grant |
| Image registry | **AWS ECR** (two repos) |
| Runtime secrets | **AWS Secrets Manager** via the Secrets Store CSI driver + IRSA |
| Database | Managed **RDS for PostgreSQL** |
| Ingress | Internet-facing **ALB** via the AWS Load Balancer Controller |
| Manifests | The shipped `deploy/aws/eks/` Kustomize base, customized through a **prod overlay** so upstream merges stay clean |

Replace every `<PLACEHOLDER>` with your values. Commands assume `aws`,
`kubectl`, `eksctl`/`helm`, and `git` are installed and your shell is
authenticated to the target AWS account and region.

---

## Topology

```
                 Route 53  ──►  ALB (HTTPS, ACM cert)
                                  │
                 /api/v1*  ───────┼────────►  ldapportal-backend  (Spring Boot :8080)
                 everything else ─┘            ├─ envFrom: ldapportal-config (ConfigMap)
                                  │            └─ envFrom: ldapportal-secrets (synced from
                                  │                         AWS Secrets Manager via CSI)
                 /  ─────────────►  ldapportal-frontend (nginx SPA :8080)
                                               │
                              RDS PostgreSQL ◄─┘ (Flyway migrates at startup)
```

Both images bind container port **8080**; backend health is Spring Actuator at
**`/actuator/health`**. Flyway runs migrations at startup under a DB advisory
lock, so the 2 backend replicas (and rollouts) are safe.

---

## 1. Prerequisites (one-time cluster foundation)

1. **EKS cluster** 1.27+ with managed node group, and your `kubectl` context
   pointed at it (`aws eks update-kubeconfig --name <CLUSTER> --region <REGION>`).
2. **IAM OIDC provider** associated with the cluster (required for IRSA):
   ```bash
   eksctl utils associate-iam-oidc-provider --cluster <CLUSTER> --region <REGION> --approve
   ```
3. **AWS Load Balancer Controller** installed (provisions the ALB from the
   Ingress): https://kubernetes-sigs.github.io/aws-load-balancer-controller/
4. **Public subnets tagged** `kubernetes.io/role/elb=1` (so the ALB controller
   can place the internet-facing LB), or you will pin subnets on the Ingress.
5. **metrics-server** add-on (only needed for the backend HPA; drop
   `backend-hpa.yaml` from the overlay if you don't run it).
6. **Secrets Store CSI driver + AWS provider** (for the Secrets Manager path):
   ```bash
   helm repo add secrets-store-csi-driver https://kubernetes-sigs.github.io/secrets-store-csi-driver/charts
   helm install csi-secrets-store secrets-store-csi-driver/secrets-store-csi-driver \
     -n kube-system --set syncSecret.enabled=true
   kubectl apply -f https://raw.githubusercontent.com/aws/secrets-store-csi-driver-provider-aws/main/deployment/aws-provider-installer.yaml
   ```
   `syncSecret.enabled=true` is **required** — it lets the driver materialize a
   real Kubernetes Secret the Deployment can read via `envFrom`.
7. **Node IAM role** has `AmazonEC2ContainerRegistryReadOnly` (default for
   eksctl-managed nodes) — this is what lets pods pull from ECR **without** an
   imagePullSecret.

---

## 2. Create the ECR repositories

Two repos — one backend (community-plus-isva), one frontend:

```bash
export REGION=<REGION>
export ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
export ECR=$ACCOUNT_ID.dkr.ecr.$REGION.amazonaws.com

aws ecr create-repository --repository-name ldapportal-community-plus-isva --region $REGION
aws ecr create-repository --repository-name ldapportal-frontend --region $REGION
# (optional) scan on push:
aws ecr put-image-scanning-configuration \
  --repository-name ldapportal-community-plus-isva \
  --image-scanning-configuration scanOnPush=true --region $REGION
```

---

## 3. Provision RDS PostgreSQL

Create (or reuse) a PostgreSQL instance reachable from the EKS node subnets:

- Engine: PostgreSQL 14+ (16.x recommended).
- DB name `ldapportal`, master user `ldapportal` (matches `configmap.yaml`'s
  `DB_USERNAME`; change both together if you differ).
- Security group: allow 5432 from the node group's security group.
- **Keep TLS on** — the ConfigMap's `DB_URL` carries `?sslmode=require`.

Record the endpoint; you'll put it in the ConfigMap (`DB_URL`) and the master
password into Secrets Manager (§4).

---

## 4. Put the four secrets in AWS Secrets Manager

The backend reads four secret env values: `ENCRYPTION_KEY`, `JWT_SECRET`,
`BOOTSTRAP_SUPERADMIN_PASSWORD`, `DB_PASSWORD`. Create them under an
`ldapportal/` prefix (matching `secretproviderclass.example.yaml`):

```bash
aws secretsmanager create-secret --name ldapportal/encryption-key \
  --secret-string "$(openssl rand -base64 32)" --region $REGION
aws secretsmanager create-secret --name ldapportal/jwt-secret \
  --secret-string "$(openssl rand -base64 64)" --region $REGION
aws secretsmanager create-secret --name ldapportal/bootstrap-superadmin-password \
  --secret-string "$(openssl rand -base64 24)" --region $REGION
aws secretsmanager create-secret --name ldapportal/db-password \
  --secret-string "<RDS_MASTER_PASSWORD>" --region $REGION
```

> ⚠️ **`ENCRYPTION_KEY` is effectively permanent.** Rotating it without
> re-encrypting stored directory bind passwords locks out every configured
> directory. Treat it like the ECS runbook (`docs/deployment-aws.md` →
> "Rotating ENCRYPTION_KEY").

### 4.1 IAM role for the pods to read those secrets (IRSA)

Create an IAM policy allowing read on exactly those four secrets, then a role
trusted by the cluster's OIDC provider, scoped to the `ldapportal` ServiceAccount
in the `ldapportal` namespace.

`ldapportal-secrets-policy.json`:
```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"],
    "Resource": [
      "arn:aws:secretsmanager:<REGION>:<ACCOUNT_ID>:secret:ldapportal/encryption-key-*",
      "arn:aws:secretsmanager:<REGION>:<ACCOUNT_ID>:secret:ldapportal/jwt-secret-*",
      "arn:aws:secretsmanager:<REGION>:<ACCOUNT_ID>:secret:ldapportal/bootstrap-superadmin-password-*",
      "arn:aws:secretsmanager:<REGION>:<ACCOUNT_ID>:secret:ldapportal/db-password-*"
    ]
  }]
}
```
(If the secrets use a customer-managed KMS key, also allow `kms:Decrypt` on that
key ARN.)

```bash
aws iam create-policy --policy-name ldapportal-secrets-reader \
  --policy-document file://ldapportal-secrets-policy.json

# eksctl creates the role with the correct OIDC trust and binds it to the SA:
eksctl create iamserviceaccount \
  --cluster <CLUSTER> --region <REGION> \
  --namespace ldapportal --name ldapportal \
  --attach-policy-arn arn:aws:iam::<ACCOUNT_ID>:policy/ldapportal-secrets-reader \
  --role-name ldapportal-secrets-reader \
  --approve
```

Note the role ARN it prints — you'll annotate the ServiceAccount with it in §6.
(If you let `eksctl create iamserviceaccount` create the SA, you'll instead make
the overlay's `serviceaccount.yaml` match it, or skip the base SA — see §6.4.)

---

## 5. Build and push images from GitLab CI → ECR

### 5.1 Mirror the repo into GitLab (if not already done)

```bash
git clone --mirror https://github.com/dir-IQ/ldapportal-core.git
cd ldapportal-core.git
git remote add gitlab https://<GITLAB_HOST>/<GROUP>/ldapportal-core.git
git push --mirror gitlab
```
Keep GitHub as a read-only upstream and merge `vX.Y.Z` tags periodically.

### 5.2 Let GitLab authenticate to AWS (OIDC, no static keys)

Create an IAM role trusted by your GitLab instance's OIDC provider that allows
`ecr:GetAuthorizationToken` (account-wide) plus push/pull on the two repos
(`ecr:BatchCheckLayerAvailability`, `:PutImage`, `:InitiateLayerUpload`,
`:UploadLayerPart`, `:CompleteLayerUpload`, `:BatchGetImage`). Reference it from
the pipeline via `id_tokens` + `aws sts assume-role-with-web-identity`.

> Simpler fallback if you don't use GitLab OIDC: store `AWS_ACCESS_KEY_ID` /
> `AWS_SECRET_ACCESS_KEY` for a push-only IAM user as **masked, protected** CI/CD
> variables. OIDC is preferred — no long-lived keys.

### 5.3 Adapt the `publish` stage for ECR + single edition

The shipped `.gitlab-ci.yml` pushes both editions to the GitLab registry. For
this deployment you only need **community-plus-isva** + **frontend**, pushed to
ECR. Replace the `publish-backend` / `publish-frontend` jobs with:

```yaml
variables:
  AWS_REGION: "<REGION>"
  ECR_REGISTRY: "<ACCOUNT_ID>.dkr.ecr.<REGION>.amazonaws.com"

.ecr-login:
  image: docker:27
  services:
    - docker:27-dind
  before_script:
    - apk add --no-cache aws-cli
    # OIDC: assume the push role (or drop this and rely on CI AWS_* variables)
    - aws ecr get-login-password --region "$AWS_REGION" \
        | docker login --username AWS --password-stdin "$ECR_REGISTRY"

publish-backend:
  stage: publish
  extends: .ecr-login
  needs:
    - job: package-backend          # keep only the community-plus-isva matrix leg
      artifacts: true
  script:
    - IMAGE="$ECR_REGISTRY/ldapportal-community-plus-isva"
    - docker build -f docker/community-plus-isva/Dockerfile
        -t "$IMAGE:$CI_COMMIT_TAG" -t "$IMAGE:latest" .
    - docker push "$IMAGE:$CI_COMMIT_TAG"
    - docker push "$IMAGE:latest"
  rules:
    - if: '$CI_COMMIT_TAG =~ /^v[0-9]/'

publish-frontend:
  stage: publish
  extends: .ecr-login
  needs: []
  script:
    - IMAGE="$ECR_REGISTRY/ldapportal-frontend"
    - docker build -f frontend/Dockerfile
        -t "$IMAGE:$CI_COMMIT_TAG" -t "$IMAGE:latest" frontend
    - docker push "$IMAGE:$CI_COMMIT_TAG"
    - docker push "$IMAGE:latest"
  rules:
    - if: '$CI_COMMIT_TAG =~ /^v[0-9]/'
```

Also narrow `package-backend`'s matrix to a single edition so you don't build
the community-only JAR you won't ship:

```yaml
package-backend:
  # ...
  parallel:
    matrix:
      - EDITION: [community-plus-isva]
```

> **Build-order reminder:** the backend Dockerfile **`COPY`s a host-built JAR**
> from `distribution/community-plus-isva/target/`. The `package` stage must run
> before `publish` (the `needs:` above enforces it). Keep the `test`-stage
> guardrails (`scripts/check-addons-license-headers.sh`, the community-bundle
> boundary scans) — they assert the artifact stays Apache-clean.

### 5.4 Cut a release

```bash
git tag v1.0.0 && git push gitlab v1.0.0
```
The tag triggers `package` → `publish`, leaving:
- `<ACCOUNT_ID>.dkr.ecr.<REGION>.amazonaws.com/ldapportal-community-plus-isva:v1.0.0`
- `<ACCOUNT_ID>.dkr.ecr.<REGION>.amazonaws.com/ldapportal-frontend:v1.0.0`

---

## 6. Configure the manifests (prod overlay)

Keep the upstream `deploy/aws/eks/` base untouched; put your edits in an overlay
so upstream merges stay clean. The repo ships this overlay at
`deploy/aws/eks-overlays/prod/` (a sibling tree, not nested under the base —
kustomize forbids an overlay from referencing a base that is its own ancestor):

### 6.1 `eks-overlays/prod/kustomization.yaml`

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

resources:
  - ../../eks                   # the upstream base (directory ref; not an ancestor)
  - secretproviderclass.yaml    # not in the base; add it here

# Repoint both images at ECR and pin the release tag.
images:
  - name: ghcr.io/dir-iq/ldapportal-community-plus-isva
    newName: <ACCOUNT_ID>.dkr.ecr.<REGION>.amazonaws.com/ldapportal-community-plus-isva
    newTag: "v1.0.0"
  - name: ghcr.io/dir-iq/ldapportal-frontend
    newName: <ACCOUNT_ID>.dkr.ecr.<REGION>.amazonaws.com/ldapportal-frontend
    newTag: "v1.0.0"

# Layer environment-specific edits as patches (below).
patches:
  - path: configmap-patch.yaml
  - path: serviceaccount-patch.yaml
  - path: backend-csi-patch.yaml
  - path: ingress-patch.yaml
```

### 6.2 `configmap-patch.yaml` — DB endpoint, public origin, entitlement

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: ldapportal-config
data:
  DB_URL: "jdbc:postgresql://<RDS_ENDPOINT>:5432/ldapportal?sslmode=require"
  CORS_ALLOWED_ORIGIN: "https://<HOSTNAME>"
  # Directory Sync ON — self-host grant of the open-source entitlement.
  # (Already set in the base ConfigMap; restated here for clarity.)
  LDAPPORTAL_ENTITLEMENTS_GRANT: "DIRECTORY_SYNC"
```

### 6.3 `serviceaccount-patch.yaml` — IRSA annotation

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: ldapportal
  annotations:
    eks.amazonaws.com/role-arn: arn:aws:iam::<ACCOUNT_ID>:role/ldapportal-secrets-reader
```

### 6.4 `secretproviderclass.yaml` — Secrets Manager → K8s Secret

Copy `deploy/aws/eks/secretproviderclass.example.yaml`, set `region`, and
replace `ACCOUNT_ID` in the four ARNs. It syncs the four Secrets Manager entries
into a Kubernetes Secret named **`ldapportal-secrets`** — the exact name/shape
the backend Deployment already consumes via `envFrom: secretRef`, so no
Deployment env change is needed, only the volume mount below.

### 6.5 `backend-csi-patch.yaml` — mount the SecretProviderClass

The synced Secret is only created once a pod **mounts** the SecretProviderClass
volume. Add the CSI volume + a read-only mount to the backend pod:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ldapportal-backend
spec:
  template:
    spec:
      containers:
        - name: app
          volumeMounts:
            - name: secrets-store
              mountPath: /mnt/secrets-store
              readOnly: true
      volumes:
        - name: secrets-store
          csi:
            driver: secrets-store.csi.k8s.io
            readOnly: true
            volumeAttributes:
              secretProviderClass: ldapportal-aws-secrets
```

> Ordering note: kubelet mounts CSI volumes (which triggers the secret sync)
> before resolving container `envFrom`, so the backend reads the freshly-synced
> `ldapportal-secrets` on first start. If a pod ever races ahead of the sync it
> fails with `CreateContainerConfigError` and kubelet retries until the Secret
> exists — the rollout self-heals.

### 6.6 `ingress-patch.yaml` — cert + hostname

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: ldapportal
  annotations:
    alb.ingress.kubernetes.io/certificate-arn: arn:aws:acm:<REGION>:<ACCOUNT_ID>:certificate/<CERT_ID>
spec:
  tls:
    - hosts:
        - <HOSTNAME>
  rules:
    - host: <HOSTNAME>
      http:
        paths:
          - path: /api/v1
            pathType: Prefix
            backend:
              service: { name: ldapportal-backend, port: { number: 8080 } }
          - path: /
            pathType: Prefix
            backend:
              service: { name: ldapportal-frontend, port: { number: 80 } }
```

---

## 7. Deploy

```bash
# 1. Namespace
kubectl apply -f deploy/aws/eks/namespace.yaml

# 2. (If you did NOT use `eksctl create iamserviceaccount`) ensure the SA gets
#    its IRSA annotation from the overlay; if you DID, the SA already exists and
#    the overlay patch just reconciles the annotation.

# 3. Apply everything through the overlay
kubectl apply -k deploy/aws/eks-overlays/prod

# 4. Watch the backend come up (first boot runs Flyway migrations — up to ~5 min)
kubectl -n ldapportal rollout status deploy/ldapportal-backend
kubectl -n ldapportal get ingress ldapportal   # ADDRESS = the ALB DNS name
```

---

## 8. DNS

Point `<HOSTNAME>` at the ALB:
- **Route 53:** an ALIAS A/AAAA record to the ALB, or a CNAME to the ingress
  `ADDRESS`.

Then browse `https://<HOSTNAME>` and log in as `superadmin` with the
`BOOTSTRAP_SUPERADMIN_PASSWORD` from Secrets Manager. The bootstrap superadmin
goes inert once a permanent local superadmin exists.

---

## 9. Enable & use Directory Sync

The entitlement is already granted (`LDAPPORTAL_ENTITLEMENTS_GRANT=DIRECTORY_SYNC`
in the ConfigMap), so the feature is live at startup — no license file. Confirm
it in the backend log:

```bash
kubectl -n ldapportal logs deploy/ldapportal-backend | grep -i "Entitlements:"
# Entitlements: edition=COMMUNITY granted=[... DIRECTORY_SYNC ...] withheld=[...] source="..."
```

Then configure the actual sync in the UI (source/target directories, schedule).
See `docs/directory-sync-operations.md` for operational detail. Your real
directories (OpenLDAP / AD / Entra / IBM) are configured here — the directory
servers in `compose.yaml` are dev fixtures only.

---

## 10. Verify

```bash
kubectl -n ldapportal get pods,svc,ingress,hpa
kubectl -n ldapportal logs deploy/ldapportal-backend | grep -i "Started .* in"
# in-cluster health:
kubectl -n ldapportal exec deploy/ldapportal-backend -- wget -qO- localhost:8080/actuator/health
# confirm the synced secret materialized:
kubectl -n ldapportal get secret ldapportal-secrets -o jsonpath='{.data}' | tr ',' '\n'
```

---

## 11. Day-2 operations

- **Ship a new version:** tag `vX.Y.Z` in GitLab (builds + pushes to ECR), bump
  `newTag` in `eks-overlays/prod/kustomization.yaml`, `kubectl apply -k` again.
  `maxUnavailable: 0` gives a zero-downtime rollout.
- **Rotate `JWT_SECRET` / `BOOTSTRAP_SUPERADMIN_PASSWORD` / `DB_PASSWORD`:**
  update the value in Secrets Manager; the CSI driver re-syncs the K8s Secret on
  its rotation poll. Restart the backend to pick up new env
  (`kubectl -n ldapportal rollout restart deploy/ldapportal-backend`).
- **Do NOT rotate `ENCRYPTION_KEY`** without the re-encryption runbook (§4) —
  it invalidates stored directory bind passwords.
- **Scaling:** `backend-hpa.yaml` autoscales 2→6 on 70% CPU (needs
  metrics-server); frontend is fixed at 2; PDBs keep ≥1 of each through node
  drains.
- **Migrations:** Flyway runs at startup under a DB advisory lock — replicas and
  rollouts are safe; no separate migration job.

---

## 12. Teardown

```bash
kubectl delete -k deploy/aws/eks-overlays/prod
# ALB is reclaimed when the Ingress is deleted; namespace delete removes the rest.
# RDS, ECR repos, Secrets Manager entries and IAM roles are managed outside the
# manifests — remove them separately if decommissioning.
```
