// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.AuditEvent;
import com.ldapportal.entity.enums.AuditAction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    /**
     * Paginated, multi-filter query. All filter params are optional (null = no filter).
     */
    @Query(value = """
            SELECT * FROM audit_events e
            WHERE (:directoryId IS NULL OR e.directory_id = CAST(:directoryId AS UUID))
              AND (:actorId     IS NULL OR e.actor_id     = CAST(:actorId AS UUID))
              AND (CAST(:action AS VARCHAR) IS NULL OR e.action = CAST(:action AS VARCHAR))
              AND (CAST(:targetDn AS VARCHAR) IS NULL OR e.target_dn = CAST(:targetDn AS VARCHAR))
              AND (CAST(:source AS VARCHAR) IS NULL
                   OR e.detail->>'source' = CAST(:source AS VARCHAR))
              AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR e.occurred_at >= CAST(:from AS TIMESTAMPTZ))
              AND (CAST(:to AS TIMESTAMPTZ)   IS NULL OR e.occurred_at <= CAST(:to AS TIMESTAMPTZ))
            ORDER BY e.occurred_at DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM audit_events e
            WHERE (:directoryId IS NULL OR e.directory_id = CAST(:directoryId AS UUID))
              AND (:actorId     IS NULL OR e.actor_id     = CAST(:actorId AS UUID))
              AND (CAST(:action AS VARCHAR) IS NULL OR e.action = CAST(:action AS VARCHAR))
              AND (CAST(:targetDn AS VARCHAR) IS NULL OR e.target_dn = CAST(:targetDn AS VARCHAR))
              AND (CAST(:source AS VARCHAR) IS NULL
                   OR e.detail->>'source' = CAST(:source AS VARCHAR))
              AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR e.occurred_at >= CAST(:from AS TIMESTAMPTZ))
              AND (CAST(:to AS TIMESTAMPTZ)   IS NULL OR e.occurred_at <= CAST(:to AS TIMESTAMPTZ))
            """,
            nativeQuery = true)
    Page<AuditEvent> findAll(
            @Param("directoryId") UUID directoryId,
            @Param("actorId")     UUID actorId,
            @Param("action")      String action,
            @Param("targetDn")    String targetDn,
            @Param("source")      String source,
            @Param("from")        OffsetDateTime from,
            @Param("to")          OffsetDateTime to,
            Pageable pageable);

    /**
     * Directory-scoped query for non-superadmins. Restricts results to the
     * given set of directory IDs.
     */
    @Query(value = """
            SELECT * FROM audit_events e
            WHERE e.directory_id IN (:directoryIds)
              AND (:actorId     IS NULL OR e.actor_id     = CAST(:actorId AS UUID))
              AND (CAST(:action AS VARCHAR) IS NULL OR e.action = CAST(:action AS VARCHAR))
              AND (CAST(:targetDn AS VARCHAR) IS NULL OR e.target_dn = CAST(:targetDn AS VARCHAR))
              AND (CAST(:source AS VARCHAR) IS NULL
                   OR e.detail->>'source' = CAST(:source AS VARCHAR))
              AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR e.occurred_at >= CAST(:from AS TIMESTAMPTZ))
              AND (CAST(:to AS TIMESTAMPTZ)   IS NULL OR e.occurred_at <= CAST(:to AS TIMESTAMPTZ))
            ORDER BY e.occurred_at DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM audit_events e
            WHERE e.directory_id IN (:directoryIds)
              AND (:actorId     IS NULL OR e.actor_id     = CAST(:actorId AS UUID))
              AND (CAST(:action AS VARCHAR) IS NULL OR e.action = CAST(:action AS VARCHAR))
              AND (CAST(:targetDn AS VARCHAR) IS NULL OR e.target_dn = CAST(:targetDn AS VARCHAR))
              AND (CAST(:source AS VARCHAR) IS NULL
                   OR e.detail->>'source' = CAST(:source AS VARCHAR))
              AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR e.occurred_at >= CAST(:from AS TIMESTAMPTZ))
              AND (CAST(:to AS TIMESTAMPTZ)   IS NULL OR e.occurred_at <= CAST(:to AS TIMESTAMPTZ))
            """,
            nativeQuery = true)
    Page<AuditEvent> findAllByDirectoryIds(
            @Param("directoryIds") Set<UUID> directoryIds,
            @Param("actorId")      UUID actorId,
            @Param("action")       String action,
            @Param("targetDn")     String targetDn,
            @Param("source")       String source,
            @Param("from")         OffsetDateTime from,
            @Param("to")           OffsetDateTime to,
            Pageable pageable);

    /** Used by the changelog reader to skip already-processed records. */
    boolean existsByDirectoryIdAndChangelogChangeNumber(UUID directoryId, String changeNumber);
}
