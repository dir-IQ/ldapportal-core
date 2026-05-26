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
        String encryptedPassword = encryptionService.encrypt(req.bindPassword());
        AuditDataSource src = new AuditDataSource();
        applyRequest(src, req, encryptedPassword);
        AuditSourceResponse resp = AuditSourceResponse.from(auditSourceRepo.save(src));
        changelogReader.clearConfigError(resp.id());
        return resp;
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
                    AuditDataSource probe = new AuditDataSource();
                    probe.setChangelogBaseDn(changelogDn);
                    probe.setChangelogFormat(req.changelogFormat());
                    probe.setBranchFilterDn(req.branchFilterDn());
                    try {
                        SearchRequest verifyReq = strategy.buildSearchRequest(probe, 1);
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
}
