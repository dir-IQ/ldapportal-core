// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.dto.directory.DirectoryConnectionRequest;
import com.ldapportal.dto.directory.DirectoryConnectionResponse;
import com.ldapportal.dto.directory.TestConnectionRequest;
import com.ldapportal.dto.directory.TestConnectionResult;
import com.ldapportal.entity.AuditDataSource;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.DirectoryGroupBaseDn;
import com.ldapportal.entity.DirectoryUserBaseDn;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.ldapportal.ldap.SslHelper;
import com.ldapportal.core.directory.event.DirectoryConnectionSavedEvent;
import com.ldapportal.core.directory.event.DirectoryCreatedEvent;
import org.springframework.context.ApplicationEventPublisher;
import com.ldapportal.repository.AuditDataSourceRepository;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.DirectoryGroupBaseDnRepository;
import com.ldapportal.repository.DirectoryUserBaseDnRepository;
import com.unboundid.ldap.sdk.BindResult;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPConnectionOptions;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SimpleBindRequest;
import com.unboundid.util.ssl.SSLUtil;
import com.unboundid.util.ssl.TrustAllTrustManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class DirectoryConnectionService {

    private final DirectoryConnectionRepository  dirRepo;
    private final DirectoryUserBaseDnRepository  userBaseDnRepo;
    private final DirectoryGroupBaseDnRepository groupBaseDnRepo;
    private final AuditDataSourceRepository      auditSourceRepo;
    private final EncryptionService              encryptionService;
    private final LdapConnectionFactory          connectionFactory;
    private final ApplicationEventPublisher      eventPublisher;
    private final com.ldapportal.core.entitlement.UsageLimitService usageLimitService;
    private final com.ldapportal.directory.DirectoryProviderRegistry providerRegistry;

    // ── CRUD ──────────────────────────────────────────────────────────────────

    public List<DirectoryConnectionResponse> listDirectories() {
        return dirRepo.findAll().stream().map(this::toResponse).toList();
    }

    public DirectoryConnectionResponse getDirectory(UUID id) {
        return toResponse(require(id));
    }

    @Transactional
    public DirectoryConnectionResponse createDirectory(DirectoryConnectionRequest req) {
        return doCreateDirectory(resolveSlug(req.slug(), req.displayName()), req);
    }

    /**
     * Idempotent create-or-update keyed by the stable IaC slug (§4.1). When
     * a directory with the given slug exists it is updated in place (full
     * replace, secrets preserved when omitted); otherwise a new directory is
     * created with exactly that slug. The {@code created} flag lets the
     * controller map to 201 vs 200 so automation can report drift accurately.
     */
    @Transactional
    public UpsertOutcome upsertBySlug(String slug, DirectoryConnectionRequest req) {
        return upsertBySlug(slug, req, null);
    }

    /**
     * Slug upsert with an optional {@code If-Match} precondition (§4.4). When a
     * matching directory exists the {@code expectedVersion}, if non-null, must
     * equal its current version or the update is rejected with a 412. The check
     * applies only on the update path — a create has no prior version to match.
     */
    @Transactional
    public UpsertOutcome upsertBySlug(String slug, DirectoryConnectionRequest req, Long expectedVersion) {
        String normalized = normalizeSlug(slug);
        return dirRepo.findBySlug(normalized)
                .map(existing -> new UpsertOutcome(
                        updateDirectory(existing.getId(), req, expectedVersion), false))
                .orElseGet(() -> new UpsertOutcome(doCreateDirectory(normalized, req), true));
    }

    /** Result of an idempotent upsert: the saved view plus whether a row was created. */
    public record UpsertOutcome(DirectoryConnectionResponse response, boolean created) {
    }

    private DirectoryConnectionResponse doCreateDirectory(String slug, DirectoryConnectionRequest req) {
        // License cap check — fires before any real work so a 402 is
        // reported back to the caller without side effects. The count is
        // read inside this @Transactional so a concurrent create that
        // commits first sees the updated count here; losing the race
        // results in one extra row at worst, which is acceptable and
        // documented in docs/edition-boundary.md §Limits.
        usageLimitService.requireWithinLimit(
                com.ldapportal.core.entitlement.LimitType.DIRECTORIES,
                dirRepo.count());

        DirectoryConnection dc = new DirectoryConnection();
        dc.setSlug(slug);
        applyRequest(dc, req);

        if (req.directoryType() == com.ldapportal.entity.enums.DirectoryType.ENTRA_ID) {
            // Entra ID: client secret instead of bind password
            if (req.entraClientSecret() != null && !req.entraClientSecret().isBlank()) {
                dc.setEntraClientSecretEncrypted(encryptionService.encrypt(req.entraClientSecret()));
            }
            // Set LDAP defaults to satisfy non-null DB constraints
            if (dc.getHost() == null) dc.setHost("n/a");
            if (dc.getBindDn() == null) dc.setBindDn("n/a");
            if (dc.getBaseDn() == null) dc.setBaseDn("n/a");
            if (dc.getBindPasswordEncrypted() == null) dc.setBindPasswordEncrypted("n/a");
            if (dc.getSslMode() == null) dc.setSslMode(com.ldapportal.entity.enums.SslMode.NONE);
        } else {
            // LDAP: bind password required
            if (req.bindPassword() != null && !req.bindPassword().isBlank()) {
                dc.setBindPasswordEncrypted(encryptionService.encrypt(req.bindPassword()));
            } else {
                throw new IllegalArgumentException("bindPassword is required when creating a directory");
            }
        }

        dc = dirRepo.save(dc);
        saveBaseDns(dc, req);

        // Fan-out: modules (e.g. ee.alerting) can listen and do per-directory
        // setup. Published synchronously within the @Transactional boundary
        // so listener failures roll back the directory create — this
        // preserves the pre-refactor behaviour where alert-seeding failure
        // was logged-but-swallowed (see AlertAutoSeeder).
        eventPublisher.publishEvent(new DirectoryCreatedEvent(dc.getId()));

        // Capability probe runs out-of-band via DirectoryCapabilityRefresher
        // (AFTER_COMMIT, REQUIRES_NEW). Publishing inside the tx is fine —
        // Spring routes AFTER_COMMIT listeners through TransactionSynchronization,
        // so the event-fire here doesn't make the probe run until commit
        // succeeds. If the create rolls back (e.g. an alerting listener
        // throws above), the saved-event never reaches the refresher.
        eventPublisher.publishEvent(new DirectoryConnectionSavedEvent(dc.getId()));

        return toResponse(dc);
    }

    @Transactional
    public DirectoryConnectionResponse updateDirectory(UUID id, DirectoryConnectionRequest req) {
        return updateDirectory(id, req, null);
    }

    @Transactional
    public DirectoryConnectionResponse updateDirectory(UUID id, DirectoryConnectionRequest req,
                                                       Long expectedVersion) {
        DirectoryConnection dc = require(id);
        com.ldapportal.web.ETagSupport.requireMatch(expectedVersion, dc.getVersion());
        applyRequest(dc, req);

        if (req.bindPassword() != null && !req.bindPassword().isBlank()) {
            dc.setBindPasswordEncrypted(encryptionService.encrypt(req.bindPassword()));
        }
        if (req.entraClientSecret() != null && !req.entraClientSecret().isBlank()) {
            dc.setEntraClientSecretEncrypted(encryptionService.encrypt(req.entraClientSecret()));
        }

        // Clear capabilities on every update — host / port / credentials
        // / type may have changed, and any one of those invalidates the
        // stored vendor / OID snapshot. Clearing unconditionally (rather
        // than guessing which subset of fields matters) keeps the invariant
        // simple: between updateDirectory commit and the AFTER_COMMIT
        // probe completing, the chip is hidden. A probe failure then
        // leaves the row in a truthful "unknown" state rather than
        // displaying stale vendor data from a previous host.
        dc.setCapabilities(null);

        connectionFactory.evict(dc.getId());
        dc = dirRepo.save(dc);
        saveBaseDns(dc, req);

        // Re-probe out-of-band via DirectoryCapabilityRefresher — runs
        // AFTER_COMMIT, off the request thread, on a connectionless
        // listener path so nothing pins a DB or LDAP resource through
        // the probe round-trip.
        eventPublisher.publishEvent(new DirectoryConnectionSavedEvent(dc.getId()));

        return toResponse(dc);
    }

    @Transactional
    public void deleteDirectory(UUID id) {
        DirectoryConnection dc = require(id);
        connectionFactory.evict(dc.getId());
        dirRepo.delete(dc);
    }

    public void evictPool(UUID id) {
        require(id);
        connectionFactory.evict(id);
        log.info("Pool evicted for directory {}", id);
    }

    // ── Test connection ───────────────────────────────────────────────────────

    public TestConnectionResult testConnection(TestConnectionRequest req) {
        Instant start = Instant.now();
        try {
            try (LDAPConnection conn = openTestConnection(req)) {
                BindResult result = conn.bind(
                        new SimpleBindRequest(req.bindDn(), req.bindPassword()));
                long ms = Duration.between(start, Instant.now()).toMillis();

                if (result.getResultCode() == ResultCode.SUCCESS) {
                    return new TestConnectionResult(true, "Connection and bind successful", ms);
                }
                return new TestConnectionResult(false,
                        "Bind failed: " + result.getResultCode().getName(), ms);
            }
        } catch (Exception ex) {
            long ms = Duration.between(start, Instant.now()).toMillis();
            return new TestConnectionResult(false, ex.getMessage(), ms);
        }
    }

    /**
     * Probe live connectivity of a <em>stored</em> directory using its saved
     * credentials. Unlike {@link #testConnection(TestConnectionRequest)} —
     * which validates an unsaved form before persisting — this resolves the
     * directory by id and delegates to the type-appropriate
     * {@link com.ldapportal.directory.DirectoryProvider}, so it works
     * uniformly for LDAP and Entra. Drives the per-row status dot on the
     * Directory Connections page (and mirrors the dashboard's reachability
     * signal). Never throws on connectivity failure — the failure is the
     * result, returned as {@code success=false} with the provider's message.
     */
    public TestConnectionResult checkConnection(UUID id) {
        DirectoryConnection dc = require(id);
        Instant start = Instant.now();
        String error = providerRegistry.getProvider(dc).testConnection(dc);
        long ms = Duration.between(start, Instant.now()).toMillis();
        return error == null
                ? new TestConnectionResult(true, "Reachable", ms)
                : new TestConnectionResult(false, error, ms);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void applyRequest(DirectoryConnection dc, DirectoryConnectionRequest req) {
        dc.setDirectoryType(req.directoryType() != null ? req.directoryType()
                : com.ldapportal.entity.enums.DirectoryType.GENERIC);
        dc.setDisplayName(req.displayName());
        dc.setHost(req.host());
        dc.setPort(req.port());
        dc.setSslMode(req.sslMode() != null ? req.sslMode() : com.ldapportal.entity.enums.SslMode.NONE);
        dc.setTrustAllCerts(req.trustAllCerts());
        dc.setTrustedCertificatePem(req.trustedCertificatePem());
        dc.setBindDn(req.bindDn());
        dc.setBaseDn(req.baseDn());
        dc.setPagingSize(req.pagingSize());
        dc.setPoolMinSize(req.poolMinSize());
        dc.setPoolMaxSize(req.poolMaxSize());
        dc.setPoolConnectTimeoutSeconds(req.poolConnectTimeoutSeconds());
        dc.setPoolResponseTimeoutSeconds(req.poolResponseTimeoutSeconds());
        dc.setEnableDisableAttribute(req.enableDisableAttribute());
        dc.setEnableDisableValueType(req.enableDisableValueType());
        dc.setEnableValue(req.enableValue());
        dc.setDisableValue(req.disableValue());
        dc.setEnabled(req.enabled());
        dc.setSecondaryHost(req.secondaryHost());
        dc.setSecondaryPort(req.secondaryPort());
        dc.setGlobalCatalogPort(req.globalCatalogPort());
        dc.setSelfServiceEnabled(req.selfServiceEnabled());
        dc.setReplicationEnabled(req.replicationEnabled());
        dc.setSelfServiceLoginAttribute(
                req.selfServiceLoginAttribute() != null && !req.selfServiceLoginAttribute().isBlank()
                        ? req.selfServiceLoginAttribute() : "uid");

        if (req.auditDataSourceId() != null) {
            AuditDataSource auditSrc = auditSourceRepo.findById(req.auditDataSourceId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "AuditDataSource", req.auditDataSourceId()));
            dc.setAuditDataSource(auditSrc);
        } else {
            dc.setAuditDataSource(null);
        }

        // Entra ID fields
        dc.setTenantId(req.tenantId());
        dc.setEntraClientId(req.entraClientId());
        dc.setGraphEndpoint(req.graphEndpoint());
    }

    private void saveBaseDns(DirectoryConnection dc, DirectoryConnectionRequest req) {
        userBaseDnRepo.deleteAllByDirectoryId(dc.getId());
        groupBaseDnRepo.deleteAllByDirectoryId(dc.getId());

        if (req.userBaseDns() != null) {
            req.userBaseDns().forEach(b -> {
                DirectoryUserBaseDn e = new DirectoryUserBaseDn();
                e.setDirectory(dc);
                e.setDn(b.dn());
                e.setDisplayOrder(b.displayOrder());
                userBaseDnRepo.save(e);
            });
        }
        if (req.groupBaseDns() != null) {
            req.groupBaseDns().forEach(b -> {
                DirectoryGroupBaseDn e = new DirectoryGroupBaseDn();
                e.setDirectory(dc);
                e.setDn(b.dn());
                e.setDisplayOrder(b.displayOrder());
                groupBaseDnRepo.save(e);
            });
        }
    }

    private DirectoryConnectionResponse toResponse(DirectoryConnection dc) {
        List<DirectoryUserBaseDn>  users  = userBaseDnRepo.findAllByDirectoryIdOrderByDisplayOrderAsc(dc.getId());
        List<DirectoryGroupBaseDn> groups = groupBaseDnRepo.findAllByDirectoryIdOrderByDisplayOrderAsc(dc.getId());
        return DirectoryConnectionResponse.from(dc, users, groups);
    }

    private DirectoryConnection require(UUID id) {
        return dirRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("DirectoryConnection", id));
    }

    // ── Slug (IaC external key) helpers ────────────────────────────────────────

    private static final Pattern SLUG_PATTERN =
            Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    /**
     * Validate and normalize a caller-supplied slug (path segment on the
     * upsert endpoint). Lowercased and trimmed; rejected with a 400-class
     * {@link IllegalArgumentException} if it isn't a clean
     * hyphen-separated alphanumeric token of at most 100 chars.
     */
    private String normalizeSlug(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("slug is required");
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.length() > 100 || !SLUG_PATTERN.matcher(s).matches()) {
            throw new IllegalArgumentException(
                    "slug must be lowercase alphanumeric segments separated by single hyphens "
                            + "(max 100 chars): " + raw);
        }
        return s;
    }

    /**
     * Resolve the slug for a plain create: use the caller's value when
     * supplied (validated), otherwise derive one from the display name and
     * append a numeric suffix until it's unique. Keeps {@code POST} creates
     * backward-compatible (no slug in the body) while still producing a
     * stable key the IaC upsert can target later.
     */
    private String resolveSlug(String requested, String displayName) {
        String base = (requested != null && !requested.isBlank())
                ? normalizeSlug(requested)
                : slugify(displayName);
        String candidate = base;
        int n = 2;
        while (dirRepo.findBySlug(candidate).isPresent()) {
            candidate = base + "-" + n++;
        }
        return candidate;
    }

    private String slugify(String displayName) {
        String base = displayName == null ? "" : displayName
                .trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        if (base.length() > 100) {
            base = base.substring(0, 100).replaceAll("-+$", "");
        }
        return base.isEmpty() ? "directory" : base;
    }

    // ── One-shot SSL connection for test ──────────────────────────────────────

    private LDAPConnection openTestConnection(TestConnectionRequest req) throws Exception {
        LDAPConnectionOptions options = new LDAPConnectionOptions();
        options.setConnectTimeoutMillis(10_000);
        options.setResponseTimeoutMillis(10_000L);

        if (req.sslMode() == SslMode.LDAPS) {
            SSLUtil sslUtil = buildTestSslUtil(req);
            SSLSocketFactory sf = sslUtil.createSSLSocketFactory();
            return new LDAPConnection(sf, options, req.host(), req.port());
        }

        LDAPConnection conn = new LDAPConnection(options, req.host(), req.port());

        if (req.sslMode() == SslMode.STARTTLS) {
            SSLUtil sslUtil = buildTestSslUtil(req);
            com.unboundid.ldap.sdk.extensions.StartTLSExtendedRequest startTls =
                    new com.unboundid.ldap.sdk.extensions.StartTLSExtendedRequest(
                            sslUtil.createSSLContext());
            conn.processExtendedOperation(startTls);
        }

        return conn;
    }

    private SSLUtil buildTestSslUtil(TestConnectionRequest req) throws Exception {
        return SslHelper.buildSslUtil(req.trustAllCerts(), req.trustedCertificatePem());
    }
}
