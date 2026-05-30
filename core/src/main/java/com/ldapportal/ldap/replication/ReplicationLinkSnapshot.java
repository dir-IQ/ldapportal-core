// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.ReplicationLink;
import com.ldapportal.entity.ReplicationLinkAttrMapping;

import java.util.List;
import java.util.UUID;

/**
 * Immutable projection of a {@link ReplicationLink} used by the
 * non-transactional consumers (enqueuer, worker, delivery, mappers).
 *
 * <p><b>Why this exists.</b> The previous shape passed the live
 * {@code ReplicationLink} entity across the JPA boundary. With
 * {@code spring.jpa.open-in-view=false} the Hibernate session closes
 * the moment the repository call returns, so any subsequent access to
 * a LAZY association on the entity ({@code attributeMappings},
 * {@code sourceDirectory}, {@code targetDirectory}) raised
 * {@code LazyInitializationException}. The enqueuer's outer catch
 * swallowed it; the worker's outer catch turned it into a permanent
 * delivery failure. Neither bug surfaced in the Mockito unit tests
 * (which hand the consumers POJO entities with in-memory ArrayList
 * collections — no proxy involvement).
 *
 * <p>The snapshot record makes the bug class impossible by
 * construction: the record carries no JPA proxies, no lazy collections,
 * no session dependency. Once a snapshot is built (inside the
 * repository's transactional method, via {@link #from(ReplicationLink)}),
 * every consumer can read every field outside any tx with no risk of
 * a session-related crash.
 *
 * <p>{@link DirectoryConnection} is retained as an entity reference
 * (not further projected) because the consumers only access its
 * column-typed scalar fields (host, port, bindDn, etc.) — never its
 * one lazy association ({@code auditDataSource}). Repository queries
 * that hydrate this snapshot must {@code JOIN FETCH} both directories
 * so the entities are fully populated by the time the session closes.
 */
public record ReplicationLinkSnapshot(
        UUID id,
        String displayName,
        DirectoryConnection sourceDirectory,
        DirectoryConnection targetDirectory,
        String sourceBaseDn,
        String targetBaseDn,
        boolean enabled,
        boolean autoCreateOnMissing,
        List<AttrMappingSnapshot> attributeMappings) {

    public record AttrMappingSnapshot(String sourceAttr,
                                       String targetAttr,
                                       String valueTemplate) {
        static AttrMappingSnapshot from(ReplicationLinkAttrMapping m) {
            return new AttrMappingSnapshot(
                    m.getSourceAttr(), m.getTargetAttr(), m.getValueTemplate());
        }
    }

    /**
     * Materialise a link entity into a snapshot. <b>MUST be called
     * while the entity's Hibernate session is still open</b> — typically
     * from inside the repository's {@code @Transactional} method that
     * returned it. The repository's {@code @Query} must
     * {@code LEFT JOIN FETCH} {@code attributeMappings} and
     * {@code JOIN FETCH} both {@code sourceDirectory} and
     * {@code targetDirectory} so this factory's field reads don't
     * trigger any lazy loading.
     */
    public static ReplicationLinkSnapshot from(ReplicationLink link) {
        return new ReplicationLinkSnapshot(
                link.getId(),
                link.getDisplayName(),
                link.getSourceDirectory(),
                link.getTargetDirectory(),
                link.getSourceBaseDn(),
                link.getTargetBaseDn(),
                link.isEnabled(),
                link.isAutoCreateOnMissing(),
                link.getAttributeMappings().stream()
                        .map(AttrMappingSnapshot::from)
                        .toList());
    }
}
