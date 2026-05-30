// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.ldap.model.DirectoryCapabilities;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DirectoryConnectionRepository extends JpaRepository<DirectoryConnection, UUID> {

    List<DirectoryConnection> findAllByEnabledTrue();

    Optional<DirectoryConnection> findByUserRepositoryTrue();

    List<DirectoryConnection> findAllByAuditDataSourceId(UUID auditDataSourceId);

    /**
     * Targeted update of just the {@code capabilities} JSONB column.
     * Used by {@link com.ldapportal.ldap.DirectoryCapabilityRefresher} so a
     * post-commit refresh doesn't have to emit a full-row UPDATE on every
     * probe success — that would (a) waste WAL bandwidth re-writing
     * unchanged columns (TEXT-typed bind_password_encrypted +
     * trusted_certificate_pem are the largest), (b) lie via
     * {@code @UpdateTimestamp} on {@code updated_at}, and (c) silently
     * clobber any concurrent operator edit committed between the
     * listener's {@code findById} and its {@code save}. The targeted
     * UPDATE touches only {@code capabilities}, so a parallel rename or
     * type change is preserved.
     *
     * @return rows affected — caller can detect a deleted-while-probing
     *         race by checking for 0.
     */
    @Modifying
    @Query("UPDATE DirectoryConnection dc SET dc.capabilities = :capabilities WHERE dc.id = :id")
    int updateCapabilities(@Param("id") UUID id,
                           @Param("capabilities") DirectoryCapabilities capabilities);
}
