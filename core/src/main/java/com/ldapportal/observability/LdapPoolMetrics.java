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
 * <p>{@link com.ldapportal.ldap.LdapConnectionFactory} registers a directory's
 * meters when its pool is first created and removes them when the pool is
 * evicted. Gauges read the pool's instantaneous state, and counters read its
 * cumulative {@code LDAPConnectionPoolStatistics}, both evaluated at scrape
 * time.</p>
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
        Tags tags = DirectoryMeterTags.of(dc);

        // Register everything, but roll back on a mid-way failure so we never leave
        // half the meters in the registry untracked by byDirectory (which would make
        // them un-deregisterable and leave them scraping a closed pool).
        List<Meter> meters = new ArrayList<>();
        try {
            // ── Gauges: instantaneous pool state, read straight off the pool ──────────
            meters.add(gauge(pool, tags, "ldapportal.ldap.pool.connections.available",
                    "Connections currently idle and available for checkout",
                    LDAPConnectionPool::getCurrentAvailableConnections));
            // Configured capacity — pair with .available to derive utilisation. NB: this
            // is the pool's own getMaximumAvailableConnections() (the configured max),
            // NOT the identically-named statistic, which is a high-water mark of idle.
            meters.add(gauge(pool, tags, "ldapportal.ldap.pool.connections.max",
                    "Configured maximum pool size (capacity)",
                    LDAPConnectionPool::getMaximumAvailableConnections));

            // ── Counters: cumulative (monotonic), read from the pool statistics ───────
            // Failed checkouts == pool exhaustion, the primary alert signal.
            meters.add(counter(pool, tags.and("result", "success"), "ldapportal.ldap.pool.checkouts",
                    "Connection checkouts (borrows) from the pool",
                    p -> p.getConnectionPoolStatistics().getNumSuccessfulCheckouts()));
            meters.add(counter(pool, tags.and("result", "failed"), "ldapportal.ldap.pool.checkouts",
                    "Connection checkouts (borrows) from the pool",
                    p -> p.getConnectionPoolStatistics().getNumFailedCheckouts()));
            meters.add(counter(pool, tags, "ldapportal.ldap.pool.connections.closed.defunct",
                    "Connections discarded as defunct (broken or unusable)",
                    p -> p.getConnectionPoolStatistics().getNumConnectionsClosedDefunct()));
            meters.add(counter(pool, tags.and("result", "success"), "ldapportal.ldap.pool.connection.attempts",
                    "New connection establishment attempts",
                    p -> p.getConnectionPoolStatistics().getNumSuccessfulConnectionAttempts()));
            meters.add(counter(pool, tags.and("result", "failed"), "ldapportal.ldap.pool.connection.attempts",
                    "New connection establishment attempts",
                    p -> p.getConnectionPoolStatistics().getNumFailedConnectionAttempts()));
            return meters;
        } catch (RuntimeException e) {
            meters.forEach(registry::remove);
            throw e;
        }
    }

    private Gauge gauge(LDAPConnectionPool pool, Tags tags, String name, String description,
                        ToDoubleFunction<LDAPConnectionPool> value) {
        return Gauge.builder(name, pool, value)
                .description(description).baseUnit("connections").tags(tags).register(registry);
    }

    private FunctionCounter counter(LDAPConnectionPool pool, Tags tags, String name, String description,
                                    ToDoubleFunction<LDAPConnectionPool> value) {
        return FunctionCounter.builder(name, pool, value)
                .description(description).tags(tags).register(registry);
    }
}
