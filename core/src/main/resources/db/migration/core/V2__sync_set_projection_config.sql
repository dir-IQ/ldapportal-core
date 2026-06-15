-- SPDX-License-Identifier: Apache-2.0
--
-- Phase 1 of the membership/sync engine: the projection + selection config the
-- engine reads to turn a source entry into its desired target state. These are
-- the minimal columns the engine consumes; the rich management surface
-- (DTO/validation/controller/UI, brownfield adoption) lands in Phase 2 on top of
-- them. See docs/plans/2026-06-06-sync-engine-implementation-plan.md.

ALTER TABLE sync_set
    -- Placement: the target base DN that the source base (object_scope_base_dn)
    -- is rewritten to. Null => identity placement (target DN == source DN).
    ADD COLUMN target_base_dn          VARCHAR(500),
    -- Membership predicate evaluated in-engine against the read entry. An
    -- RFC 4515 filter for Phase 1 (objectClass + simple expressions); Phase 2
    -- generalizes it. Null => every entry in scope is a member.
    ADD COLUMN applicability_filter    VARCHAR(2000),
    -- Comma-separated DN-valued attributes used for reference remapping and
    -- closure (member, uniqueMember, manager, owner, secDN, ...). Null => the
    -- engine's built-in default set.
    ADD COLUMN reference_attributes    VARCHAR(1000),
    -- Attribute the normalized source identity is stamped onto every target
    -- entry as, for brownfield correlation (the sourceAnchor). Null => not
    -- written (brownfield adoption is Phase 2).
    ADD COLUMN source_anchor_attribute VARCHAR(255),
    -- What to do when a tracked entry leaves membership: DELETE the target, or
    -- hold for REVIEW. Phase 1 acts on DELETE; REVIEW quarantine is Phase 2.
    ADD COLUMN delete_policy           VARCHAR(20)  NOT NULL DEFAULT 'DELETE',
    -- Optional attribute rename / value-template rules, as a JSON array of
    -- {sourceAttr, targetAttr, valueTemplate}. Null/empty => identity transform
    -- (copy user attributes through unchanged).
    ADD COLUMN transform_rules         JSONB,
    ADD CONSTRAINT sync_set_delete_policy_check
        CHECK (delete_policy IN ('DELETE','REVIEW'));
