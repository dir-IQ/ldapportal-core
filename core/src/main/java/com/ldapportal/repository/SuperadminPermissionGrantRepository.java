// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.SuperadminPermissionGrant;
import com.ldapportal.entity.enums.SuperadminPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SuperadminPermissionGrantRepository extends JpaRepository<SuperadminPermissionGrant, UUID> {

    /** All permission grants held by a superadmin account. */
    List<SuperadminPermissionGrant> findAllByAccountId(UUID accountId);

    boolean existsByAccountIdAndPermission(UUID accountId, SuperadminPermission permission);

    void deleteAllByAccountId(UUID accountId);

    /**
     * Number of active superadmin accounts that hold a given permission.
     * Used to enforce the "never remove the last owner" invariant — an owner is
     * a superadmin with {@link SuperadminPermission#MANAGE_SUPERADMINS}.
     */
    @Query("""
            SELECT COUNT(DISTINCT g.account.id) FROM SuperadminPermissionGrant g
            WHERE g.permission = :permission
              AND g.account.role = com.ldapportal.entity.enums.AccountRole.SUPERADMIN
              AND g.account.active = true
            """)
    long countActiveAccountsWithPermission(@Param("permission") SuperadminPermission permission);
}
