// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.exception.LdapConnectionException;
import com.ldapportal.exception.LdapOperationException;
import com.ldapportal.service.EncryptionService;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.sdk.LDAPConnectionPool;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link LdapConnectionFactory}.
 *
 * Uses the UnboundID in-memory LDAP server for network-level tests
 * so no external LDAP server is required.
 */
@ExtendWith(MockitoExtension.class)
class LdapConnectionFactoryTest {

    @Mock
    private EncryptionService encryptionService;

    private LdapConnectionFactory factory;
    private InMemoryDirectoryServer inMemoryServer;

    @BeforeEach
    void setUp() throws Exception {
        factory = new LdapConnectionFactory(encryptionService);

        // Start an in-memory LDAP server with a simple base DN
        InMemoryDirectoryServerConfig config =
            new InMemoryDirectoryServerConfig("dc=example,dc=com");
        config.addAdditionalBindCredentials("cn=admin,dc=example,dc=com", "adminpass");
        inMemoryServer = new InMemoryDirectoryServer(config);
        inMemoryServer.startListening();
    }

    @AfterEach
    void tearDown() {
        factory.closeAll();
        if (inMemoryServer != null) {
            inMemoryServer.shutDown(true);
        }
    }

    @Test
    void getPool_returnsConnectionPool_forPlainLdap() {
        DirectoryConnection dc = buildDirectoryConnection(SslMode.NONE);
        when(encryptionService.decrypt(anyString())).thenReturn("adminpass");

        LDAPConnectionPool pool = factory.getPool(dc);
        assertThat(pool).isNotNull();
        assertThat(pool.getCurrentAvailableConnections()).isPositive();
    }

    @Test
    void getPool_returnsSamePool_onSecondCall() {
        DirectoryConnection dc = buildDirectoryConnection(SslMode.NONE);
        when(encryptionService.decrypt(anyString())).thenReturn("adminpass");

        LDAPConnectionPool pool1 = factory.getPool(dc);
        LDAPConnectionPool pool2 = factory.getPool(dc);
        assertThat(pool1).isSameAs(pool2);
        verify(encryptionService, times(1)).decrypt(anyString());
    }

    @Test
    void evict_removesAndClosesPool() {
        DirectoryConnection dc = buildDirectoryConnection(SslMode.NONE);
        when(encryptionService.decrypt(anyString())).thenReturn("adminpass");

        factory.getPool(dc);
        factory.evict(dc.getId());

        // After eviction the pool is recreated on next getPool call
        when(encryptionService.decrypt(anyString())).thenReturn("adminpass");
        LDAPConnectionPool newPool = factory.getPool(dc);
        assertThat(newPool).isNotNull();
        verify(encryptionService, times(2)).decrypt(anyString());
    }

    @Test
    void withConnection_executesOperation_andReturnsResult() {
        DirectoryConnection dc = buildDirectoryConnection(SslMode.NONE);
        when(encryptionService.decrypt(anyString())).thenReturn("adminpass");

        String dn = factory.withConnection(dc, conn -> conn.getRootDSE().getDN());
        assertThat(dn).isNotNull();
    }

    @Test
    void getPool_withBadPassword_throwsLdapConnectionException() {
        DirectoryConnection dc = buildDirectoryConnection(SslMode.NONE);
        when(encryptionService.decrypt(anyString())).thenReturn("wrong-password");

        assertThatThrownBy(() -> factory.getPool(dc))
            .isInstanceOf(LdapConnectionException.class);
    }

    // ── Operation-vs-connection error discrimination ────────────────
    //
    // Operation-level LDAP errors (NO_SUCH_OBJECT, INVALID_DN_SYNTAX,
    // INSUFFICIENT_ACCESS_RIGHTS, etc) used to wrap as
    // LdapConnectionException → 502 "LDAP server unreachable", which
    // misled operators about the root cause. They now wrap as
    // LdapOperationException → 422 with the actual LDAP message.

    @Test
    void withConnection_operationLevelError_throwsLdapOperationException_notConnection() {
        DirectoryConnection dc = buildDirectoryConnection(SslMode.NONE);
        when(encryptionService.decrypt(anyString())).thenReturn("adminpass");

        // Synthesize an operation-level error: NO_SUCH_OBJECT is the
        // canonical "operation reached the server fine but the entry
        // isn't there" code. Throwing it from inside the lambda
        // exercises the same path that `conn.getEntry(missing-dn)`
        // would; using a thrown LDAPException keeps the test
        // server-state-free.
        assertThatThrownBy(() -> factory.withConnection(dc, conn -> {
            throw new LDAPException(ResultCode.NO_SUCH_OBJECT, "entry not found");
        }))
            .isInstanceOf(LdapOperationException.class)
            .hasMessageContaining("entry not found");
    }

    @Test
    void withConnection_invalidDnSyntax_throwsLdapOperationException() {
        // INVALID_DN_SYNTAX is the case that surfaced this bug — a
        // syntactically-bad DN passed into a base-fetch.
        DirectoryConnection dc = buildDirectoryConnection(SslMode.NONE);
        when(encryptionService.decrypt(anyString())).thenReturn("adminpass");

        assertThatThrownBy(() -> factory.withConnection(dc, conn -> {
            throw new LDAPException(ResultCode.INVALID_DN_SYNTAX, "not a valid dn");
        }))
            .isInstanceOf(LdapOperationException.class)
            .hasMessageContaining("not a valid dn");
    }

    @Test
    void withConnection_connectionBrokenCode_throwsLdapConnectionException() {
        // CONNECT_ERROR is one of the result codes that
        // ResultCode.isConnectionUsable() returns false for — i.e. a
        // genuine connectivity failure where the socket should be
        // marked defunct.
        DirectoryConnection dc = buildDirectoryConnection(SslMode.NONE);
        when(encryptionService.decrypt(anyString())).thenReturn("adminpass");

        assertThatThrownBy(() -> factory.withConnection(dc, conn -> {
            throw new LDAPException(ResultCode.CONNECT_ERROR, "socket closed");
        }))
            .isInstanceOf(LdapConnectionException.class)
            .hasMessageContaining("socket closed");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private DirectoryConnection buildDirectoryConnection(SslMode sslMode) {
        DirectoryConnection dc = new DirectoryConnection();
        dc.setId(UUID.randomUUID());
        dc.setDisplayName("Test Directory");
        dc.setHost("localhost");
        dc.setPort(inMemoryServer.getListenPort());
        dc.setSslMode(sslMode);
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
