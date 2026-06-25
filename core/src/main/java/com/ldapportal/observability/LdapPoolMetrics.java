// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.observability;

import com.ldapportal.entity.DirectoryConnection;
import com.unboundid.ldap.sdk.LDAPConnectionPool;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.ToDoubleFunction;

/**
 * Per-directory LDAP connection-pool metrics, exported via Micrometer
 * (Prometheus). The directory layer is the portal's core dependency, and
 * pool exhaustion (failed checkouts) / available-connection depletion are
 * its primary operational-health signals.
 *
 * <p>{@link LdapConnectionFactory} registers a directory's meters when its
 * pool is first created and removes them when the pool is evicted. Each meter
 * reads the pool's live {@code LDAPConnectionPoolStatistics} at scrape time.</p>
 *
 * <p>Tagged by {@code directory_id} / {@code directory} / {@code type} — all
 * bounded, low-cardinality dimensions (one series set per configured
 * directory). No user, DN, or entry data ever becomes a tag.</p>
 */
@Component
@Slf4j
public class LdapPoolMetrics {

    private final MeterRegistry registry;
    private final ConcurrentMap<UUID, List<Meter>> byDirectory = new ConcurrentHashMap<>();

    public LdapPoolMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Bind pool metrics for a directory. Idempotent per directory id. */
    public void register(DirectoryConnection dc, LDAPConnectionPool pool) {
        try {
            byDirectory.computeIfAbsent(dc.getId(), id -> bind(dc, pool));
        } catch (RuntimeException e) {
            // Metrics must never break pool creation (the critical path).
            log.warn("Failed to register LDAP pool metrics for [{}]: {}", dc.getDisplayName(), e.getMessage());
        }
    }

    /** Remove a directory's pool metrics. Call when the pool is evicted. */
    public void deregister(UUID directoryId) {
        List<Meter> meters = byDirectory.remove(directoryId);
        if (meters != null) {
            meters.forEach(registry::remove);
        }
    }

    private List<Meter> bind(DirectoryConnection dc, LDAPConnectionPool pool) {
        Tags tags = Tags.of(
                "directory_id", String.valueOf(dc.getId()),
                "directory", dc.getDisplayName() == null ? "" : dc.getDisplayName(),
                "type", dc.getDirectoryType() == null ? "UNKNOWN" : dc.getDirectoryType().name());

        List<Meter> meters = new ArrayList<>();
        // Instantaneous pool depth.
        meters.add(gauge(pool, tags, "ldapportal.ldap.pool.connections.available",
                "Connections currently available (idle) in the pool",
                p -> p.getConnectionPoolStatistics().getNumAvailableConnections()));
        meters.add(gauge(pool, tags, "ldapportal.ldap.pool.connections.max",
                "High-water mark of available connections",
                p -> p.getConnectionPoolStatistics().getMaximumAvailableConnections()));

        // Cumulative counters (monotonic) — failed checkouts == pool exhaustion.
        meters.add(counter(pool, tags.and("result", "success"), "ldapportal.ldap.pool.checkouts",
                p -> p.getConnectionPoolStatistics().getNumSuccessfulCheckouts()));
        meters.add(counter(pool, tags.and("result", "failed"), "ldapportal.ldap.pool.checkouts",
                p -> p.getConnectionPoolStatistics().getNumFailedCheckouts()));
        meters.add(counter(pool, tags, "ldapportal.ldap.pool.connections.closed.defunct",
                p -> p.getConnectionPoolStatistics().getNumConnectionsClosedDefunct()));
        meters.add(counter(pool, tags.and("result", "success"), "ldapportal.ldap.pool.connection.attempts",
                p -> p.getConnectionPoolStatistics().getNumSuccessfulConnectionAttempts()));
        meters.add(counter(pool, tags.and("result", "failed"), "ldapportal.ldap.pool.connection.attempts",
                p -> p.getConnectionPoolStatistics().getNumFailedConnectionAttempts()));
        return meters;
    }

    private Gauge gauge(LDAPConnectionPool pool, Tags tags, String name, String description,
                        ToDoubleFunction<LDAPConnectionPool> value) {
        return Gauge.builder(name, pool, value).description(description).tags(tags).register(registry);
    }

    private FunctionCounter counter(LDAPConnectionPool pool, Tags tags, String name,
                                    ToDoubleFunction<LDAPConnectionPool> value) {
        return FunctionCounter.builder(name, pool, value).tags(tags).register(registry);
    }
}
