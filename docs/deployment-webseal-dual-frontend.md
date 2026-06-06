# Dual-frontend deployment: WebSEAL admins + LOCAL-only superadmins

How to deploy LDAP Portal so that **admins authenticate through a WebSEAL
junction (SSO)** while **superadmins use LOCAL password auth on a separate,
non-WebSEAL path**. This is the supported way to satisfy "admins must use
WebSEAL, but superadmins are necessarily local-auth-only."

This doc is the topology/runbook layer. For configuring the WebSEAL feature
itself (junction flags, the Trusted Proxies form, header names, logout URL),
see the in-app **WebSEAL Configuration Guide** (Settings → Authentication →
WebSEAL help), which is the authoritative source for the per-field settings.

---

## 1. Why this topology is correct (not a workaround)

Superadmins are **LOCAL-only by design** — the break-glass recovery account
must not depend on any external IdP. Two facts enforced in code make the split
safe *by construction*:

- **The WebSEAL path cannot mint a superadmin token.**
  `WebSealAuthenticationService.authenticate()` only matches accounts whose
  `authType == WEBSEAL` (`core/.../auth/WebSealAuthenticationService.java`).
  Superadmins are `LOCAL`-typed, so even a forged `iv-user` naming a superadmin
  resolves to `BadCredentialsException`. No junction configuration can produce a
  superadmin session.

- **The superadmin LOCAL path is independent of WebSEAL/IdP.**
  `AuthenticationService.login()` branches on `account.getAuthType()` and does a
  bcrypt check against the stored hash — no proxy, no IdP round-trip. When
  WebSEAL or the IdP is down, admins are locked out but **superadmins can still
  log in and remediate.** That is the entire point of the break-glass account,
  and this topology makes it explicit.

The two-frontend split is therefore a **network/UX boundary, not an
authorization boundary.** Authorization is always enforced server-side from the
JWT (`@PreAuthorize`, `@Entitled`, `PermissionService`). Do not rely on "it's a
separate frontend" for any access-control guarantee — rely on it only to route
the two user populations down the right network path.

---

## 2. Topology

```
                         ┌────────────────────────────────────────┐
  Admins (SSO)           │  WebSEAL junction                       │
  ───────────────►       │  - terminates IdP auth                  │
                         │  - injects iv-user / iv-groups          │   peer IP = JUNCTION
                         │  - serves admin SPA + proxies /api/v1   ├──────────────┐
                         └────────────────────────────────────────┘              │
                                                                                  ▼
                                                                        ┌───────────────────┐
                                                                        │  LDAP Portal       │
                                                                        │  backend           │
                                                                        │                    │
                                                                        │ trusts iv-user ONLY│
                                                                        │ when getRemoteAddr │
                                                                        │ ∈ Trusted Proxies  │
                                                                        └───────────────────┘
                         ┌────────────────────────────────────────┐              ▲
  Superadmins (LOCAL)    │  Superadmin ingress (NO WebSEAL)        │   peer IP = NOT TRUSTED
  ───────────────►       │  - serves superadmin SPA + proxies      ├──────────────┘
                         │    /api/v1                              │
                         │  - STRIPS any inbound iv-* header       │
                         └────────────────────────────────────────┘
```

The **single security invariant** the whole design reduces to:

> Only traffic that genuinely traversed the WebSEAL junction may present a
> trusted `getRemoteAddr()` to the backend.

`WebSealAuthenticationService` trusts the `iv-user` header solely on the basis
of the **immediate TCP peer IP** vs the configured **Trusted Proxies** CIDR
list. `X-Forwarded-For` is deliberately ignored. So the admin junction's peer
IP is allow-listed; the superadmin ingress's peer IP must **not** be.

---

## 3. Recommended configuration

### 3.1 Backend (single shared instance)

Both frontends talk to **one** backend. Settings that matter:

