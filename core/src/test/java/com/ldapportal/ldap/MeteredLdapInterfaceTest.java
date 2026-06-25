// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.observability.LdapOperationMetrics;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.unboundid.ldap.sdk.SearchScope;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the operation timing decorator against a real UnboundID in-memory
 * server, so the operation labels, result classes, and elapsed-time capture
 * ride on actual SDK calls rather than mocks.
 */
class MeteredLdapInterfaceTest {

    private static final String OP_TIMER = LdapOperationMetrics.OPERATION_TIMER;
    private static final String BASE = "dc=example,dc=com";

    private InMemoryDirectoryServer ds;
    private LDAPConnection conn;
    private SimpleMeterRegistry registry;
    private MeteredLdapInterface iface;
    private DirectoryConnection dc;

    @BeforeEach
    void setUp() throws Exception {
        InMemoryDirectoryServerConfig cfg = new InMemoryDirectoryServerConfig(BASE);
        cfg.setSchema(null); // lenient fixture — no schema enforcement
        ds = new InMemoryDirectoryServer(cfg);
        ds.startListening();
        // Seed the base entry directly on the server so it doesn't count as a metered op.
        ds.add(new Entry(BASE, new Attribute("objectClass", "top", "domain"), new Attribute("dc", "example")));
        conn = ds.getConnection();

        registry = new SimpleMeterRegistry();
        LdapOperationMetrics metrics = new LdapOperationMetrics(registry);

        dc = new DirectoryConnection();
        dc.setId(UUID.randomUUID());
        dc.setDisplayName("Test OUD");
        dc.setDirectoryType(DirectoryType.ORACLE_UNIFIED_DIRECTORY);

        iface = new MeteredLdapInterface(conn, metrics, metrics.directoryTags(dc));
    }

    @AfterEach
    void tearDown() {
        if (conn != null) conn.close();
        if (ds != null) ds.shutDown(true);
    }

    private Timer timer(String operation, String result) {
        return registry.find(OP_TIMER).tag("operation", operation).tag("result", result).timer();
    }

    @Test
    void search_records_a_success_timer_tagged_by_directory_and_operation() throws Exception {
        iface.search(BASE, SearchScope.SUB, "(objectClass=*)");

        Timer t = registry.get(OP_TIMER)
                .tag("directory_id", dc.getId().toString())
                .tag("type", "ORACLE_UNIFIED_DIRECTORY")
                .tag("operation", "search")
                .tag("result", "success")
                .timer();
        assertThat(t.count()).isEqualTo(1);
        assertThat(t.totalTime(TimeUnit.NANOSECONDS)).isGreaterThan(0.0);
    }

    @Test
    void add_modify_delete_each_record_their_own_operation() throws Exception {
        String dn = "uid=alice," + BASE;
        iface.add(new Entry(dn, new Attribute("objectClass", "top", "person"),
                new Attribute("uid", "alice"), new Attribute("cn", "Alice"), new Attribute("sn", "A")));
        iface.modify(dn, new Modification(ModificationType.REPLACE, "description", "hello"));
        iface.delete(dn);

        assertThat(timer("add", "success").count()).isEqualTo(1);
        assertThat(timer("modify", "success").count()).isEqualTo(1);
        assertThat(timer("delete", "success").count()).isEqualTo(1);
    }

    @Test
    void failed_delete_records_the_result_class_and_propagates() {
        assertThatThrownBy(() -> iface.delete("uid=ghost," + BASE))
                .isInstanceOf(LDAPException.class);

        assertThat(timer("delete", "not_found").count()).isEqualTo(1);
        // The failure must not be miscounted as a success.
        assertThat(timer("delete", "success")).isNull();
    }

    @Test
    void duplicate_add_records_an_invalid_result_class() throws Exception {
        String dn = "uid=bob," + BASE;
        Entry entry = new Entry(dn, new Attribute("objectClass", "top", "person"),
                new Attribute("uid", "bob"), new Attribute("cn", "Bob"), new Attribute("sn", "B"));
        iface.add(entry);

        assertThatThrownBy(() -> iface.add(entry)).isInstanceOf(LDAPException.class);

        assertThat(timer("add", "success").count()).isEqualTo(1);
        assertThat(timer("add", "invalid").count()).isEqualTo(1);
    }
}
