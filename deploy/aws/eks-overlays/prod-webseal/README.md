<!-- SPDX-License-Identifier: Apache-2.0 -->
# Dual-frontend WebSEAL overlay — EKS + ECR + Secrets Manager

Deploys LDAP Portal with **two frontends on two hostnames** sharing one backend:

```
admin.example.com  → (external WebSEAL junction) → admin frontend ──┐  passes iv-user
                       internet-facing ALB                          ├─► shared backend
sa.example.com     → INTERNAL ALB (VPN/bastion)  → superadmin FE ───┘  strips iv-*
```

- **Admins** authenticate via the WebSEAL junction (SSO); the admin frontend
  passes the injected `iv-user` through to the backend.
- **Superadmins** authenticate via LOCAL password on a separate, non-WebSEAL,
  internal-only path; the superadmin frontend **strips** `iv-*`.

Each frontend serves its SPA **and** proxies `/api/v1` to the backend, so the
backend's peer IP (`getRemoteAddr()`) is the *frontend pod*, not the shared
ALB. This, plus the `iv-*` strip, is what keeps the two populations' trust
separate. Design rationale: [`docs/deployment-webseal-dual-frontend.md`](../../../docs/deployment-webseal-dual-frontend.md).

Same ECR + AWS Secrets Manager setup as the [`prod`](../prod/) overlay (IRSA,
CSI mount, SecretProviderClass), but replaces the single frontend + single
ingress with two of each. It is **self-contained** — it references the base
directory (`../../eks`) and carries its own copies of the IRSA/CSI/secret
files, so everything you edit for a WebSEAL deploy lives in this directory.

## Peer-IP model: "iv-* strip only"

This overlay does **not** separate the frontends onto distinct node groups.
Both frontends share the pod subnet, so the backend's **Trusted Proxies** must
allow-list that whole CIDR — which includes the superadmin pods too. That is
safe **only** because the superadmin nginx strips `iv-*`
(`superadmin-frontend-nginx-configmap.yaml`). **Do not remove the strip**, and
do not point `/api/v1` straight at the backend from either ingress — both would
break the trust boundary (see the anti-patterns in the design doc §5).

## Files

| File | Purpose |
|---|---|
| `kustomization.yaml` | References the base (`../../eks`), removes its single frontend + ingress, adds the dual frontends + ingresses, repoints images to ECR. |
| `remove-base-single-frontend.yaml` | `$patch: delete` for the base's single frontend (deployment/service/pdb/nginx ConfigMap) and ingress. |
| `admin-frontend-*` | Admin SPA deployment/service/pdb + nginx ConfigMap (passes iv-*). |
| `superadmin-frontend-*` | Superadmin SPA deployment/service/pdb + nginx ConfigMap (strips iv-*). |
| `admin-ingress.yaml` | Internet-facing ALB, `admin.example.com`, all paths → admin frontend. |
| `superadmin-ingress.yaml` | **Internal** ALB, `sa.example.com`, all paths → superadmin frontend. |
| `configmap-patch.yaml` | Backend DB/CORS/entitlement (env-settable values only). |
| `serviceaccount-patch.yaml` / `backend-csi-patch.yaml` / `secretproviderclass.yaml` | IRSA + AWS Secrets Manager wiring (local copies). |

## Edit before applying

- `ACCOUNT_ID` / `us-east-1` / `v1.0.0` — ECR registry, region, image tag (`kustomization.yaml`).
- `REPLACE_RDS_ENDPOINT` — RDS host (`configmap-patch.yaml`).
- `admin.example.com` / `sa.example.com` — your two hostnames (both ingresses; admin also in CORS).
- `REPLACE_ME` (cert id) on **both** ingresses — ACM certs for each hostname (or one SAN cert).
- IRSA role ARN (`serviceaccount-patch.yaml`) and the secret ARNs / region (`secretproviderclass.yaml`).

## Prerequisites (beyond the base/prod ones)

- **Internal-scheme ALB subnets** tagged `kubernetes.io/role/internal-elb=1`
  (the superadmin ingress needs them); public subnets tagged
  `kubernetes.io/role/elb=1` for the admin ingress.
- The **external WebSEAL junction** configured to front `admin.example.com`,
  terminate IdP auth, inject `iv-user`/`iv-groups` (`-c iv-user,iv-groups`), and
  **overwrite** any client-supplied `iv-*` from its own session.
- Network path from your admin/VPN network to the **internal** ALB for the
  superadmin host.

## Apply

```bash
kubectl apply -f deploy/aws/eks/namespace.yaml          # once
kubectl apply -k deploy/aws/eks-overlays/prod-webseal   # this overlay
kubectl -n ldapportal rollout status deploy/ldapportal-backend
kubectl -n ldapportal get ingress                       # two ADDRESSes: admin + superadmin
```

Point DNS: `admin.example.com` → the WebSEAL junction (which forwards to the
internet-facing ALB); `sa.example.com` → the internal ALB (private record).

## Post-deploy (the trust config — REQUIRED, not in YAML)

The WebSEAL trust settings are DB/UI-persisted, so set them once after first
boot:

1. Browse the **superadmin** host (`https://sa.example.com`) and log in as
   `superadmin` (LOCAL, password from Secrets Manager).
2. **Settings → Authentication → WebSEAL:**
   - Enable the **WEBSEAL** login method (keep **LOCAL** enabled — required for
     superadmins; the UI guards against unticking it).
   - **Trusted Proxies** = the **pod subnet CIDR(s)** your frontends run in
     (with the VPC CNI, pods take VPC IPs from the node ENI subnets). This is
     the immediate peer the backend sees from the frontend nginx. It will
     include the superadmin pods too — safe because they strip `iv-*`.
   - Header names default to `iv-user` / `iv-groups`; change only if your
     junction emits custom names.
   - Logout URL defaults to `/pkmslogout`.
3. **Pre-create admin accounts** with **Auth Type = WEBSEAL** and username =
   the exact `iv-user` value WebSEAL sends (no auto-provisioning).
4. Keep **≥2 active LOCAL superadmins** (break-glass; the backend refuses to
   remove the last one).

## Verify

```bash
# From the superadmin host, a forged iv-user must NOT mint an admin token:
curl -H 'iv-user: someadmin' https://sa.example.com/api/v1/auth/webseal/authorize   # → 401

# Admin SSO: incognito → admin host → IdP login → auto-signed-in.
# Break-glass: with WebSEAL/IdP stopped, a superadmin can still log in on sa host.
```

See the design doc's §6 checklist for the full verification matrix.