| Setting | Value | Why |
|---|---|---|
| Enabled login methods (`enabledAuthTypes`) | `LOCAL` **and** `WEBSEAL` | Global, not per-path. LOCAL is required for superadmins; WEBSEAL for admins. Keep LOCAL enabled — the UI guards against unticking it. |
| Trusted Proxies (`websealTrustedProxies`) | **Only** the WebSEAL junction's peer IP/CIDR | The allow-list is the runtime trust gate. Empty = WEBSEAL disabled (fail-closed). |
| User Header (`websealUserHeader`) | `iv-user` (default) | Override only if the junction emits a custom header name. |
| Groups Header (`websealGroupsHeader`) | `iv-groups` (default) | Audit-only; never used for authz. |
| Logout URL (`websealLogoutUrl`) | `/pkmslogout` (default) | Admin logout redirects here to clear the WebSEAL session. |
| Cookie secure (`app.cookie.secure`) | `true` | Production must be HTTPS end-to-end. Only set `false` for local plain-HTTP dev. |

**Do not put any intermediate proxy/LB between WebSEAL and the backend that
would NAT the peer IP** (see §5). The Trusted Proxies list must contain the
*actual* `getRemoteAddr()` the backend observes.

### 3.2 Admin frontend (behind WebSEAL)

- Served by / behind the WebSEAL junction. The junction terminates IdP auth and
  injects `iv-user` / `iv-groups` (`-c iv-user,iv-groups` on the junction).
- The junction must **overwrite** any client-supplied `iv-*` header from its own
  authenticated session — never pass an inbound one through.
- On `/login`, the admin SPA silently probes `GET /api/v1/auth/webseal/authorize`
  and is auto-signed-in when the trusted `iv-user` header is present.
- Serve the SPA assets and proxy `/api/v1` under the **same hostname** as the
  junction (see §3.4 for why).

### 3.3 Superadmin frontend (NOT behind WebSEAL)

- A separate ingress/hostname that does **not** route through WebSEAL.
- Its peer IP to the backend must **not** be in Trusted Proxies.
- It must **strip inbound `iv-*` headers** as defence-in-depth, e.g.:
  - nginx: `proxy_set_header iv-user ""; proxy_set_header iv-groups "";`
  - HAProxy: `http-request del-header iv-user` / `http-request del-header iv-groups`
- Superadmins reach the normal `/login` form and authenticate via LOCAL
  (`POST /api/v1/auth/login`), which never consults `iv-user`.
- Recommended: keep this ingress on a restricted/admin network (VPN, bastion,
  internal-only), since it is the privileged break-glass entry point.
- Optional hardening: do **not** expose `/api/v1/auth/webseal/authorize` on this
  host. It's harmless when the peer isn't trusted, but removing it keeps the
  spoofing surface off the more-privileged frontend.

### 3.4 Origin & cookie model (important)

The session JWT is issued as a cookie with these attributes
(`core/.../controller/AuthController.java`):

- name `jwt`, `HttpOnly`, `Secure` (when `app.cookie.secure=true`)
- **`SameSite=Strict`**
- **`Path=/api/v1`**
- **no `Domain` attribute** → the cookie is *host-only*

Consequences for a two-frontend layout:

1. **Serve each SPA same-origin with the API it calls.** Each frontend host
   should serve its SPA assets *and* reverse-proxy `/api/v1` to the shared
   backend under that same hostname. With `SameSite=Strict` and a host-only
   cookie, a cross-origin SPA→API call would not carry the session cookie and
   would fail. Same-origin per frontend also means **no CORS** is required.
2. **Two hostnames ⇒ two independent sessions.** The admin UI and superadmin UI
   each get their own `jwt` cookie scoped to their own host. Logging into one
   does not create a session on the other, and the WebSEAL SSO session never
   leaks to the superadmin host. This isolation is desirable here.

So the clean shape is:

```
admin.example.com      → (WebSEAL junction) → SPA + /api/v1  ─┐
                                                              ├─► shared backend
sa.example.com         → (direct ingress)   → SPA + /api/v1  ─┘
```

