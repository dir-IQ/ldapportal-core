// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.ldap.model.DirectoryCapabilities;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LdapCapabilityProbeServiceTest {

    @Mock
    private EncryptionService encryptionService;

    private InMemoryDirectoryServer inMemoryServer;
    private LdapConnectionFactory factory;
    private LdapCapabilityProbeService probe;

    @BeforeEach
    void setUp() throws Exception {
        InMemoryDirectoryServerConfig config =
                new InMemoryDirectoryServerConfig("dc=example,dc=com");
        config.addAdditionalBindCredentials("cn=admin,dc=example,dc=com", "adminpass");
        inMemoryServer = new InMemoryDirectoryServer(config);
        inMemoryServer.startListening();

        factory = new LdapConnectionFactory(encryptionService);
        probe = new LdapCapabilityProbeService(factory);
    }

    @AfterEach
    void tearDown() {
        factory.closeAll();
        if (inMemoryServer != null) {
            inMemoryServer.shutDown(true);
        }
    }

    @Test
    void probe_returnsSnapshotWithOidsAndProbedAt() {
        // The UnboundID in-memory server publishes a root DSE with
        // supportedControl OIDs and standard SASL mechanisms. It
        // doesn't advertise a vendorName (the implementation is
        // anonymous); the snapshot must therefore tolerate a null
        // vendor without throwing.
        when(encryptionService.decrypt(anyString())).thenReturn("adminpass");
        DirectoryConnection dc = buildDirectoryConnection();

        DirectoryCapabilities caps = probe.probe(dc);

        assertThat(caps).isNotNull();
        assertThat(caps.probedAt()).isNotNull();
        assertThat(caps.supportedControls()).isNotEmpty();
        // namingContexts MUST include the base DN we configured —
        // that's the contract the cross-check on save will eventually
        // depend on.
        assertThat(caps.namingContexts()).contains("dc=example,dc=com");
    }

    @Test
    void probe_returnsNull_forEntraId() {
        // Entra ID is not an LDAP server, so the probe short-circuits
        // without ever opening a connection. Verifying behaviour, not
        // the network path: build a connection of type ENTRA_ID and
        // expect a null snapshot back regardless of other state.
        DirectoryConnection dc = buildDirectoryConnection();
        dc.setDirectoryType(DirectoryType.ENTRA_ID);

        DirectoryCapabilities caps = probe.probe(dc);

        assertThat(caps).isNull();
    }

    @Test
    void probe_returnsNull_onConnectionFailure() {
        // Point at a port nothing is listening on — the connection
        // attempt fails inside withConnection, and the probe should
        // swallow the exception and return null so a partial-config
        // directory still saves. Confirms the "best-effort" contract.
        when(encryptionService.decrypt(anyString())).thenReturn("adminpass");
        DirectoryConnection dc = buildDirectoryConnection();
        dc.setPort(1);  // reserved, nothing listens

        DirectoryCapabilities caps = probe.probe(dc);

        assertThat(caps).isNull();
    }

    private DirectoryConnection buildDirectoryConnection() {
        DirectoryConnection dc = new DirectoryConnection();
        dc.setId(UUID.randomUUID());
        dc.setDisplayName("Test Directory");
        dc.setDirectoryType(DirectoryType.GENERIC);
        dc.setHost("localhost");
        dc.setPort(inMemoryServer.getListenPort());
        dc.setSslMode(SslMode.NONE);
        dc.setTrustAllCerts(false);
        dc.setBindDn("cn=admin,dc=example,dc=com");
        dc.setBindPasswordEncrypted("encrypted-placeholder");
        dc.setBaseDn("dc=example,dc=com");
        dc.setPoolMinSize(1);
        dc.setPoolMaxSize(5);
        dc.setPoolConnectTimeoutSeconds(5);
        dc.setPoolResponseTimeoutSeconds(10);
        dc.setPagingSize(100);
        return dc;
    }
}
