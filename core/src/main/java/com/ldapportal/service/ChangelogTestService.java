// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.dto.replication.ChangelogTestResult;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.ldapportal.ldap.changelog.ChangelogReadContext;
import com.ldapportal.ldap.changelog.DseeChangelogStrategy;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.unboundid.ldap.sdk.FullLDAPInterface;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.LDAPSearchException;
import com.unboundid.ldap.sdk.RootDSE;
import com.unboundid.ldap.sdk.SearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Config-time changelog capability probe (§7A.11). Verifies, before a CHANGELOG
 * link can be enabled, that the source actually supports the cursor model:
 * <ul>
 *   <li>the root DSE exposes {@code firstChangeNumber} + {@code lastChangeNumber}
 *       — without them gap/cursor-reset detection is blind, so this is a
 *       <b>hard fail</b> (a non-conforming server can't be silently enabled);</li>
 *   <li>the {@code changelogBaseDn} is readable and its entries carry
 *       {@code changeNumber}.</li>
 * </ul>
 * Reads over the source directory's pooled, bounded-timeout connection.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChangelogTestService {

    private final DirectoryConnectionRepository dirRepo;
    private final LdapConnectionFactory         connectionFactory;

    private static final DseeChangelogStrategy DSEE = new DseeChangelogStrategy();
    private static final String DEFAULT_BASE_DN = "cn=changelog";

    public ChangelogTestResult test(UUID directoryId, String changelogBaseDn) {
        long start = System.nanoTime();
        DirectoryConnection dir = dirRepo.findById(directoryId)
                .orElseThrow(() -> new ResourceNotFoundException("DirectoryConnection", directoryId.toString()));
        String baseDn = (changelogBaseDn == null || changelogBaseDn.isBlank())
                ? DEFAULT_BASE_DN : changelogBaseDn.trim();
        try {
            return connectionFactory.withConnectionUnreplicated(dir, iface -> probe(iface, baseDn, start));
        } catch (RuntimeException ex) {
            return new ChangelogTestResult(false,
                    "Changelog test failed: " + ex.getMessage(), elapsedMs(start), null, null);
        }
    }

    private ChangelogTestResult probe(FullLDAPInterface iface, String baseDn, long start) throws LDAPException {
        RootDSE dse = iface.getRootDSE();
        Long head = dse == null ? null : dse.getAttributeValueAsLong("lastChangeNumber");
        Long first = dse == null ? null : dse.getAttributeValueAsLong("firstChangeNumber");
        if (head == null || first == null) {
            return new ChangelogTestResult(false,
                    "Root DSE does not expose firstChangeNumber/lastChangeNumber — these are required "
                            + "for gap and cursor-reset detection; this server cannot be used for changelog capture",
                    elapsedMs(start), head, first);
        }

        SearchResult result;
        try {
            result = iface.search(DSEE.buildSearchRequest(new ChangelogReadContext(baseDn, null, null), 1));
        } catch (LDAPSearchException se) {
            // SIZE_LIMIT_EXCEEDED just means the changelog has more than one
            // entry — it's readable, take the partial result.
            result = se.getSearchResult();
        }
        if (result.getEntryCount() > 0
                && result.getSearchEntries().get(0).getAttributeValue("changeNumber") == null) {
            return new ChangelogTestResult(false,
                    "Changelog entries at " + baseDn + " are missing the changeNumber attribute",
                    elapsedMs(start), head, first);
        }
        return new ChangelogTestResult(true,
                "Changelog reachable at " + baseDn + "; current head changeNumber " + head,
                elapsedMs(start), head, first);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
