-- Add optimistic-locking version column to provisioning_profiles.
--
-- Two superadmins concurrently editing the same profile previously
-- produced a torn write with no detection: the update path is "delete
-- all attribute configs, flush, reinsert" + "delete all group
-- assignments, flush, reinsert" + replace additional profiles, so
-- whichever transaction committed last quietly stomped the other's
-- changes. JPA's @Version makes Hibernate include the column in the
-- UPDATE's WHERE clause and raise ObjectOptimisticLockingFailureException
-- (mapped to 409 in GlobalExceptionHandler) when the row has moved
-- under the caller.
--
-- Default 0 backfills existing rows. NOT NULL because @Version on a
-- Long field is allowed to be null only for transient entities; rows
-- already in the table must have a starting value.

ALTER TABLE provisioning_profiles
    ADD COLUMN version bigint NOT NULL DEFAULT 0;
