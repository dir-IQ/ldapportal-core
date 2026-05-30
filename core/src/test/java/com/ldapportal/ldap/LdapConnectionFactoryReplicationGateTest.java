// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.ldap.replication.ReplicatingLdapInterface;
import com.ldapportal.ldap.replication.ReplicationEnqueuer;
import com.ldapportal.service.EncryptionService;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R2 per-directory replication gate. Verifies that the capture wrapper
 * ({@link ReplicatingLdapInterface}) is applied to the interface handed
 * to the operation lambda only when the source directory's
 * {@code replicationEnabled} master switch is on.
 */
@ExtendWith(MockitoExtension.class)
class LdapConnectionFactoryReplicationGateTest {

    @Mock private EncryptionService encryptionService;

    private LdapConnectionFactory factory;
    private InMemoryDirectoryServer ldapServer;

    @BeforeEach
    void setUp() throws Exception {
        // A wired (non-null) enqueuer means replication is active; the
        // per-directory flag is then the deciding gate.
        factory = new LdapConnectionFactory(encryptionService, mock(ReplicationEnqueuer.class));

        InMemoryDirectoryServerConfig config =
                new InMemoryDirectoryServerConfig("dc=example,dc=com");
        config.addAdditionalBindCredentials("cn=admin,dc=example,dc=com", "adminpass");
        ldapServer = new InMemoryDirectoryServer(config);
        ldapServer.startListening();
    }

    @AfterEach
    void tearDown() {
        factory.closeAll();
        if (ldapServer != null) ldapServer.shutDown(true);
    }

    private DirectoryConnection directory(boolean replicationEnabled) {
        DirectoryConnection dc = new DirectoryConnection();
        dc.setId(UUID.randomUUID());
        dc.setDisplayName("Test Directory");
        dc.setHost("localhost");
        dc.setPort(ldapServer.getListenPort());
        dc.setSslMode(SslMode.NONE);
        dc.setTrustAllCerts(false);
        dc.setBindDn("cn=admin,dc=example,dc=com");
        dc.setBindPasswordEncrypted("encrypted-placeholder");
        dc.setBaseDn("dc=example,dc=com");
        dc.setPoolMinSize(1);
        dc.setPoolMaxSize(2);
        dc.setPoolConnectTimeoutSeconds(5);
        dc.setPoolResponseTimeoutSeconds(10);
        dc.setPagingSize(100);
        dc.setReplicationEnabled(replicationEnabled);
        return dc;
    }

    @Test
    void withConnection_wrapsForCapture_whenReplicationEnabled() {
        when(encryptionService.decrypt(anyString())).thenReturn("adminpass");

        boolean wrapped = factory.withConnection(directory(true),
                iface -> iface instanceof ReplicatingLdapInterface);

        assertThat(wrapped).isTrue();
    }

    @Test
    void withConnection_skipsWrapper_whenReplicationDisabled() {
        when(encryptionService.decrypt(anyString())).thenReturn("adminpass");

        boolean wrapped = factory.withConnection(directory(false),
                iface -> iface instanceof ReplicatingLdapInterface);

        assertThat(wrapped).isFalse();
    }

    @Test
    void withConnectionUnreplicated_neverWraps_evenWhenEnabled() {
        when(encryptionService.decrypt(anyString())).thenReturn("adminpass");

        boolean wrapped = factory.withConnectionUnreplicated(directory(true),
                iface -> iface instanceof ReplicatingLdapInterface);

        assertThat(wrapped).isFalse();
    }
}
