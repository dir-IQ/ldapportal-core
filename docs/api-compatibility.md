# LDAP Portal REST API compatibility policy

LDAP Portal commits to a strict additive-only semver model for its REST API. This document defines what "stable" means, what changes within a major version are guaranteed safe for consumers, and what breaking-change path exists when it's genuinely needed.

**Effective date:** 2026-04-24 (aspirational until the first external consumer — Feature 5 Terraform provider — ships; binding from that point forward).

## Where to find the contract

- **Spec (YAML):** `GET /api/v1/openapi.yaml` — no authentication required.
- **Spec (JSON):** `GET /api/v1/openapi` — same.
- **Interactive UI:** `/swagger-ui.html` — SUPERADMIN-authenticated. The UI is an operator tool that lets you execute calls; the spec at `/api/v1/openapi.yaml` is the contract.

## The promise

Code written against `v1` works indefinitely. We commit to:

- Every endpoint the spec documents continues to exist at its documented path under `/api/v1/` and return the documented HTTP status codes for the documented outcomes.
- Every request field the spec documents as accepted continues to be accepted with the same shape and semantics.
- Every response field the spec documents as returned continues to be returned with the same shape and semantics.
- Every auth requirement continues to apply unchanged.
- Every enum value documented in the spec continues to be a valid value.

## Allowed changes within v1 (additive)

These changes ship in any release without notice. Consumer code that tolerates unknown fields (any competently-written JSON client) is forward-compatible.

- New endpoints (new paths under `/api/v1/`).
- New optional fields on request bodies.
- New fields on response bodies.
- New values on existing enums (e.g. new `OutboundEventType.wireName` values, new `AuditAction` values).
- New optional query parameters on existing endpoints.
- New response headers.
- New optional request headers.
- Internal implementation changes (performance, storage, service decomposition, ordering semantics where not contract-specified).
- New error-detail fields on `ProblemDetail` responses.

## Forbidden changes within v1

These changes require a new major version. We will not ship them in v1 under any circumstance.

- Remove an endpoint.
- Rename a request or response field.
- Rename an enum value.
- Remove an enum value.
- Change a request field from optional to required, or a response field from required to optional.
- Narrow a type constraint (e.g. tighten a regex, shorten a max-length, reduce an integer range).
- Change the HTTP status code returned for an existing outcome.
- Change the authentication requirement of an existing endpoint.
- Change the JSON shape of an existing nested object (e.g. flattening a nested `{actor:{id,username}}` into `{actorId, actorUsername}`).
- Change the meaning of an existing field without changing its name.

## Making a breaking change

Breaking changes ship as a parallel `v2` alongside `v1`:

- `v2` endpoints live under `/api/v2/...`. The same controller class can produce both versions via separate `@RequestMapping` methods.
- `v2` may reuse, rename, or restructure fields freely.
- `v1` keeps working indefinitely after `v2` ships. No deprecation window, no removal plan.
- `v2` is announced quarterly at the earliest. Minor-version LDAP Portal releases never introduce a new major API version.

Consumers are free to migrate on their own timeline. A `v1` consumer will never receive a breaking response because we shipped `v2`.

## Outbound event payloads

Outbound events emitted via the event backbone (Prereq B) carry an explicit `schemaVersion` field inside each envelope. That field follows the same additive-only rule at `schemaVersion: 1`. See [`superpowers/specs/2026-04-23-event-backbone-design.md`](superpowers/specs/2026-04-23-event-backbone-design.md).

## Authentication

Every endpoint documented in `/api/v1/openapi.yaml` enforces one of three authentication schemes:

- **Cookie JWT** (`jwt` httpOnly cookie) — issued by `POST /api/v1/auth/login`.
- **`Authorization: Bearer <JWT>`** — same JWT used via the Authorization header; equivalent to the cookie for API clients.
- **`Authorization: Bearer ldap_pat_...`** — long-lived API tokens. See [`superpowers/specs/2026-04-22-api-token-auth-design.md`](superpowers/specs/2026-04-22-api-token-auth-design.md).

Individual endpoints declare their required role (SUPERADMIN / ADMIN / SELF_SERVICE) via Spring Security URL patterns; the OpenAPI spec reflects the security requirement at the operation level.

## Pre-publication caveat

Until Feature 5 (the Terraform provider) ships, there are no external `v1` consumers. During that window the policy is aspirational — incompatible refactors can land if they improve the contract before it's carved in stone. From the moment the Terraform provider is announced, the policy is binding and additive-only.

This caveat will be removed from this doc when Feature 5 ships. Git history records the transition.
