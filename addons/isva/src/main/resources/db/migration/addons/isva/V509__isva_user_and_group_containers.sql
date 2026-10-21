-- SPDX-License-Identifier: Apache-2.0
--
-- The IVIA management DIT gets explicit containers for users and groups.
--
-- management_dit_base_dn used to be where linked-mode secUser entries were
-- created (directly under it), so deployments pointed it at the users
-- container, e.g. cn=Users,secAuthority=Default,<suffix>. It now names the
-- IVIA management DIT itself (secAuthority=Default,<suffix>); secUser
-- entries go in user_container_dn and secGroup registration entries (an
-- IVIA "group import") in group_container_dn. Either container left NULL
-- defaults to cn=Users / cn=Groups below the management DIT.
--
-- Existing rows keep provisioning exactly where they did:
--   * a value of the form cn=Users,<root> becomes management DIT <root>, with
--     the user container left to default (cn=Users,<root>, the same DN);
--   * any other value is kept as the management DIT and also pinned as the
--     user container, so secUser entries still land directly under it.
-- The group container is left to default in both cases.

ALTER TABLE vendor_integration_isva_config ADD COLUMN user_container_dn TEXT;
ALTER TABLE vendor_integration_isva_config ADD COLUMN group_container_dn TEXT;

UPDATE vendor_integration_isva_config
   SET user_container_dn = management_dit_base_dn
 WHERE management_dit_base_dn IS NOT NULL
   AND lower(management_dit_base_dn) NOT LIKE 'cn=users,%';

UPDATE vendor_integration_isva_config
   SET management_dit_base_dn = trim(substring(management_dit_base_dn
                                     FROM position(',' IN management_dit_base_dn) + 1))
 WHERE lower(management_dit_base_dn) LIKE 'cn=users,%';
