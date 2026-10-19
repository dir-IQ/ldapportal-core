// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.changelog;

import com.unboundid.asn1.ASN1OctetString;
import com.unboundid.ldap.sdk.Control;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;

/**
 * Strategy for the Oracle Unified Directory / OpenDJ external changelog read in
 * <em>cookie mode</em>.
 *
 * <p>The search carries the External Changelog Request Control with the last
 * {@code changeLogCookie} the caller processed; the server returns the changes
 * after it. Each returned entry carries its own {@code changeLogCookie} — the
 * position just after that change — so resuming means persisting the cookie of
 * the last entry handled. The cookie is a per-replicated-domain set of
 * replication CSNs (one per originating replica), which every server in the
 * topology can resolve; unlike the draft-compat integer {@code changeNumber}
 * (assigned locally by each server's indexer) it survives failover and
 * load-balanced source nodes.
 *
 * <p>Entry DNs are {@code replicationCSN=<csn>,<domain>,cn=changelog} — two
 * levels below the changelog root — so the search is subtree-scoped. The
 * {@code targetDN} / {@code changeType} / {@code newRDN} / {@code newSuperior}
 * attributes match the DSEE format, so DN and rename extraction are inherited.
 *
 * <p>A cookie older than the server's changelog purge window (or naming a
 * domain the server no longer knows) is refused with
 * {@code UNWILLING_TO_PERFORM} ("Full resync required"); the current head is the
 * Root DSE's {@code lastExternalChangelogCookie}.
 */
public class OudCookieChangelogStrategy extends DseeChangelogStrategy {

    /** External Changelog Request Control (cookie exchange). */
    public static final String ECL_COOKIE_CONTROL_OID = "1.3.6.1.4.1.26027.1.5.4";

    /** Root DSE attribute holding the newest cookie across all replicated domains. */
    public static final String ROOT_DSE_LAST_COOKIE = "lastExternalChangelogCookie";

    public static final String COOKIE_ATTR = "changeLogCookie";

    private static final String[] ATTRIBUTES = {
            "changeType", "targetDN", "newRDN", "deleteOldRDN", "newSuperior",
            "changeTime", "changeInitiatorsName", "replicationCSN", "targetEntryUUID",
            COOKIE_ATTR
    };

    /**
     * Incremental cookie-mode search: the changes after {@code cookie}
     * ({@code null}/blank = from the start of the retained changelog). The
     * control is critical so a server without cookie-mode support fails the
     * search instead of silently answering in change-number mode (whose entries
     * carry no cookie to advance by).
     */
    public SearchRequest buildCookieSearchRequest(String changelogBaseDn, String cookie, int sizeLimit)
            throws LDAPException {
        SearchRequest req = new SearchRequest(changelogBaseDn, SearchScope.SUB, "(objectClass=*)", ATTRIBUTES);
        req.setSizeLimit(sizeLimit);
        req.addControl(cookieControl(cookie));
        return req;
    }

    /**
     * Audit-reader SPI path: cookie mode has no {@code changeNumber} cursor, so
     * this reads from the start of the retained changelog. Branch filtering is
     * left to the caller's scope checks.
     */
    @Override
    public SearchRequest buildSearchRequest(ChangelogReadContext ctx, int sizeLimit) throws LDAPException {
        return buildCookieSearchRequest(ctx.changelogBaseDn(), null, sizeLimit);
    }

    /** The cookie positioned just after this change — the resume point. */
    @Override
    public String extractEntryId(SearchResultEntry entry) {
        String cookie = entry.getAttributeValue(COOKIE_ATTR);
        return cookie == null || cookie.isBlank() ? null : cookie;
    }

    /**
     * The control value is the cookie as a BER OCTET STRING (the encoding the
     * OpenDJ/OUD lineage writes; its decoder also accepts the raw string).
     */
    static Control cookieControl(String cookie) {
        String value = cookie == null ? "" : cookie;
        return new Control(ECL_COOKIE_CONTROL_OID, true,
                new ASN1OctetString(new ASN1OctetString(value).encode()));
    }
}
