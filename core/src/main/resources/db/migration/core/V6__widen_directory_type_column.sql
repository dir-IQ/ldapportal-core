-- SPDX-License-Identifier: Apache-2.0
--
-- Hotfix on top of P1 of the OUD support work. The directory_type
-- column was sized at varchar(20) — exactly long enough for the
-- previous longest enum value (IBM_DIRECTORY_SERVER, 20 chars). P1
-- added ORACLE_UNIFIED_DIRECTORY (24 chars), which overflowed the
-- column on the first attempt to save an OUD-typed directory:
--
--   ERROR: value too long for type character varying(20)
--
-- Widen to varchar(40) — gives headroom for any near-future enum
-- additions without another migration. The enum is converted to
-- string by the default JPA EnumType.STRING mapping; the column
-- just needs to hold the longest enum name.

ALTER TABLE directory_connections
    ALTER COLUMN directory_type TYPE varchar(40);
