// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.changelog;

import com.ldapportal.entity.AuditDataSource;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Strategy for Oracle DSEE / UnboundID-style {@code cn=changelog} entries
 * using {@code objectClass=changeLogEntry} and an integer {@code changeNumber}.
 */
@Slf4j
public class DseeChangelogStrategy implements ChangelogStrategy {

    private static final String[] ATTRIBUTES = {
            "changeNumber", "changeType", "targetDN",
            "changes", "newRDN", "deleteOldRDN", "newSuperior",
            "changeTime", "creatorsName"
    };

    /** GeneralizedTime format used in LDAP changeLog timestamps. */
    static final DateTimeFormatter GENERALIZED_TIME =
            new DateTimeFormatterBuilder()
                    .appendPattern("yyyyMMddHHmmss")
                    .optionalStart()
                    .appendLiteral('.')
                    .appendFraction(ChronoField.MILLI_OF_SECOND, 1, 3, false)
                    .optionalEnd()
                    .appendLiteral('Z')
                    .toFormatter();

    @Override
    public SearchRequest buildSearchRequest(AuditDataSource src, int sizeLimit) throws LDAPException {
        String filter = "(objectClass=changeLogEntry)";
        if (src.getBranchFilterDn() != null && !src.getBranchFilterDn().isBlank()) {
            filter = "(&(objectClass=changeLogEntry)(targetDN=" + src.getBranchFilterDn() + "*))";
        }

        SearchRequest req = new SearchRequest(
                src.getChangelogBaseDn(),
                SearchScope.ONE,
                filter,
                ATTRIBUTES);
        req.setSizeLimit(sizeLimit);
        return req;
    }

    @Override
    public String extractEntryId(SearchResultEntry entry) {
        return entry.getAttributeValue("changeNumber");
    }

    @Override
    public String extractTargetDn(SearchResultEntry entry) {
        return entry.getAttributeValue("targetDN");
    }

    @Override
    public Map<String, Object> extractDetail(SearchResultEntry entry) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("changeType", entry.getAttributeValue("changeType"));
        detail.put("changes", entry.getAttributeValue("changes"));
        detail.put("creatorsName", entry.getAttributeValue("creatorsName"));
        if (entry.getAttributeValue("newRDN") != null) {
            detail.put("newRDN", entry.getAttributeValue("newRDN"));
            detail.put("deleteOldRDN", entry.getAttributeValue("deleteOldRDN"));
            detail.put("newSuperior", entry.getAttributeValue("newSuperior"));
        }
        return detail;
    }

    @Override
    public OffsetDateTime extractOccurredAt(SearchResultEntry entry) {
        return parseGeneralizedTime(entry.getAttributeValue("changeTime"));
    }

    @Override
    public boolean isRecordable(SearchResultEntry entry) {
        return true; // cn=changelog only contains completed write operations
    }

    static OffsetDateTime parseGeneralizedTime(String value) {
        if (value == null || value.isBlank()) {
            return OffsetDateTime.now(ZoneOffset.UTC);
        }
        try {
            return LocalDateTime.parse(value, GENERALIZED_TIME).atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException ex) {
            log.debug("Cannot parse changelog timestamp '{}', using now", value);
            return OffsetDateTime.now(ZoneOffset.UTC);
        }
    }
}
