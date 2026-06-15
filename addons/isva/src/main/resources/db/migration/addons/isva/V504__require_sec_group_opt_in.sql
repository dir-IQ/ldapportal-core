-- SPDX-License-Identifier: Apache-2.0
-- require_sec_group was persisted (DEFAULT TRUE) but never enforced: no code
-- read it, so every deployment's observed behavior was "off" regardless of
-- the stored value. Enforcement lands in the same release as this migration —
-- flipping it on retroactively for rows that defaulted to TRUE would suddenly
-- refuse membership writes to every non-secGroup group. Reset all existing
-- rows to FALSE (preserving observed behavior) and make the flag opt-in
-- going forward; operators who want the gate turn it on deliberately in the
-- IVIA config panel.
UPDATE vendor_integration_isva_config SET require_sec_group = FALSE;
ALTER TABLE vendor_integration_isva_config
    ALTER COLUMN require_sec_group SET DEFAULT FALSE;
