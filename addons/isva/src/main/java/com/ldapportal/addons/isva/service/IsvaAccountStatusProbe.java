// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.service;

import com.ldapportal.addons.isva.IsvaLinkedUserLookup;
import com.ldapportal.addons.isva.dto.IsvaAccountStatus;
import com.ldapportal.addons.isva.entity.IsvaTopologyMode;
import com.ldapportal.addons.isva.entity.VendorIntegrationIsvaConfig;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.exception.LdapOperationException;
import com.ldapportal.exception.ResourceNotFoundException;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchResultEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Topology-aware existence + lifecycle probe for an identity's IVIA
 * account. The {@link IsvaLinkedUserLookup} alone only finds linked
 * pairs; in inline mode the {@code secUser} objectClass lives on the
 * demographic entry itself, so a lookup-only check would mis-classify
 * an already-granted inline entry as orphaned and let a redundant
 * grant trip {@code ATTRIBUTE_OR_VALUE_EXISTS} at the LDAP layer
 * (surfacing as 500). This probe routes per topology so the verb
 * can refuse with a clean 409 instead.
 *
 * <p>{@link #probe} always returns a snapshot; for an orphaned
 * identity every IVIA-side field is null/false. A truly missing
 * demographic DN (the URL points at no entry at all) escalates to
 * {@link ResourceNotFoundException} — that's a 404, not an
 * orphan-409.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IsvaAccountStatusProbe {

    private final IsvaLinkedUserLookup linkedUserLookup;
    private final LdapConnectionFactory connectionFactory;

    /** Attributes pulled off the demographic entry. {@code objectClass}
     * is what tells inline-mode "is secUser overlaid?"; the {@code sec*}
     * subset is reused as the inline-mode lifecycle snapshot. */
    private static final String[] DEMOGRAPHIC_ATTRS = {
            "objectClass",
            "secAcctValid", "secPwdValid", "secValidUntil",
            "secPwdLastChanged", "secAuthority"
    };

    /** Attributes pulled off the paired secUser entry in linked mode. */
    private static final String[] LINKED_SECUSER_ATTRS = {
            "secAcctValid", "secPwdValid", "secValidUntil",
            "secPwdLastChanged", "secAuthority"
    };

    private static final DateTimeFormatter GENERALIZED_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'").withZone(ZoneOffset.UTC);

    public IsvaAccountStatus probe(DirectoryConnection dir,
                                   VendorIntegrationIsvaConfig cfg,
                                   String demographicDn) {
        SearchResultEntry demographic = fetchByDn(dir, demographicDn, DEMOGRAPHIC_ATTRS);
        if (demographic == null) {
            throw new ResourceNotFoundException(
                    "Identity not found: " + demographicDn);
        }
        return switch (cfg.getTopologyMode()) {
            case INLINE -> probeInline(demographic);
            case LINKED -> probeLinked(dir, cfg, demographic);
        };
    }

    private IsvaAccountStatus probeInline(SearchResultEntry demographic) {
        if (!hasObjectClass(demographic, "secUser")) {
            return IsvaAccountStatus.orphaned(IsvaTopologyMode.INLINE);
        }
        return IsvaAccountStatus.present(
                IsvaTopologyMode.INLINE,
                null,
                parseBoolean(demographic.getAttributeValue("secAcctValid")),
                parseGeneralizedTime(demographic.getAttributeValue("secValidUntil")),
                parseBoolean(demographic.getAttributeValue("secPwdValid")),
                parseGeneralizedTime(demographic.getAttributeValue("secPwdLastChanged")),
                demographic.getAttributeValue("secAuthority"));
    }

    private IsvaAccountStatus probeLinked(DirectoryConnection dir,
                                          VendorIntegrationIsvaConfig cfg,
                                          SearchResultEntry demographic) {
        Optional<String> secUserDn = linkedUserLookup.findSecUserDn(
                dir, cfg.getManagementDitBaseDn(), demographic.getDN());
        if (secUserDn.isEmpty()) {
            return IsvaAccountStatus.orphaned(IsvaTopologyMode.LINKED);
        }
        SearchResultEntry secUser = fetchByDn(dir, secUserDn.get(), LINKED_SECUSER_ATTRS);
        if (secUser == null) {
            // The lookup found a pointer, but base-fetching the target said
            // "gone" — TOCTOU race with another operator. Treat as orphan;
            // the next verb will compute its guards against this snapshot
            // and refuse cleanly.
            log.warn("Linked secUser DN {} found via lookup but missing on base-fetch — "
                    + "treating as orphaned for probe purposes", secUserDn.get());
            return IsvaAccountStatus.orphaned(IsvaTopologyMode.LINKED);
        }
        return IsvaAccountStatus.present(
                IsvaTopologyMode.LINKED,
                secUser.getDN(),
                parseBoolean(secUser.getAttributeValue("secAcctValid")),
                parseGeneralizedTime(secUser.getAttributeValue("secValidUntil")),
                parseBoolean(secUser.getAttributeValue("secPwdValid")),
                parseGeneralizedTime(secUser.getAttributeValue("secPwdLastChanged")),
                secUser.getAttributeValue("secAuthority"));
    }

    /**
     * Looks up the {@code uid} attribute on the demographic entry —
     * needed by {@code IsvaSecUserPlans} grant fragments which key
     * {@code secLogin} off the uid. Returned as an empty string when
     * the entry has no uid, matching the existing convention in
     * {@code IsvaSecUserPlans.firstValueOrEmpty}.
     */
    public String resolveUid(DirectoryConnection dir, String demographicDn) {
        SearchResultEntry entry = fetchByDn(dir, demographicDn, new String[]{"uid"});
        if (entry == null) {
            throw new ResourceNotFoundException(
                    "Identity not found: " + demographicDn);
        }
        String uid = entry.getAttributeValue("uid");
        return uid == null ? "" : uid;
    }

    // ── helpers ──────────────────────────────────────────────────────

    /**
     * Base-scope fetch of a specific DN. Returns null when the entry
     * doesn't exist ({@code NO_SUCH_OBJECT}); rethrows other LDAP
     * failures as {@link LdapOperationException} so the executor's
     * usual error path applies.
     */
    private SearchResultEntry fetchByDn(DirectoryConnection dir,
                                        String dn,
                                        String[] attrs) {
        return connectionFactory.withConnection(dir, conn -> {
            try {
                return conn.getEntry(dn, attrs);
            } catch (LDAPException e) {
                if (e.getResultCode() == ResultCode.NO_SUCH_OBJECT) {
                    return null;
                }
                throw new LdapOperationException(
                        "Failed to fetch entry " + dn + ": " + e.getMessage(), e);
            }
        });
    }

    private static boolean hasObjectClass(SearchResultEntry entry, String objectClass) {
        String[] values = entry.getAttributeValues("objectClass");
        if (values == null) {
            return false;
        }
        for (String v : values) {
            if (objectClass.equalsIgnoreCase(v)) {
                return true;
            }
        }
        return false;
    }

    private static boolean parseBoolean(String s) {
        return "TRUE".equalsIgnoreCase(s);
    }

    /**
     * Parse LDAP generalized-time as written by
     * {@link com.ldapportal.addons.isva.IsvaSecUserPlans#generalizedTime}
     * ({@code yyyyMMddHHmmss'Z'}, UTC). Lenient: returns null on
     * blank or unparseable input rather than throwing — a malformed
     * timestamp shouldn't 500 the status read.
     */
    private static OffsetDateTime parseGeneralizedTime(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return java.time.LocalDateTime.parse(s, GENERALIZED_TIME)
                    .atOffset(ZoneOffset.UTC);
        } catch (java.time.format.DateTimeParseException e) {
            log.warn("Unparseable generalized-time value [{}] — surfacing as null", s);
            return null;
        }
    }
}
