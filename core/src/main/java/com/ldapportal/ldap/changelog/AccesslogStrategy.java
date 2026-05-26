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
 * Strategy for OpenLDAP's {@code slapo-accesslog} overlay.
 *
 * <p>Accesslog entries live under a configurable suffix (typically
 * {@code cn=accesslog}) and use {@code reqStart} as their unique
 * identifier (a GeneralizedTime value like
 * {@code 20260319143022.000006Z#000001#000#000000}).</p>
 *
 * <p>The search filter restricts results to successful write operations:
 * {@code (&(objectClass=auditWriteObject)(reqResult=0))}.</p>
 */
@Slf4j
public class AccesslogStrategy implements ChangelogStrategy {

    private static final String[] ATTRIBUTES = {
            "reqStart", "reqEnd", "reqType", "reqDN", "reqResult",
            "reqMod", "reqOld", "reqNewRDN", "reqNewSuperior",
            "reqDeleteOldRDN", "reqAuthzID"
    };

    /**
     * GeneralizedTime format for the timestamp portion of {@code reqStart}.
     * The full value may include a serial suffix like
     * {@code #000001#000#000000} which is stripped before parsing.
     * Parses fractional seconds (1-6 digits) and a trailing literal 'Z'.
     */
    private static final DateTimeFormatter GENERALIZED_TIME =
            new DateTimeFormatterBuilder()
                    .appendPattern("yyyyMMddHHmmss")
                    .optionalStart()
                    .appendLiteral('.')
                    .appendFraction(ChronoField.MICRO_OF_SECOND, 1, 6, false)
                    .optionalEnd()
                    .appendLiteral('Z')
                    .toFormatter();

    @Override
    public SearchRequest buildSearchRequest(AuditDataSource src, int sizeLimit) throws LDAPException {
        String filter;
        if (src.getBranchFilterDn() != null && !src.getBranchFilterDn().isBlank()) {
            // Filter to entries whose reqDN ends with the branch filter DN
            filter = "(&(objectClass=auditWriteObject)(reqResult=0)"
                    + "(reqDN=*" + src.getBranchFilterDn() + "))";
        } else {
            filter = "(&(objectClass=auditWriteObject)(reqResult=0))";
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
        return entry.getAttributeValue("reqStart");
    }

    @Override
    public String extractTargetDn(SearchResultEntry entry) {
        return entry.getAttributeValue("reqDN");
    }

    @Override
    public Map<String, Object> extractDetail(SearchResultEntry entry) {
        Map<String, Object> detail = new LinkedHashMap<>();

        String reqType = entry.getAttributeValue("reqType");
        detail.put("changeType", mapReqType(reqType));

        // reqMod is multi-valued — each value describes one modification
        String[] reqMods = entry.getAttributeValues("reqMod");
        if (reqMods != null && reqMods.length > 0) {
            detail.put("changes", String.join("\n", reqMods));
        }

        // reqOld holds previous attribute values (if logold is configured)
        String[] reqOld = entry.getAttributeValues("reqOld");
        if (reqOld != null && reqOld.length > 0) {
            detail.put("reqOld", String.join("\n", reqOld));
        }

        // Extract actor from reqAuthzID (format: "dn:cn=admin,dc=example,dc=com")
        String reqAuthzID = entry.getAttributeValue("reqAuthzID");
        if (reqAuthzID != null) {
            detail.put("creatorsName", stripDnPrefix(reqAuthzID));
        }

        // ModRDN-specific attributes
        if (entry.getAttributeValue("reqNewRDN") != null) {
            detail.put("newRDN", entry.getAttributeValue("reqNewRDN"));
            detail.put("deleteOldRDN", entry.getAttributeValue("reqDeleteOldRDN"));
            detail.put("newSuperior", entry.getAttributeValue("reqNewSuperior"));
        }

        return detail;
    }

    @Override
    public OffsetDateTime extractOccurredAt(SearchResultEntry entry) {
        String reqStart = entry.getAttributeValue("reqStart");
        return parseReqStart(reqStart);
    }

    @Override
    public boolean isRecordable(SearchResultEntry entry) {
        return true; // search filter already restricts to successful write ops
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Map OpenLDAP {@code reqType} values to the conventional changelog
     * {@code changeType} names used by the DSEE format.
     */
    private static String mapReqType(String reqType) {
        if (reqType == null) return null;
        return switch (reqType.toLowerCase()) {
            case "add"    -> "add";
            case "modify" -> "modify";
            case "delete" -> "delete";
            case "modrdn" -> "modrdn";
            default       -> reqType.toLowerCase();
        };
    }

    /**
     * Strip the {@code "dn:"} prefix from an {@code reqAuthzID} value.
     * E.g. {@code "dn:cn=admin,dc=example,dc=com"} → {@code "cn=admin,dc=example,dc=com"}.
     */
    private static String stripDnPrefix(String authzId) {
        if (authzId.regionMatches(true, 0, "dn:", 0, 3)) {
            return authzId.substring(3).trim();
        }
        return authzId;
    }

    /**
     * Parse the timestamp portion of a {@code reqStart} value.
     * Full format: {@code 20260319143022.000006Z#000001#000#000000}.
     * We strip everything after the first {@code #}.
     */
    static OffsetDateTime parseReqStart(String value) {
        if (value == null || value.isBlank()) {
            return OffsetDateTime.now(ZoneOffset.UTC);
        }
        try {
            // Strip serial suffix (everything from first '#')
            int hashIdx = value.indexOf('#');
            String timestamp = hashIdx > 0 ? value.substring(0, hashIdx) : value;
            // Parse as LocalDateTime (the 'Z' is a literal, not offset) and assume UTC
            return LocalDateTime.parse(timestamp, GENERALIZED_TIME).atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException ex) {
            log.debug("Cannot parse accesslog timestamp '{}', using now", value);
            return OffsetDateTime.now(ZoneOffset.UTC);
        }
    }
}
