// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.exception.LdapConnectionException;
import com.ldapportal.service.EncryptionService;
import com.unboundid.ldap.sdk.*;
import com.unboundid.ldap.sdk.extensions.StartTLSExtendedRequest;
import com.unboundid.util.ssl.SSLUtil;
import com.unboundid.util.ssl.TrustAllTrustManager;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Creates and caches {@link LDAPConnectionPool} instances keyed by
 * {@link DirectoryConnection} ID.
 *
 * <p>Pools are lazily created on first use and reused for subsequent
 * operations.  When a directory connection is updated (e.g. new password,
 * changed host), the caller must call {@link #evict(UUID)} to close the
 * stale pool before the next operation recreates it with the new settings.</p>
 *
 * <p>SSL/TLS is configured per the {@link SslMode} on the connection:
 * <ul>
 *   <li>{@code NONE} — plain TCP</li>
 *   <li>{@code LDAPS} — SSL/TLS on connect (typically port 636)</li>
 *   <li>{@code STARTTLS} — plain TCP upgraded with the STARTTLS extended op</li>
 * </ul>
 * If {@code trustedCertificatePem} is set it is used as the sole trust anchor;
 * if {@code trustAllCerts} is set all server certificates are accepted;
 * otherwise the JVM default trust store is used.
 * </p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class LdapConnectionFactory {

    private final EncryptionService encryptionService;

    private final ConcurrentMap<UUID, LDAPConnectionPool> pools = new ConcurrentHashMap<>();

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the connection pool for the given {@code directoryConnection},
     * creating it if it does not already exist.
     */
    public LDAPConnectionPool getPool(DirectoryConnection directoryConnection) {
        return pools.computeIfAbsent(directoryConnection.getId(),
            id -> createPool(directoryConnection));
    }

    /**
     * Borrows a connection from the pool, executes the operation, then
     * returns the connection.
     *
     * <p>On {@link LDAPException}, only releases the connection as defunct when
     * the result code indicates a broken connection (network failure, server
     * closed the socket, etc.).  Operation-level errors (e.g.
     * {@code NO_SUCH_OBJECT}, {@code INSUFFICIENT_ACCESS_RIGHTS}) return the
     * connection to the pool normally so healthy connections aren't discarded.</p>
     */
    public <T> T withConnection(DirectoryConnection dc,
                                LdapOperation<T> operation) {
        LDAPConnectionPool pool = getPool(dc);
        LDAPConnection conn = null;
        try {
            conn = pool.getConnection();
            return operation.execute(conn);
        } catch (LDAPException e) {
            if (conn != null) {
                // Only mark connection defunct for genuine connectivity failures;
                // operation-level errors (result codes ≥ 1 that don't indicate
                // a broken socket) should not shrink the pool.
                if (!e.getResultCode().isConnectionUsable()) {
                    pool.releaseDefunctConnection(conn);
                } else {
                    pool.releaseConnection(conn);
                }
                conn = null;
            }
            throw new LdapConnectionException(
                "LDAP operation failed on [" + dc.getDisplayName() + "]: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                pool.releaseConnection(conn);
            }
        }
    }

    /**
     * Closes and removes the cached pool for the given connection ID.
     * Should be called whenever a {@link DirectoryConnection} is updated.
     */
    public void evict(UUID connectionId) {
        LDAPConnectionPool pool = pools.remove(connectionId);
        if (pool != null) {
            pool.close();
            log.info("Evicted LDAP pool for connection {}", connectionId);
        }
    }

    /**
     * Opens a single, unbound LDAP connection to the given directory server.
     *
     * <p>Unlike {@link #getPool}, this method creates a fresh connection every
     * time and does <em>not</em> cache it.  The intended use-case is credential
     * verification (e.g. admin login via LDAP bind) where the caller must bind
     * with user-supplied credentials and then immediately close the connection.</p>
     *
     * <p>The caller is responsible for closing the connection (try-with-resources
     * is recommended).</p>
     *
     * @throws LdapConnectionException if the connection cannot be established
     */
    public LDAPConnection openUnboundConnection(DirectoryConnection dc) {
        try {
            LDAPConnectionOptions options = buildOptions(dc);

            if (dc.getSslMode() == SslMode.LDAPS) {
                SSLUtil sslUtil = buildSslUtil(dc);
                return new LDAPConnection(sslUtil.createSSLSocketFactory(),
                        options, dc.getHost(), dc.getPort());
            }

            LDAPConnection conn = new LDAPConnection(options, dc.getHost(), dc.getPort());

            if (dc.getSslMode() == SslMode.STARTTLS) {
                SSLUtil sslUtil = buildSslUtil(dc);
                ExtendedResult startTlsResult = conn.processExtendedOperation(
                        new StartTLSExtendedRequest(sslUtil.createSSLContext()));
                if (!startTlsResult.getResultCode().equals(ResultCode.SUCCESS)) {
                    conn.close();
                    throw new LdapConnectionException(
                            "STARTTLS negotiation failed for [" + dc.getDisplayName() + "]: "
                            + startTlsResult.getResultCode());
                }
            }

            return conn;

        } catch (LdapConnectionException e) {
            throw e;
        } catch (Exception e) {
            throw new LdapConnectionException(
                    "Failed to open unbound connection to [" + dc.getDisplayName() + "]: "
                    + e.getMessage(), e);
        }
    }

    /**
     * Closes all pools on application shutdown.
     */
    @PreDestroy
    public void closeAll() {
        pools.forEach((id, pool) -> {
            try {
                pool.close();
            } catch (Exception e) {
                log.warn("Error closing LDAP pool {}: {}", id, e.getMessage());
            }
        });
        pools.clear();
    }

    // ── Pool creation ─────────────────────────────────────────────────────────

    private LDAPConnectionPool createPool(DirectoryConnection dc) {
        try {
            String password = encryptionService.decrypt(dc.getBindPasswordEncrypted());
            SimpleBindRequest bindRequest = new SimpleBindRequest(dc.getBindDn(), password);

            LDAPConnectionOptions options = buildOptions(dc);
            ServerSet serverSet = buildServerSet(dc, options);

            LDAPConnectionPool pool;
            if (dc.getSslMode() == SslMode.STARTTLS) {
                SSLUtil sslUtil = buildSslUtil(dc);
                StartTLSPostConnectProcessor startTLS =
                    new StartTLSPostConnectProcessor(sslUtil.createSSLContext());
                pool = new LDAPConnectionPool(
                    serverSet, bindRequest,
                    dc.getPoolMinSize(), dc.getPoolMaxSize(),
                    startTLS);
            } else {
                pool = new LDAPConnectionPool(
                    serverSet, bindRequest,
                    dc.getPoolMinSize(), dc.getPoolMaxSize());
            }

            log.info("Created LDAP pool for [{}] host={}:{} ssl={} min={} max={}",
                dc.getDisplayName(), dc.getHost(), dc.getPort(),
                dc.getSslMode(), dc.getPoolMinSize(), dc.getPoolMaxSize());
            return pool;

        } catch (LdapConnectionException e) {
            throw e;
        } catch (Exception e) {
            throw new LdapConnectionException(
                "Failed to create LDAP pool for [" + dc.getDisplayName() + "]: " + e.getMessage(), e);
        }
    }

    private LDAPConnectionOptions buildOptions(DirectoryConnection dc) {
        LDAPConnectionOptions options = new LDAPConnectionOptions();
        options.setConnectTimeoutMillis(dc.getPoolConnectTimeoutSeconds() * 1_000);
        options.setResponseTimeoutMillis((long) dc.getPoolResponseTimeoutSeconds() * 1_000L);
        return options;
    }

    private ServerSet buildServerSet(DirectoryConnection dc,
                                     LDAPConnectionOptions options) throws Exception {
        ServerSet primary;
        if (dc.getSslMode() == SslMode.LDAPS) {
            javax.net.ssl.SSLSocketFactory sslSocketFactory = buildSslUtil(dc).createSSLSocketFactory();
            primary = new SingleServerSet(dc.getHost(), dc.getPort(), sslSocketFactory, options);
        } else {
            primary = new SingleServerSet(dc.getHost(), dc.getPort(), options);
        }

        // Multi-DC failover: if a secondary host is configured, wrap in FailoverServerSet
        if (dc.getSecondaryHost() != null && !dc.getSecondaryHost().isBlank()) {
            int secondaryPort = dc.getSecondaryPort() != null ? dc.getSecondaryPort() : dc.getPort();
            ServerSet secondary;
            if (dc.getSslMode() == SslMode.LDAPS) {
                javax.net.ssl.SSLSocketFactory sslSocketFactory = buildSslUtil(dc).createSSLSocketFactory();
                secondary = new SingleServerSet(dc.getSecondaryHost(), secondaryPort, sslSocketFactory, options);
            } else {
                secondary = new SingleServerSet(dc.getSecondaryHost(), secondaryPort, options);
            }
            return new FailoverServerSet(primary, secondary);
        }

        return primary;
    }

    private SSLUtil buildSslUtil(DirectoryConnection dc) throws Exception {
        return SslHelper.buildSslUtil(dc.isTrustAllCerts(), dc.getTrustedCertificatePem());
    }

    // ── Functional interface ──────────────────────────────────────────────────

    @FunctionalInterface
    public interface LdapOperation<T> {
        T execute(LDAPConnection connection) throws LDAPException;
    }
}
