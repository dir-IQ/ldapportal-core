// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.exception.LdapConnectionException;
import com.ldapportal.exception.LdapOperationException;
import com.ldapportal.ldap.replication.ReplicatingLdapInterface;
import com.ldapportal.ldap.replication.ReplicationEnqueuer;
import com.ldapportal.ldap.annotation.LdapWriteAuthorized;
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
@LdapWriteAuthorized("Constructs the write surface — wraps pooled connections in "
        + "ReplicatingLdapInterface and hands out the write-capable interface; "
        + "issues no mutating calls itself.")
public class LdapConnectionFactory {

    private final EncryptionService encryptionService;

    /**
     * Replication enqueuer for app-initiated writes. Wired via Spring;
     * may be {@code null} in unit tests that construct the factory
     * directly. When null, {@link #withConnection} passes the raw
     * {@link LDAPConnection} through without wrapping — replication is
     * inactive.
     */
    private final ReplicationEnqueuer replicationEnqueuer;

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
     * <p>On {@link LDAPException}, distinguishes two cases via
     * {@link ResultCode#isConnectionUsable()}:
     * <ul>
     *   <li><b>Connection broken</b> (network failure, server closed
     *       the socket, SASL bind error, etc) — releases the
     *       connection as defunct so the pool shrinks, and wraps
     *       as {@link LdapConnectionException} → 502 in the global
     *       handler ("LDAP server unreachable").</li>
     *   <li><b>Operation-level error</b> ({@code NO_SUCH_OBJECT},
     *       {@code INVALID_DN_SYNTAX}, {@code INSUFFICIENT_ACCESS_RIGHTS},
     *       constraint violations, etc) — returns the connection to the
     *       pool normally and wraps as {@link LdapOperationException}
     *       → 422 in the global handler, with the LDAP message preserved.
     *       Operators get the actual root cause instead of a misleading
     *       "LDAP server unreachable".</li>
     * </ul>
     *
     * <p>Callers that need finer per-result-code handling (e.g.
     * "treat {@code NO_SUCH_OBJECT} as a non-error return value")
     * should catch {@link LDAPException} inside the lambda and
     * translate before re-throwing — see
     * {@link LdapBrowseService#entryExists}.</p>
     */
    public <T> T withConnection(DirectoryConnection dc,
                                LdapOperation<T> operation) {
        return doWithConnection(dc, operation, replicationEnqueuer != null);
    }

    /**
     * Variant of {@link #withConnection} that bypasses the replication
     * wrapper. Use sparingly — every call site must have a deliberate
     * reason not to participate in replication capture. Currently
     * intended for two paths:
     *
     * <ol>
     *   <li>The {@code ReplicationWorker} (P1) — target-side writes
     *       must not loop back into the queue.</li>
     *   <li>The {@code ChangelogReplicationEnqueuer} (future) — its
     *       own LDAP reads on the source are uninteresting.</li>
     * </ol>
     *
     * Everything else should call {@link #withConnection}.
     */
    public <T> T withConnectionUnreplicated(DirectoryConnection dc,
                                             LdapOperation<T> operation) {
        return doWithConnection(dc, operation, false);
    }

    private <T> T doWithConnection(DirectoryConnection dc,
                                    LdapOperation<T> operation,
                                    boolean replicate) {
        LDAPConnectionPool pool = getPool(dc);
        LDAPConnection conn = null;
        try {
            conn = pool.getConnection();
            // Per-directory replication gate (R2): when the directory's
            // master switch is off, skip the capture wrapper entirely —
            // no events accumulate for any link sourced here. The
            // entitlement-level degradation (community edition) is enforced
            // downstream in ReplicationEnqueuer.
            boolean wrap = replicate
                    && replicationEnqueuer != null
                    && dc.isReplicationEnabled();
            com.unboundid.ldap.sdk.FullLDAPInterface iface =
                    wrap
                    ? new ReplicatingLdapInterface(conn, replicationEnqueuer, dc.getId())
                    : conn;
            return operation.execute(iface);
        } catch (LDAPException e) {
            boolean connectionBroken = !e.getResultCode().isConnectionUsable();
            if (conn != null) {
                if (connectionBroken) {
                    pool.releaseDefunctConnection(conn);
                } else {
                    pool.releaseConnection(conn);
                }
                conn = null;
            }
            if (connectionBroken) {
                throw new LdapConnectionException(
                    "LDAP connection failed on [" + dc.getDisplayName() + "]: " + e.getMessage(), e);
            }
            throw new LdapOperationException(
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

    /**
     * Callback receiving a {@link com.unboundid.ldap.sdk.FullLDAPInterface}
     * — either a raw {@link LDAPConnection} (from
     * {@link #withConnectionUnreplicated}) or a
     * {@code ReplicatingLdapInterface} wrapping one (from
     * {@link #withConnection}). The wrapper intercepts successful
     * writes to enqueue replication events; reads pass through.
     *
     * <p>The parameter type is {@code FullLDAPInterface} rather than
     * the narrower {@code LDAPInterface} so addon callers that need
     * {@code bind()} or {@code processExtendedOperation()} inside a
     * lambda still compile — both methods live on
     * {@code FullLDAPInterface} and are implemented by both
     * {@code LDAPConnection} and {@code ReplicatingLdapInterface}.
     *
     * <p>Callers must not cast to {@code LDAPConnection} — there's no
     * guarantee about the underlying type. Methods that need
     * connection-only features (reconnect, getConnectedAddress, etc.)
     * should use {@link #openUnboundConnection} instead of this
     * callback surface.
     */
    @FunctionalInterface
    public interface LdapOperation<T> {
        T execute(com.unboundid.ldap.sdk.FullLDAPInterface connection) throws LDAPException;
    }
}
