// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.AdminFeaturePermission;
import com.ldapportal.entity.enums.FeatureKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AdminFeaturePermissionRepository extends JpaRepository<AdminFeaturePermission, UUID> {

    List<AdminFeaturePermission> findAllByAdminAccountId(UUID adminAccountId);

    /**
     * @deprecated Prefer {@link #findAdminWideOverride(UUID, FeatureKey)} or
     * {@link #findProfileOverride(UUID, UUID, FeatureKey)} — the no-scope
     * version can't tell admin-wide and per-profile overrides apart and may
     * return either. Kept for backwards-compatible callers that only consult
     * admin-wide overrides.
     */
    @Deprecated
    Optional<AdminFeaturePermission> findByAdminAccountIdAndFeatureKey(UUID adminAccountId, FeatureKey featureKey);

    /** Returns the admin-wide override for this (admin, feature), if any. */
    @Query("SELECT p FROM AdminFeaturePermission p " +
           "WHERE p.adminAccount.id = :adminId AND p.featureKey = :feature AND p.profile IS NULL")
    Optional<AdminFeaturePermission> findAdminWideOverride(@Param("adminId") UUID adminId,
                                                            @Param("feature") FeatureKey feature);

    /** Returns the profile-scoped override for this (admin, profile, feature), if any. */
    @Query("SELECT p FROM AdminFeaturePermission p " +
           "WHERE p.adminAccount.id = :adminId AND p.profile.id = :profileId AND p.featureKey = :feature")
    Optional<AdminFeaturePermission> findProfileOverride(@Param("adminId") UUID adminId,
                                                          @Param("profileId") UUID profileId,
                                                          @Param("feature") FeatureKey feature);

    /** All admin-wide overrides for the account — used by the effective-permissions viewer. */
    @Query("SELECT p FROM AdminFeaturePermission p " +
           "WHERE p.adminAccount.id = :adminId AND p.profile IS NULL")
    List<AdminFeaturePermission> findAdminWideOverrides(@Param("adminId") UUID adminId);

    /** All profile-scoped overrides for the account. */
    @Query("SELECT p FROM AdminFeaturePermission p " +
           "WHERE p.adminAccount.id = :adminId AND p.profile IS NOT NULL")
    List<AdminFeaturePermission> findProfileOverrides(@Param("adminId") UUID adminId);

    boolean existsByAdminAccountIdAndFeatureKeyAndEnabledTrue(UUID adminAccountId, FeatureKey featureKey);

    /** Clears the admin-wide override (profile IS NULL) for the given (admin, feature). */
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM AdminFeaturePermission p " +
           "WHERE p.adminAccount.id = :adminId AND p.featureKey = :feature AND p.profile IS NULL")
    void deleteAdminWideOverride(@Param("adminId") UUID adminId, @Param("feature") FeatureKey feature);

    /** Clears the per-profile override for the given (admin, profile, feature). */
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM AdminFeaturePermission p " +
           "WHERE p.adminAccount.id = :adminId AND p.profile.id = :profileId AND p.featureKey = :feature")
    void deleteProfileOverride(@Param("adminId") UUID adminId,
                                @Param("profileId") UUID profileId,
                                @Param("feature") FeatureKey feature);

    @Deprecated
    void deleteByAdminAccountIdAndFeatureKey(UUID adminAccountId, FeatureKey featureKey);

    void deleteAllByAdminAccountId(UUID adminAccountId);
}
