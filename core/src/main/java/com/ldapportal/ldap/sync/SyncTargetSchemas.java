// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.unboundid.ldap.sdk.schema.Schema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The target directory's schema, for comparing a target entry with the
 * projected one ({@link TargetEntryDiffer}: which attributes hold DNs, and
 * attribute aliases). Read once
 * per directory and kept for {@link #TTL}, so a reconcile doesn't fetch it per
 * entry. A schema that can't be read is remembered as absent for a shorter
 * while (the differ then compares values exactly, as before).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SyncTargetSchemas {

    static final Duration TTL = Duration.ofMinutes(10);
    static final Duration FAILURE_TTL = Duration.ofMinutes(1);

    private final LdapConnectionFactory connectionFactory;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(Schema schema, Instant expires) {
    }

    /** The directory's schema, or {@code null} when it can't be read. */
    @Nullable
    public Schema forDirectory(DirectoryConnection dir) {
        Instant now = Instant.now();
        Cached c = cache.get(dir.getId());
        if (c != null && c.expires().isAfter(now)) {
            return c.schema();
        }
        Schema schema = null;
        try {
            schema = connectionFactory.withConnectionUnreplicated(dir, conn -> conn.getSchema());
        } catch (Exception ex) {
            log.debug("Could not read the schema of {}: {}", dir.getDisplayName(), ex.toString());
        }
        cache.put(dir.getId(), new Cached(schema, now.plus(schema != null ? TTL : FAILURE_TTL)));
        return schema;
    }
}
