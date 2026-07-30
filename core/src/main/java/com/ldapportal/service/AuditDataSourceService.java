// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.dto.audit.AuditSourceRequest;
import com.ldapportal.dto.audit.AuditSourceResponse;
import com.ldapportal.dto.directory.TestConnectionResult;
import com.ldapportal.entity.AuditDataSource;
import com.ldapportal.entity.enums.ChangelogFormat;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.ldap.LdapChangelogReader;
import com.ldapportal.ldap.SslHelper;
import com.ldapportal.ldap.changelog.AccesslogStrategy;
import com.ldapportal.ldap.changelog.ChangelogReadContext;
import com.ldapportal.ldap.changelog.ChangelogStrategy;
import com.ldapportal.ldap.changelog.DseeChangelogStrategy;
import com.ldapportal.repository.AuditDataSourceRepository;
import com.unboundid.ldap.sdk.*;
import com.unboundid.util.ssl.SSLUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuditDataSourceService {

    private final AuditDataSourceRepository auditSourceRepo;
    private final EncryptionService         encryptionService;
    private final LdapChangelogReader       changelogReader;

    // ── CRUD ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<AuditSourceResponse> list() {
        return auditSourceRepo.findAll().stream()
                .map(AuditSourceResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public AuditSourceResponse get(UUID id) {
        return AuditSourceResponse.from(load(id));
    }

    @Transactional
    public AuditSourceResponse create(AuditSourceRequest req) {
        return doCreate(resolveSlug(req.slug(), req.displayName()), req);
    }

    private AuditSourceResponse doCreate(String slug, AuditSourceRequest req) {
        if (req.bindPassword() == null || req.bindPassword().isBlank()) {
            throw new IllegalArgumentException("bindPassword is required when creating an audit data source");
        }
        String encryptedPassword = encryptionService.encrypt(req.bindPassword());
        AuditDataSource src = new AuditDataSource();
        src.setSlug(slug);
        applyRequest(src, req, encryptedPassword);
        AuditSourceResponse resp = AuditSourceResponse.from(auditSourceRepo.save(src));
        changelogReader.clearConfigError(resp.id());
        return resp;
    }

    /**
     * Idempotent create-or-update keyed by the stable IaC slug — the audit-source
     * analogue of {@code DirectoryConnectionService.upsertBySlug}. When a source
     * with the slug exists it is updated in place (bind password preserved when
     * omitted); otherwise a new source is created with exactly that slug.
     */
    @Transactional
    public UpsertOutcome upsertBySlug(String slug, AuditSourceRequest req) {
        String normalized = normalizeSlug(slug);
        return auditSourceRepo.findBySlug(normalized)
                .map(existing -> new UpsertOutcome(update(existing.getId(), req), false))
                .orElseGet(() -> new UpsertOutcome(doCreate(normalized, req), true));
    }

    /** Result of an idempotent upsert: the saved view plus whether a row was created. */
    public record UpsertOutcome(AuditSourceResponse response, boolean created) {
    }

    @Transactional
    public AuditSourceResponse update(UUID id, AuditSourceRequest req) {
        AuditDataSource src = load(id);
        String encryptedPassword = (req.bindPassword() != null && !req.bindPassword().isBlank())
                ? encryptionService.encrypt(req.bindPassword())
                : src.getBindPasswordEncrypted();
        applyRequest(src, req, encryptedPassword);
        changelogReader.clearConfigError(id);
        return AuditSourceResponse.from(auditSourceRepo.save(src));
    }

    @Transactional
    public void delete(UUID id) {
        auditSourceRepo.delete(load(id));
    }

    // ── Test connection ────────────────────────────────────────────────────────

    public TestConnectionResult testConnection(AuditSourceRequest req) {
        Instant start = Instant.now();
        try {
            String bindDn = req.bindDn().trim();
            String password = req.bindPassword();

            LDAPConnectionOptions opts = new LDAPConnectionOptions();
            opts.setConnectTimeoutMillis(10_000);
            opts.setResponseTimeoutMillis(10_000L);

            LDAPConnection conn;
            if (req.sslMode() == SslMode.LDAPS) {
                SSLUtil sslUtil = SslHelper.buildSslUtil(req.trustAllCerts(), req.trustedCertificatePem());
                conn = new LDAPConnection(sslUtil.createSSLSocketFactory(),
                        opts, req.host().trim(), req.port());
            } else {
                conn = new LDAPConnection(opts, req.host().trim(), req.port());
                if (req.sslMode() == SslMode.STARTTLS) {
                    SSLUtil sslUtil = SslHelper.buildSslUtil(req.trustAllCerts(), req.trustedCertificatePem());
                    conn.processExtendedOperation(
                            new com.unboundid.ldap.sdk.extensions.StartTLSExtendedRequest(
                                    sslUtil.createSSLContext()));
                }
            }

            try (conn) {
                BindResult result = conn.bind(new SimpleBindRequest(bindDn, password));
                long ms = Duration.between(start, Instant.now()).toMillis();

                if (result.getResultCode() == ResultCode.SUCCESS) {
                    // Verify changelog base DN is reachable
                    String changelogDn = req.changelogBaseDn() != null
                            ? req.changelogBaseDn().trim() : "cn=changelog";
                    // Use a strategy-aware search to verify the changelog base is reachable
                    // and that the configured format returns results
                    ChangelogStrategy strategy = req.changelogFormat() == ChangelogFormat.OPENLDAP_ACCESSLOG
                            ? new AccesslogStrategy() : new DseeChangelogStrategy();
                    ChangelogReadContext ctx =
                            new ChangelogReadContext(changelogDn, req.branchFilterDn(), null);
                    try {
                        SearchRequest verifyReq = strategy.buildSearchRequest(ctx, 1);
                        conn.search(verifyReq);
                    } catch (LDAPException ex) {
                        return new TestConnectionResult(false,
                                "Bind OK, but changelog base DN '" + changelogDn
                                        + "' is not reachable: " + ex.getMessage(), ms);
                    }
                    return new TestConnectionResult(true,
                            "Connection, bind, and changelog base DN verified", ms);
                }
                return new TestConnectionResult(false,
                        "Bind failed: " + result.getResultCode().getName(), ms);
            }
        } catch (Exception ex) {
            long ms = Duration.between(start, Instant.now()).toMillis();
            return new TestConnectionResult(false, ex.getMessage(), ms);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void applyRequest(AuditDataSource src, AuditSourceRequest req,
                              String encryptedPassword) {
        src.setDisplayName(req.displayName().trim());
        src.setHost(req.host().trim());
        src.setPort(req.port());
        src.setSslMode(req.sslMode());
        src.setTrustAllCerts(req.trustAllCerts());
        src.setTrustedCertificatePem(req.trustedCertificatePem());
        src.setBindDn(req.bindDn().trim());
        src.setBindPasswordEncrypted(encryptedPassword);
        src.setChangelogBaseDn(req.changelogBaseDn() != null
                ? req.changelogBaseDn().trim() : "cn=changelog");
        src.setBranchFilterDn(req.branchFilterDn() != null
                ? req.branchFilterDn().trim() : null);
        src.setChangelogFormat(req.changelogFormat());
        src.setEnabled(req.enabled());
    }

    private AuditDataSource load(UUID id) {
        return auditSourceRepo.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AuditDataSource", id));
    }

    // ── Slug (IaC external key) helpers ────────────────────────────────────────

    private static final java.util.regex.Pattern SLUG_PATTERN =
            java.util.regex.Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    private String normalizeSlug(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("slug is required");
        }
        String s = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.length() > 100 || !SLUG_PATTERN.matcher(s).matches()) {
            throw new IllegalArgumentException(
                    "slug must be lowercase alphanumeric segments separated by single hyphens "
                            + "(max 100 chars): " + raw);
        }
        return s;
    }

    private String resolveSlug(String requested, String displayName) {
        String base = (requested != null && !requested.isBlank())
                ? normalizeSlug(requested)
                : slugify(displayName);
        String candidate = base;
        int n = 2;
        while (auditSourceRepo.findBySlug(candidate).isPresent()) {
            candidate = base + "-" + n++;
        }
        return candidate;
    }

    private String slugify(String displayName) {
        String base = displayName == null ? "" : displayName
                .trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        if (base.length() > 100) {
            base = base.substring(0, 100).replaceAll("-+$", "");
        }
        return base.isEmpty() ? "audit-source" : base;
    }

    // ── IaC export ─────────────────────────────────────────────────────────────

    /**
     * A single audit data source rendered for config export: an
     * {@link AuditSourceRequest} carrying the full restorable declaration (slug,
     * connection, changelog config, trusted cert PEM) with the bind password
     * omitted, plus a flag telling the exporter whether one is stored (so it can
     * emit a {@code ${ENV_VAR}} placeholder without reading the secret back).
     */
    public record AuditSourceExport(AuditSourceRequest request, boolean bindPasswordSet) {
    }

    /**
     * Export every audit data source as a restorable {@link AuditSourceExport},
     * ordered by slug for stable, diff-friendly output. Runtime sync state
     * ({@code dirsyncCookie}) is intentionally excluded — only configuration is
     * emitted — and the bind password is never exposed.
     */
    @Transactional(readOnly = true)
    public List<AuditSourceExport> exportAll() {
        return auditSourceRepo.findAll().stream()
                .sorted(java.util.Comparator.comparing(AuditDataSource::getSlug,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .map(s -> new AuditSourceExport(
                        new AuditSourceRequest(
                                s.getDisplayName(),
                                s.getHost(),
                                s.getPort(),
                                s.getSslMode(),
                                s.isTrustAllCerts(),
                                s.getTrustedCertificatePem(),
                                s.getBindDn(),
                                null,                       // bindPassword — write-only, never exported
                                s.getChangelogBaseDn(),
                                s.getBranchFilterDn(),
                                s.getChangelogFormat(),
                                s.isEnabled(),
                                s.getSlug()),
                        s.getBindPasswordEncrypted() != null && !s.getBindPasswordEncrypted().isBlank()))
                .toList();
    }
}
