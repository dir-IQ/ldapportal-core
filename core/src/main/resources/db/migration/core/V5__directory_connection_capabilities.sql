-- SPDX-License-Identifier: Apache-2.0
--
-- P2 of the OUD support work — capability probe persistence.
-- A jsonb column to hold the DirectoryCapabilities snapshot
-- (vendorName, vendorVersion, supportedControls, supportedExtensions,
-- supportedSaslMechanisms, namingContexts, probedAt) populated at
-- connect-time by LdapCapabilityProbeService. Nullable because the
-- probe is best-effort: Entra ID has no LDAP root DSE, and an LDAP
-- probe that fails (server hides root DSE under bind RBAC, transient
-- network) saves the directory without capabilities rather than
-- aborting. UI surfaces the badge only when present.

ALTER TABLE directory_connections
    ADD COLUMN capabilities jsonb;

COMMENT ON COLUMN directory_connections.capabilities IS
    'Root-DSE capability snapshot — vendor / version / supported control + extension OIDs. '
    'Probed by LdapCapabilityProbeService at connect-time. Nullable; absent means probe '
    'was skipped (Entra ID) or failed.';
