-- SPDX-License-Identifier: Apache-2.0
--
-- A profile's RDN attribute is required only when the app composes the DN
-- (Automatic mode: "<rdn_attribute>=<value>,<target OU>"). In operator-entered
-- mode a DN template names the entry, or the operator types the whole DN, so
-- the column may be empty. ProvisioningProfileService enforces the rule.
ALTER TABLE provisioning_profiles ALTER COLUMN rdn_attribute DROP NOT NULL;
