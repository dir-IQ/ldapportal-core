-- Additional profiles: stacking group memberships across profiles

-- Join table for explicit additional profile relationships
CREATE TABLE profile_additional_profiles (
    profile_id            UUID NOT NULL REFERENCES provisioning_profiles(id) ON DELETE CASCADE,
    additional_profile_id UUID NOT NULL REFERENCES provisioning_profiles(id) ON DELETE CASCADE,
    PRIMARY KEY (profile_id, additional_profile_id),
    CONSTRAINT chk_no_self_ref CHECK (profile_id != additional_profile_id)
);

-- Flag: when true, this profile's groups are auto-included in every other profile in the same directory
ALTER TABLE provisioning_profiles
    ADD COLUMN auto_include_groups BOOLEAN NOT NULL DEFAULT FALSE;

-- Flag: when true, this profile opts out of receiving auto-included groups
ALTER TABLE provisioning_profiles
    ADD COLUMN exclude_auto_includes BOOLEAN NOT NULL DEFAULT FALSE;
