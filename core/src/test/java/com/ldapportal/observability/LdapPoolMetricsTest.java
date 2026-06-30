// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.observability;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.DirectoryType;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPConnectionPool;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the per-directory pool meters against a real UnboundID
 * {@link LDAPConnectionPool} backed by an in-memory server, so the
 * registration, tags, live values, and cleanup ride on the actual SDK
 * statistics rather than mocks.
 */
class LdapPoolMetricsTest {

    private InMemoryDirectoryServer ds;
    private LDAPConnectionPool pool;
    private SimpleMeterRegistry registry;
    private LdapPoolMetrics metrics;
    private DirectoryConnection dc;

    @BeforeEach
    void setUp() throws Exception {
        InMemoryDirectoryServerConfig cfg = new InMemoryDirectoryServerConfig("dc=example,dc=com");
        cfg.setSchema(null); // skip schema validation for the fixture
        ds = new InMemoryDirectoryServer(cfg);
        ds.startListening();
        pool = new LDAPConnectionPool(ds.getConnection(), 1, 4);

        registry = new SimpleMeterRegistry();
        metrics = new LdapPoolMetrics(registry);

        dc = new DirectoryConnection();
        dc.setId(UUID.randomUUID());
        dc.setDisplayName("Test OUD");
        dc.setDirectoryType(DirectoryType.ORACLE_UNIFIED_DIRECTORY);
    }

    @AfterEach
    void tearDown() {
        if (pool != null) pool.close();
        if (ds != null) ds.shutDown(true);
    }

    @Test
    void registers_pool_gauges_and_counters_tagged_by_directory() {
        metrics.register(dc, pool);

        var available = registry.get("ldapportal.ldap.pool.connections.available")
                .tag("directory_id", dc.getId().toString())
                .tag("type", "ORACLE_UNIFIED_DIRECTORY")
                .gauge();
        assertThat(available.value()).isGreaterThanOrEqualTo(0.0);

        // connections.max is the configured capacity (the pool was built with max=4),
        // not a runtime high-water mark — so it's deterministic regardless of load.
        var max = registry.get("ldapportal.ldap.pool.connections.max")
                .tag("directory_id", dc.getId().toString())
                .gauge();
        assertThat(max.value()).isEqualTo(4.0);

        // The failed-checkouts counter (pool exhaustion signal) exists and starts at 0.
        var failed = registry.get("ldapportal.ldap.pool.checkouts")
                .tag("result", "failed").functionCounter();
        assertThat(failed.count()).isZero();
    }

    @Test
    void successful_checkout_increments_the_success_counter() throws Exception {
        metrics.register(dc, pool);

        LDAPConnection c = pool.getConnection();
        pool.releaseConnection(c);

        var success = registry.get("ldapportal.ldap.pool.checkouts")
                .tag("result", "success").functionCounter();
        assertThat(success.count()).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void register_is_idempotent_per_directory() {
        metrics.register(dc, pool);
        metrics.register(dc, pool); // second call must not double-register
        assertThat(registry.get("ldapportal.ldap.pool.connections.available")
                .tag("directory_id", dc.getId().toString()).gauges()).hasSize(1);
    }

    @Test
    void deregister_removes_the_directory_meters() {
        metrics.register(dc, pool);
        metrics.deregister(dc.getId());

        assertThat(registry.find("ldapportal.ldap.pool.connections.available").gauge()).isNull();
        assertThat(registry.find("ldapportal.ldap.pool.checkouts").functionCounter()).isNull();
    }
}
