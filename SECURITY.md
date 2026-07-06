# Security Policy

LDAP Portal handles directory credentials, personal data, and privileged
directory write access, so we take vulnerability reports seriously.

## Reporting a vulnerability

**Please do not open a public issue for security problems.**

Report privately via
[GitHub private vulnerability reporting](https://github.com/dir-IQ/ldapportal-core/security/advisories/new)
on this repository. If that is not an option, email the maintainer listed in
the root `pom.xml`.

Include what you can of the following:

- Affected component (backend module, frontend, Docker image, deploy config)
  and version or commit.
- Reproduction steps or a proof of concept.
- Impact assessment — what an attacker gains.

You should receive an acknowledgement within a few business days. Please
allow a reasonable disclosure window for a fix to land and ship before
publishing details.

## Supported versions

The project is pre-1.0. Only the latest release (and `main`) receive security
fixes.

## Scope notes

- Secrets (`ENCRYPTION_KEY`, `JWT_SECRET`, `DB_PASSWORD`,
  `BOOTSTRAP_SUPERADMIN_PASSWORD`) are supplied via environment variables and
  are required at startup; reports about missing-default behavior for these
  are working as intended.
- Container images are scanned with Trivy in CI and the repository is scanned
  with gitleaks; issues found by those tools on unmodified `main` are likely
  already known.
