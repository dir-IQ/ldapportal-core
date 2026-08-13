-- SPDX-License-Identifier: Apache-2.0
--
-- Add the secLoginType value written to every secUser entry. IBM's stock
-- secUser objectClass lists secLoginType as a MUST attribute (alongside
-- secAuthority), so provisioning against a real IVIA directory fails with an
-- object-class violation ("missing attribute secLoginType which is required
-- by object class secUser") when it isn't written. The value is
-- deployment-varying, so it's configurable like sec_authority.
--
-- Forward-only, additive. Existing rows backfill to the column DEFAULT
-- ('Default'), matching a vanilla ISVA install — the same default
-- sec_authority carries.

ALTER TABLE vendor_integration_isva_config
    ADD COLUMN sec_login_type VARCHAR(255) DEFAULT 'Default';
