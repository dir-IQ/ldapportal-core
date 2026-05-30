// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.ReplicationEvent;
import com.ldapportal.entity.ReplicationLink;
import com.ldapportal.entity.enums.ReplicationOperationType;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.ldapportal.repository.ReplicationLinkRepository;
import com.ldapportal.service.EncryptionService;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.SearchResultEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReplicationDeliveryTest {

    @Mock private EncryptionService encryptionService;
    @Mock private ReplicationLinkRepository linkRepo;

    private InMemoryDirectoryServer targetServer;
    private InMemoryDirectoryServer sourceServer;
    private LdapConnectionFactory   factory;
    private ReplicationDelivery     delivery;
    private DirectoryConnection     targetDc;
    private DirectoryConnection     sourceDc;

    private static final String BIND_DN   = "cn=admin,dc=test,dc=com";
    private static final String BIND_PASS = "adminpass";

    @BeforeEach
    void setUp() throws Exception {
        targetServer = startServer();
        sourceServer = startServer();

        when(encryptionService.decrypt(anyString())).thenReturn(BIND_PASS);
        // null replicationEnqueuer — delivery uses withConnectionUnreplicated,
        // so the wrapper never activates anyway.
        factory = new LdapConnectionFactory(encryptionService, null);
        delivery = new ReplicationDelivery(factory, linkRepo);

        targetDc = buildDc(targetServer.getListenPort());
        sourceDc = buildDc(sourceServer.getListenPort());
    }

    @AfterEach
    void tearDown() {
        factory.closeAll();
        if (targetServer != null) targetServer.shutDown(true);
        if (sourceServer != null) sourceServer.shutDown(true);
    }

    @Test
    void addEvent_appliesToTarget() throws Exception {
        ReplicationEvent event = event(ReplicationOperationType.ADD,
                "uid=alice,dc=test,dc=com",
                Map.of("attributes", Map.of(
                        "objectClass", List.of("top", "person"),
                        "cn",          List.of("Alice"),
                        "sn",          List.of("Smith"))));

        ReplicationDelivery.DeliveryResult r = delivery.deliver(event);

        assertThat(r.success()).isTrue();
        // Read the entry back from the target to confirm structure.
        SearchResultEntry e = targetServer.getEntry("uid=alice,dc=test,dc=com");
        assertThat(e).isNotNull();
        assertThat(e.getAttributeValue("cn")).isEqualTo("Alice");
    }

    @Test
    void modifyEvent_appliesToTarget() throws Exception {
        targetServer.add(new Entry("uid=bob,dc=test,dc=com",
                new Attribute("objectClass", "top", "person"),
                new Attribute("cn", "Bob"),
                new Attribute("sn", "Jones")));

        ReplicationEvent event = event(ReplicationOperationType.MODIFY,
                "uid=bob,dc=test,dc=com",
                Map.of("modifications", List.of(
                        Map.of("type", "REPLACE", "name", "cn", "values", List.of("Robert")))));

        ReplicationDelivery.DeliveryResult r = delivery.deliver(event);

        assertThat(r.success()).isTrue();
        SearchResultEntry e = targetServer.getEntry("uid=bob,dc=test,dc=com");
        assertThat(e.getAttributeValue("cn")).isEqualTo("Robert");
    }

    @Test
    void deleteEvent_appliesToTarget() throws Exception {
        targetServer.add(new Entry("uid=carol,dc=test,dc=com",
                new Attribute("objectClass", "top", "person"),
                new Attribute("cn", "Carol"),
                new Attribute("sn", "Davis")));

        ReplicationEvent event = event(ReplicationOperationType.DELETE,
                "uid=carol,dc=test,dc=com", Map.of());

        ReplicationDelivery.DeliveryResult r = delivery.deliver(event);

        assertThat(r.success()).isTrue();
        assertThat(targetServer.getEntry("uid=carol,dc=test,dc=com")).isNull();
    }

    @Test
    void deleteEvent_missingOnTarget_treatedAsConvergentSuccess() {
        // Target already has no such entry — the desired final state is
        // already achieved. Worker should mark as DELIVERED and stop
        // retrying instead of indefinitely hammering a NO_SUCH_OBJECT
        // error. This is the contract baked into deliverDelete().
        ReplicationEvent event = event(ReplicationOperationType.DELETE,
                "uid=never-existed,dc=test,dc=com", Map.of());

        ReplicationDelivery.DeliveryResult r = delivery.deliver(event);

        assertThat(r.success()).isTrue();
    }

    @Test
    void modifyAgainstMissingTarget_withoutAutoCreate_fails() throws Exception {
        ReplicationEvent event = event(ReplicationOperationType.MODIFY,
                "uid=missing,dc=test,dc=com",
                Map.of("modifications", List.of(
                        Map.of("type", "REPLACE", "name", "cn", "values", List.of("X")))));
        event.getLink().setAutoCreateOnMissing(false);

        ReplicationDelivery.DeliveryResult r = delivery.deliver(event);

        assertThat(r.success()).isFalse();
    }

    @Test
    void modifyAgainstMissingTarget_withAutoCreate_addsThenModifies() throws Exception {
        // Auto-create path: source has the entry, target doesn't, link
        // allows auto-create. Delivery reads the source entry, ADDs to
        // the target, then re-attempts the MODIFY.
        sourceServer.add(new Entry("uid=dave,dc=test,dc=com",
                new Attribute("objectClass", "top", "person"),
                new Attribute("cn", "Dave"),
                new Attribute("sn", "Edwards")));

        ReplicationEvent event = event(ReplicationOperationType.MODIFY,
                "uid=dave,dc=test,dc=com",
                Map.of("modifications", List.of(
                        Map.of("type", "REPLACE", "name", "cn", "values", List.of("David")))));
        // Same DN on both sides (identity DN mapping).
        event.setSourceDn("uid=dave,dc=test,dc=com");
        event.getLink().setAutoCreateOnMissing(true);
        event.getLink().setSourceDirectory(sourceDc);

        ReplicationDelivery.DeliveryResult r = delivery.deliver(event);

        assertThat(r.success()).isTrue();
        SearchResultEntry e = targetServer.getEntry("uid=dave,dc=test,dc=com");
        assertThat(e).isNotNull();
        // ADD reconstructed Dave; the subsequent MODIFY renamed cn to David.
        assertThat(e.getAttributeValue("cn")).isEqualTo("David");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static InMemoryDirectoryServer startServer() throws Exception {
        InMemoryDirectoryServerConfig config =
                new InMemoryDirectoryServerConfig("dc=test,dc=com");
        config.addAdditionalBindCredentials(BIND_DN, BIND_PASS);
        config.setSchema(null);
        InMemoryDirectoryServer s = new InMemoryDirectoryServer(config);
        s.add(new Entry("dc=test,dc=com",
                new Attribute("objectClass", "top", "domain"),
                new Attribute("dc", "test")));
        s.startListening();
        return s;
    }

    private DirectoryConnection buildDc(int port) {
        DirectoryConnection dc = new DirectoryConnection();
        dc.setId(UUID.randomUUID());
        dc.setHost("localhost");
        dc.setPort(port);
        dc.setSslMode(SslMode.NONE);
        dc.setBindDn(BIND_DN);
        dc.setBindPasswordEncrypted("enc-placeholder");
        dc.setBaseDn("dc=test,dc=com");
        dc.setPagingSize(100);
        dc.setPoolMinSize(1);
        dc.setPoolMaxSize(3);
        dc.setPoolConnectTimeoutSeconds(5);
        dc.setPoolResponseTimeoutSeconds(10);
        return dc;
    }

    private ReplicationEvent event(ReplicationOperationType op, String targetDn, Map<String, Object> payload) {
        ReplicationEvent e = new ReplicationEvent();
        e.setId(UUID.randomUUID());
        e.setOperation(op);
        e.setTargetDn(targetDn);
        e.setPayload(new LinkedHashMap<>(payload));
        ReplicationLink link = new ReplicationLink();
        link.setTargetDirectory(targetDc);
        e.setLink(link);
        return e;
    }
}
