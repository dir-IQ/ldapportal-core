-- SPDX-License-Identifier: Apache-2.0
--
-- Directories with several top-level roots (suffixes / naming contexts) —
-- e.g. an OUD or OpenLDAP server with two suffixes, or an IBM directory with a
-- user suffix plus IVIA's secAuthority=Default — needed one connection per
-- root. base_dn stays the primary root (every existing default and caller is
-- unchanged); additional_base_dns lists the other roots, one DN per line.
-- Searches with no explicit base cover every root, the browser shows each
-- root, and scope checks accept a DN under any of them.

ALTER TABLE directory_connections ADD COLUMN additional_base_dns TEXT;