---

## 4. Account provisioning

- **Admins:** create with **Auth Type = WEBSEAL** and a **username matching the
  exact `iv-user` value** WebSEAL will send. No auto-provisioning — an admin
  arriving via WebSEAL without a pre-created matching account gets a 401.
  Assign profile roles as usual.
- **Superadmins:** created via the dedicated superadmin flow; **always LOCAL**
  (the Auth Type selector is intentionally hidden for the Superadmin role).
  Keep **at least two** active LOCAL superadmins so routine offboarding never
  trips the "cannot remove the last active LOCAL break-glass superadmin" guard.

---

## 5. Anti-patterns / failure modes

**Shared ingress that collapses the peer IP (the dangerous one).**
If both the WebSEAL junction and the superadmin ingress reach the backend
through the *same* load-balancer / ingress-controller / service-mesh sidecar,
then `getRemoteAddr()` is identical for both paths. That shared IP is in Trusted
Proxies (it must be, for admins). An attacker who can reach the *superadmin*
(non-WebSEAL) frontend could then send a forged `iv-user: someadmin` to
`/api/v1/auth/webseal/authorize` and be issued an **admin token with no
credentials.**

Avoid by ensuring the two paths present **distinct immediate peer IPs**:
separate listeners/sidecars/source IPs, allow-list only the junction, and strip
`iv-*` on the superadmin path. If you cannot separate them at L3/L4, you must
strip `iv-*` on every non-WebSEAL route into the backend.

**Putting the superadmin path behind WebSEAL "just to be consistent."**
This re-creates the lockout the design avoids: if the IdP/WebSEAL is down,
superadmins can't reach the app to fix it. Keep the superadmin path
IdP-independent.

**Widening Trusted Proxies to make production work.**
"Works in dev, fails in prod" is almost always an intermediate hop changing the
peer IP. The fix is to collapse the hop or scope the allow-list to the real
peer — *not* to add a broad subnet, which would trust spoofed headers.

**Treating the frontend split as access control.**
An admin pointing a browser at the superadmin host (or calling superadmin APIs
directly) is still bounded by the server-side role checks, and the split adds no
authorization. Don't reason about privilege from which frontend was used.

---

## 6. Verification checklist

Network / trust:

- [ ] From the **superadmin** host, `curl -H 'iv-user: <an-admin>' \
      https://sa.example.com/api/v1/auth/webseal/authorize` returns **401**
      (proves that path's peer IP is not trusted and/or `iv-*` is stripped).
- [ ] From a host **outside** WebSEAL, a direct request to the backend with a
      forged `iv-user` is rejected (peer not in Trusted Proxies).
- [ ] Backend access logs show the WebSEAL **junction** IP as the peer on the
      admin path (not an intermediate LB).

Admin (WebSEAL) flow:

- [ ] Incognito → admin host → IdP login → lands on dashboard auto-signed-in.
- [ ] An IdP user with **no** pre-provisioned WEBSEAL account gets 401.
- [ ] Logout redirects to `/pkmslogout` and terminates the WebSEAL session.

Superadmin (LOCAL) flow:

- [ ] Superadmin host shows the normal login form and accepts LOCAL credentials.
- [ ] With WebSEAL/IdP **stopped**, a superadmin can still log in on the
      superadmin host (break-glass intact).
- [ ] The superadmin host issues its own `jwt` cookie; the admin SSO session
      does not appear there.

---

## 7. Break-glass operations

- Keep ≥2 active LOCAL superadmins; the backend refuses to delete/deactivate the
  last active LOCAL superadmin.
- Document the superadmin host URL and its restricted-network access path in
  your runbook — it is the recovery entry point when SSO is unavailable.
- Never disable the LOCAL login method globally; the UI warns before allowing
  it, and disabling it would strand both admins and superadmins behind a broken
  SSO path.
</content>
</invoke>
