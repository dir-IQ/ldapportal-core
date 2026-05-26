// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.entity.AuditDataSource;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.exception.LdapConnectionException;
import com.ldapportal.ldap.changelog.AccesslogStrategy;
import com.ldapportal.ldap.changelog.ChangelogStrategy;
import com.ldapportal.ldap.changelog.DirSyncChangelogStrategy;
import com.ldapportal.ldap.changelog.DseeChangelogStrategy;
import com.ldapportal.repository.AuditDataSourceRepository;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.service.AuditService;
import com.ldapportal.service.EncryptionService;
import com.unboundid.ldap.sdk.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Scheduled poller that reads LDAP changelog / accesslog entries from each
 * enabled {@link AuditDataSource} and persists them as
 * {@link com.ldapportal.entity.AuditEvent} records.
 *
 * <p>Supports two changelog formats via the {@link ChangelogStrategy} pattern:
 * <ul>
 *   <li>{@link DseeChangelogStrategy} — Oracle DSEE {@code cn=changelog}</li>
 *   <li>{@link AccesslogStrategy} — OpenLDAP {@code slapo-accesslog}</li>
 * </ul>
 *
 * <p>The poller runs every 60 seconds by default (configurable via
 * {@code app.audit.changelog-poll-interval-ms}).  It uses an independent
 * short-lived LDAP connection per poll (not a pool) so that disabled or
 * misconfigured sources don't block the scheduler thread.</p>
 *
 * <p>Idempotency: each entry is keyed by
 * {@code (directoryId, entryId)}.  The {@link AuditService} guard
 * prevents duplicate inserts even if the poller restarts mid-run.</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class LdapChangelogReader {

    private static final int MAX_CHANGELOG_ENTRIES_PER_POLL = 500;
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    private final AuditDataSourceRepository   auditSourceRepo;
    private final DirectoryConnectionRepository dirRepo;
    private final AuditService                 auditService;
    private final EncryptionService            encryptionService;

    /** Tracks consecutive poll failures per source for exponential backoff. */
    private final ConcurrentMap<UUID, Integer> consecutiveFailures = new ConcurrentHashMap<>();

    /** Sources disabled at runtime due to configuration errors (e.g. invalid bind DN). */
    private final Set<UUID> configErrors = ConcurrentHashMap.newKeySet();

    // ── Strategy instances (stateless, reusable) ─────────────────────────────

    private static final DseeChangelogStrategy    DSEE_STRATEGY      = new DseeChangelogStrategy();
    private static final AccesslogStrategy       ACCESSLOG_STRATEGY = new AccesslogStrategy();
    private static final DirSyncChangelogStrategy DIRSYNC_STRATEGY  = new DirSyncChangelogStrategy();

    private static ChangelogStrategy strategyFor(AuditDataSource src) {
        return switch (src.getChangelogFormat()) {
            case DSEE_CHANGELOG     -> DSEE_STRATEGY;
            case OPENLDAP_ACCESSLOG -> ACCESSLOG_STRATEGY;
            case AD_DIRSYNC         -> DIRSYNC_STRATEGY;
        };
    }

    // ── Scheduler ─────────────────────────────────────────────────────────────

    @Scheduled(fixedDelayString = "${app.audit.changelog-poll-interval-ms:60000}",
               initialDelayString = "${app.audit.changelog-poll-initial-delay-ms:15000}")
    public void pollAll() {
        List<AuditDataSource> sources = auditSourceRepo.findAll().stream()
                .filter(AuditDataSource::isEnabled)
                .toList();

        // Remove tracking for sources that no longer exist or are disabled
        consecutiveFailures.keySet().retainAll(
                sources.stream().map(AuditDataSource::getId).collect(java.util.stream.Collectors.toSet()));

        for (AuditDataSource src : sources) {
            // Skip sources that have been flagged with configuration errors
            if (configErrors.contains(src.getId())) {
                continue;
            }

            int failures = consecutiveFailures.getOrDefault(src.getId(), 0);
            if (failures >= MAX_CONSECUTIVE_FAILURES) {
                // Exponential backoff: skip 2^(failures-3) polls (i.e. 1, 2, 4, 8…)
                // Reset periodically so we eventually retry
                int skip = 1 << Math.min(failures - MAX_CONSECUTIVE_FAILURES, 6);
                if (failures % skip != 0) {
                    consecutiveFailures.merge(src.getId(), 1, Integer::sum);
                    continue;
                }
            }

            try {
                pollSource(src);
                consecutiveFailures.remove(src.getId());
            } catch (Exception ex) {
                // Check for configuration-level errors (invalid DN, bad credentials)
                // and stop retrying — the admin needs to fix the source config
                if (isConfigError(ex)) {
                    configErrors.add(src.getId());
                    log.error("Changelog polling disabled for source [{}] due to "
                                    + "configuration error (fix the source config to re-enable): {}",
                            src.getDisplayName(), ex.getMessage());
                    continue;
                }

                int newCount = consecutiveFailures.merge(src.getId(), 1, Integer::sum);
                if (newCount <= MAX_CONSECUTIVE_FAILURES) {
                    log.warn("Changelog poll failed for source [{}]: {}",
                            src.getDisplayName(), ex.getMessage(), ex);
                } else {
                    log.warn("Changelog poll failed for source [{}] ({} consecutive failures, "
                                    + "backing off): {}",
                            src.getDisplayName(), newCount, ex.getMessage());
                }
            }
        }
    }

    /**
     * Clear the config-error flag for a source so polling resumes.
     * Called when an audit source is created or updated.
     */
    public void clearConfigError(UUID sourceId) {
        configErrors.remove(sourceId);
        consecutiveFailures.remove(sourceId);
    }

    private static boolean isConfigError(Exception ex) {
        String msg = ex.getMessage();
        if (msg == null) return false;
        String lower = msg.toLowerCase();
        return lower.contains("invalid dn")
                || lower.contains("invalid credentials")
                || lower.contains("no such object");
    }

    // ── Per-source poll ───────────────────────────────────────────────────────

    private void pollSource(AuditDataSource src) {
        ChangelogStrategy strategy = strategyFor(src);
        log.debug("Polling changelog for audit source [{}] (format={})",
                src.getDisplayName(), src.getChangelogFormat());

        // Resolve optional directory association for denormalised fields
        List<DirectoryConnection> linkedDirs = dirRepo.findAll().stream()
                .filter(dc -> dc.getAuditDataSource() != null
                        && dc.getAuditDataSource().getId().equals(src.getId()))
                .toList();

        try (LDAPConnection conn = openConnection(src)) {
            SearchRequest searchReq = strategy.buildSearchRequest(src, MAX_CHANGELOG_ENTRIES_PER_POLL);

            // For AD DirSync: attach the DirSync control with the persisted cookie
            if (src.getChangelogFormat() == com.ldapportal.entity.enums.ChangelogFormat.AD_DIRSYNC) {
                byte[] cookie = src.getDirsyncCookie();
                // DirSync control OID 1.2.840.113556.1.4.841
                // Value: SEQUENCE { flags INTEGER, maxBytes INTEGER, cookie OCTET STRING }
                com.unboundid.asn1.ASN1OctetString cookieValue = (cookie != null && cookie.length > 0)
                        ? new com.unboundid.asn1.ASN1OctetString(cookie)
                        : new com.unboundid.asn1.ASN1OctetString();
                com.unboundid.asn1.ASN1Sequence seq = new com.unboundid.asn1.ASN1Sequence(
                        new com.unboundid.asn1.ASN1Integer(0x80000001),
                        new com.unboundid.asn1.ASN1Integer(Integer.MAX_VALUE),
                        cookieValue);
                searchReq.addControl(new com.unboundid.ldap.sdk.Control(
                        "1.2.840.113556.1.4.841", true,
                        new com.unboundid.asn1.ASN1OctetString(seq.encode())));
            }

            SearchResult result = conn.search(searchReq);

            for (SearchResultEntry entry : result.getSearchEntries()) {
                processEntry(src, linkedDirs, entry, strategy);
            }

            // For AD DirSync: extract and persist the updated cookie
            if (src.getChangelogFormat() == com.ldapportal.entity.enums.ChangelogFormat.AD_DIRSYNC) {
                com.unboundid.ldap.sdk.Control respControl = result.getResponseControl("1.2.840.113556.1.4.841");
                if (respControl != null && respControl.getValue() != null) {
                    try {
                        com.unboundid.asn1.ASN1Sequence seq =
                                com.unboundid.asn1.ASN1Sequence.decodeAsSequence(respControl.getValue().getValue());
                        com.unboundid.asn1.ASN1Element[] elements = seq.elements();
                        if (elements.length >= 3) {
                            byte[] newCookie = elements[2].getValue();
                            src.setDirsyncCookie(newCookie);
                            auditSourceRepo.save(src);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to extract DirSync cookie from response: {}", e.getMessage());
                    }
                }
            }

            log.debug("Processed {} changelog entries for source [{}]",
                    result.getEntryCount(), src.getDisplayName());

        } catch (LDAPException ex) {
            throw new LdapConnectionException(
                    "Changelog read failed for [" + src.getDisplayName() + "]: " + ex.getMessage(), ex);
        }
    }

    private void processEntry(AuditDataSource src,
                              List<DirectoryConnection> linkedDirs,
                              SearchResultEntry entry,
                              ChangelogStrategy strategy) {
        if (!strategy.isRecordable(entry)) {
            return;
        }

        String entryId = strategy.extractEntryId(entry);
        if (entryId == null) {
            return;
        }

        String targetDn = strategy.extractTargetDn(entry);

        // Resolve directory association by matching the targetDN to linked dirs
        DirectoryConnection matchedDir = linkedDirs.stream()
                .filter(dc -> targetDn != null
                        && targetDn.toLowerCase().endsWith(dc.getBaseDn().toLowerCase()))
                .findFirst().orElse(null);

        UUID   directoryId   = matchedDir != null ? matchedDir.getId() : null;
        String directoryName = matchedDir != null ? matchedDir.getDisplayName() : null;

        // Skip if already recorded
        if (directoryId != null &&
                auditService.isChangelogEventRecorded(directoryId, entryId)) {
            return;
        }

        Map<String, Object> detail = strategy.extractDetail(entry);
        OffsetDateTime occurredAt  = strategy.extractOccurredAt(entry);

        auditService.recordChangelogEvent(
                directoryId,
                directoryName,
                targetDn,
                entryId,
                detail,
                occurredAt);
    }

    // ── LDAP connection helpers ───────────────────────────────────────────────

    private LDAPConnection openConnection(AuditDataSource src) {
        try {
            String password = encryptionService.decrypt(src.getBindPasswordEncrypted());
            String bindDn = src.getBindDn() != null ? src.getBindDn().trim() : "";

            LDAPConnectionOptions opts = new LDAPConnectionOptions();
            opts.setConnectTimeoutMillis(10_000);
            opts.setResponseTimeoutMillis(30_000L);

            LDAPConnection conn;
            if (src.getSslMode() == SslMode.LDAPS) {
                com.unboundid.util.ssl.SSLUtil sslUtil = buildSslUtil(src);
                conn = new LDAPConnection(sslUtil.createSSLSocketFactory(),
                        opts, src.getHost(), src.getPort());
            } else {
                conn = new LDAPConnection(opts, src.getHost(), src.getPort());
                if (src.getSslMode() == SslMode.STARTTLS) {
                    try {
                        com.unboundid.util.ssl.SSLUtil sslUtil = buildSslUtil(src);
                        conn.processExtendedOperation(
                                new com.unboundid.ldap.sdk.extensions.StartTLSExtendedRequest(
                                        sslUtil.createSSLContext()));
                    } catch (Exception ex) {
                        conn.close();
                        throw ex;
                    }
                }
            }

            try {
                conn.bind(bindDn, password);
            } catch (Exception ex) {
                conn.close();
                throw ex;
            }
            return conn;

        } catch (LdapConnectionException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new LdapConnectionException(
                    "Failed to connect to audit source [" + src.getDisplayName() + "]: "
                    + ex.getMessage(), ex);
        }
    }

    private com.unboundid.util.ssl.SSLUtil buildSslUtil(AuditDataSource src) throws Exception {
        return SslHelper.buildSslUtil(src.isTrustAllCerts(), src.getTrustedCertificatePem());
    }
}
