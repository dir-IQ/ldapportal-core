// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.changelog;

import com.ldapportal.entity.AuditDataSource;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import lombok.extern.slf4j.Slf4j;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Strategy for Active Directory's DirSync control (OID 1.2.840.113556.1.4.417).
 *
 * <p>DirSync returns entries that have changed since the last cookie. Unlike
 * changelog-based strategies, the search is against the live directory tree
 * (not a separate changelog container). The cookie must be persisted between
 * polls via {@link AuditDataSource#getDirsyncCookie()}.</p>
 *
 * <p>Note: The DirSync control itself is handled by the caller
 * ({@code LdapChangelogReader}). This strategy provides the search request
 * template and entry parsing logic.</p>
 */
@Slf4j
public class DirSyncChangelogStrategy implements ChangelogStrategy {

    private static final String[] ATTRIBUTES = {
            "distinguishedName", "objectClass", "whenChanged",
            "isDeleted", "name", "sAMAccountName"
    };

    @Override
    public SearchRequest buildSearchRequest(AuditDataSource src, int sizeLimit) throws LDAPException {
        // DirSync searches against the directory root, not a changelog container
        String baseDn = src.getChangelogBaseDn() != null ? src.getChangelogBaseDn() : "";
        SearchRequest req = new SearchRequest(
                baseDn,
                SearchScope.SUB,
                "(objectClass=*)",
                ATTRIBUTES);
        // DirSync control will be added by the caller
        return req;
    }

    @Override
    public String extractEntryId(SearchResultEntry entry) {
        // Use the DN + whenChanged as a pseudo-unique key
        String whenChanged = entry.getAttributeValue("whenChanged");
        return entry.getDN() + "@" + (whenChanged != null ? whenChanged : "unknown");
    }

    @Override
    public String extractTargetDn(SearchResultEntry entry) {
        return entry.getDN();
    }

    @Override
    public Map<String, Object> extractDetail(SearchResultEntry entry) {
        Map<String, Object> detail = new LinkedHashMap<>();

        String isDeleted = entry.getAttributeValue("isDeleted");
        if ("TRUE".equalsIgnoreCase(isDeleted)) {
            detail.put("changeType", "delete");
        } else {
            detail.put("changeType", "modify");
        }

        String name = entry.getAttributeValue("name");
        if (name != null) detail.put("name", name);

        String sam = entry.getAttributeValue("sAMAccountName");
        if (sam != null) detail.put("sAMAccountName", sam);

        String[] objectClasses = entry.getAttributeValues("objectClass");
        if (objectClasses != null) {
            detail.put("objectClass", String.join(", ", objectClasses));
        }

        return detail;
    }

    @Override
    public OffsetDateTime extractOccurredAt(SearchResultEntry entry) {
        String whenChanged = entry.getAttributeValue("whenChanged");
        if (whenChanged == null) return OffsetDateTime.now(ZoneOffset.UTC);
        try {
            // AD GeneralizedTime: 20250101120000.0Z
            return DseeChangelogStrategy.GENERALIZED_TIME.parse(whenChanged,
                    java.time.LocalDateTime::from).atOffset(ZoneOffset.UTC);
        } catch (Exception e) {
            log.debug("Failed to parse whenChanged '{}': {}", whenChanged, e.getMessage());
            return OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

    @Override
    public boolean isRecordable(SearchResultEntry entry) {
        // All DirSync entries represent changes — always record
        return true;
    }
}
