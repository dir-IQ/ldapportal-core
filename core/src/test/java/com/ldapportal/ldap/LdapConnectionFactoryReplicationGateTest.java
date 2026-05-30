// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.ldap.replication.ReplicatingLdapInterface;
import com.ldapportal.ldap.replication.ReplicationEnqueuer;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * R2 per-directory replication gate. Verifies that the capture wrapper
 * ({@link ReplicatingLdapInterface}) is applied only when the source
 * directory's {@code replicationEnabled} master switch is on — observed
 * via the concrete type of the interface handed to the operation lambda.
 */
class LdapConnectionFactoryReplicationGateTest {

    private InMemoryDirectoryServer ldapServer;
    private EncryptionService encryptionService;
    private ReplicationEnqueuer enqueuer;

    @BeforeEach
    void setUp() throws Exception {
        InMemoryDirectoryServerConfig config =
                new InMemoryDirectoryServerConfig("dc=example,dc=com");
        config.addAdditionalBindCredentials("cn=admin,dc=example,dc=com", "password");
        ldapServer = new InMemoryDirectoryServer(config);
        ldapServer.startListening();

        encryptionService = new EncryptionService("0123456789abcdef0123456789abcdef");
        enqueuer = mock(ReplicationEnqueuer.class);
    }

    @AfterEach
    void tearDown() {
        if (ldapServer != null) ldapServer.stopListening();
    }

    private DirectoryConnection directory(boolean replicationEnabled) {
        DirectoryConnection dc = new DirectoryConnection();
        dc.setId(UUID.randomUUID());
        dc.setHost("localhost");
        dc.setPort(ldapServer.getListenPort());
        dc.setBindDn("cn=admin,dc=example,dc=com");
        dc.setBindPassword(encryptionService.encrypt("password"));
        dc.setSslMode(SslMode.NONE);
        dc.setBaseDn("dc=example,dc=com");
        dc.setPoolMinSize(1);
        dc.setPoolMaxSize(2);
        dc.setPoolResponseTimeoutSeconds(5);
        dc.setPoolConnectTimeoutSeconds(5);
        dc.setReplicationEnabled(replicationEnabled);
        return dc;
    }

    @Test
    void withConnection_wrapsForCapture_whenReplicationEnabled() {
        LdapConnectionFactory factory = new LdapConnectionFactory(encryptionService, enqueuer);

        boolean wrapped = factory.withConnection(directory(true),
                iface -> iface instanceof ReplicatingLdapInterface);

        assertThat(wrapped).isTrue();
    }

    @Test
    void withConnection_skipsWrapper_whenReplicationDisabled() {
        LdapConnectionFactory factory = new LdapConnectionFactory(encryptionService, enqueuer);

        boolean wrapped = factory.withConnection(directory(false),
                iface -> iface instanceof ReplicatingLdapInterface);

        assertThat(wrapped).isFalse();
    }

    @Test
    void withConnectionUnreplicated_neverWraps_evenWhenEnabled() {
        LdapConnectionFactory factory = new LdapConnectionFactory(encryptionService, enqueuer);

        boolean wrapped = factory.withConnectionUnreplicated(directory(true),
                iface -> iface instanceof ReplicatingLdapInterface);

        assertThat(wrapped).isFalse();
    }
}
