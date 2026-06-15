-- SPDX-License-Identifier: Apache-2.0
-- Password disposition for provisioning profiles: how a new user's password is
-- sourced and handled. OPERATOR_ENTERED (default) preserves today's behaviour —
-- the operator types/generates the value in the visible field. The two
-- GENERATED_* modes have the server generate the password at create time:
-- GENERATED_DELIVERED emails it to the user, GENERATED_DISCARDED writes a
-- throwaway purely to satisfy a schema-required userPassword for accounts that
-- authenticate by other means (e.g. client certificate) and surfaces it nowhere.
ALTER TABLE provisioning_profiles
    ADD COLUMN password_disposition varchar(32) NOT NULL DEFAULT 'OPERATOR_ENTERED';
