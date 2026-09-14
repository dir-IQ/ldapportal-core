// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.exception.LdapOperationException;
import com.ldapportal.ldap.annotation.LdapWriteAuthorized;
import com.ldapportal.ldap.validation.DnValidator;
import com.unboundid.asn1.ASN1OctetString;
import com.unboundid.ldap.sdk.*;
import com.unboundid.ldap.sdk.controls.SimplePagedResultsControl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Browses the LDAP Directory Information Tree (DIT) using one-level searches.
 *
 * <p>Designed for the superadmin directory browser — returns direct children of
 * a given DN and determines whether each child has sub-entries of its own
 * (so the UI can show expand/collapse arrows).</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
@LdapWriteAuthorized("Superadmin directory-browser create/modify/delete/move/rename writes.")
public class LdapBrowseService {

    private final LdapConnectionFactory connectionFactory;

    /**
     * Hard ceiling on the number of children a single browse returns, even
     * when the caller asks for "all". Bounds the worst case of a load-all on
     * the root of a very large directory; the response says so via
     * {@link BrowseResult#truncated()} so the UI can explain.
     */
    public static final int MAX_CHILDREN = 50_000;

    /**
     * Attributes a plain-text branch filter is matched against (substring,
     * case-insensitive per the server's matching rule). Covers the naming
     * attributes used for RDNs across OpenLDAP, AD and IBM directories plus
     * the common "who is this" attributes. Servers ignore names they don't
     * have, and a substring filter on an attribute without a substring rule
     * evaluates to undefined (never a match), so the list is safe everywhere.
     */
    static final List<String> QUICK_FILTER_ATTRS = List.of(
            "cn", "uid", "sAMAccountName", "ou", "o", "dc", "l", "c",
            "mail", "sn", "givenName", "displayName");

    /**
     * Fetches the entry at {@code dn} together with all of its direct
     * children (subject to {@link #MAX_CHILDREN}).
     *
     * @param dc   directory connection
     * @param dn   the base DN to browse (null falls back to directory base DN)
     * @return browse result containing the entry's attributes and child list
     */
    public BrowseResult browse(DirectoryConnection dc, String dn) {
        return browse(dc, dn, null, 0);
    }

    /**
     * Fetches the entry at {@code dn} together with a bounded, optionally
     * filtered page of its direct children.
     *
     * @param dc     directory connection
     * @param dn     the base DN to browse (null falls back to directory base DN)
     * @param filter optional child filter: a raw LDAP filter when it starts
     *               with {@code (}, otherwise plain text matched as a
     *               substring against {@link #QUICK_FILTER_ATTRS}. Blank
     *               means no filter.
     * @param limit  maximum children to return; {@code <= 0} means all
     *               (still capped at {@link #MAX_CHILDREN})
     * @return browse result; {@link BrowseResult#truncated()} is set when
     *         more children matched than were returned
     */
    public BrowseResult browse(DirectoryConnection dc, String dn, String filter, int limit) {
        if (dc.getDirectoryType() == DirectoryType.ENTRA_ID) {
            throw new IllegalArgumentException("This feature is not supported for Entra ID directories");
        }
        String baseDn = (dn != null && !dn.isBlank()) ? dn : dc.getBaseDn();
        Filter childFilter = buildChildFilter(filter);
        int effectiveLimit = (limit <= 0) ? MAX_CHILDREN : Math.min(limit, MAX_CHILDREN);

        return connectionFactory.withConnection(dc, conn -> {
            // 1. Read the entry itself
            Map<String, List<String>> attributes = readEntry(conn, baseDn);

            // 2. One-level search to find direct children
            ChildListing listing = listChildren(conn, dc, baseDn, childFilter, effectiveLimit);

            // 3. Child count. The server-maintained count on the parent is
            //    the cheap, always-available answer; an untruncated,
            //    unfiltered listing is an exact answer that beats an
            //    estimate. A filtered listing says nothing about the total.
            SubordinateCount serverCount = subordinateCount(attributes);
            Integer childCount = serverCount != null ? serverCount.count() : null;
            boolean approximate = serverCount != null && serverCount.approximate();
            boolean filtered = childFilter != null;
            if (!filtered && !listing.truncated() && (childCount == null || approximate)) {
                childCount = listing.children().size();
                approximate = false;
            }

            return new BrowseResult(baseDn, attributes, listing.children(),
                    listing.truncated(), childCount, approximate);
        });
    }

    /**
     * Turns the user's branch filter into an LDAP filter. {@code null} when
     * there is nothing to filter by (the caller then lists every child).
     *
     * @throws IllegalArgumentException when a raw filter fails to parse — the
     *         controller maps it to a 400
     */
    static Filter buildChildFilter(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.trim();
        if (t.startsWith("(")) {
            try {
                return Filter.create(t);
            } catch (LDAPException e) {
                throw new IllegalArgumentException("Invalid LDAP filter: " + e.getMessage(), e);
            }
        }
        // createSubstringFilter escapes the value, so user text like "a*b"
        // or "(x" is matched literally rather than parsed.
        List<Filter> ors = new ArrayList<>(QUICK_FILTER_ATTRS.size());
        for (String attr : QUICK_FILTER_ATTRS) {
            ors.add(Filter.createSubstringFilter(attr, null, new String[]{t}, null));
        }
        return Filter.createORFilter(ors);
    }

    private record SubordinateCount(int count, boolean approximate) {}

    /** Reads the parent's own subordinate count from its operational attributes, if the server keeps one. */
    private static SubordinateCount subordinateCount(Map<String, List<String>> attributes) {
        Integer exact = firstInt(attributes, ATTR_NUM_SUBORDINATES);
        if (exact != null) {
            return new SubordinateCount(exact, false);
        }
        Integer approx = firstInt(attributes, ATTR_AD_APPROX_SUBORDINATES);
        if (approx != null) {
            return new SubordinateCount(approx, true);
        }
        return null;
    }

    private static Integer firstInt(Map<String, List<String>> attributes, String name) {
        for (var e : attributes.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                try {
                    return Integer.parseInt(e.getValue().get(0).trim());
                } catch (NumberFormatException ex) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Returns {@code true} iff an entry exists at {@code dn}. Uses a base-scope
     * search requesting no attributes ({@code "1.1"}), so it's a single
     * round-trip with the smallest possible payload — cheaper than the full
     * {@link #browse(DirectoryConnection, String)} call when callers only need
     * a yes/no answer (e.g. validating a parent DN before bulk import).
     *
     * <p>Treats both {@code NO_SUCH_OBJECT} (syntactically-valid DN, no entry)
     * and {@code INVALID_DN_SYNTAX} (the string isn't a valid DN at all) as
     * "no entry" — the observable outcome from the caller's perspective is
     * identical, and treating malformed input as a connection-level failure
     * (prior behaviour) surfaced an unhelpful 502 instead of letting the
     * caller report a friendly "this OU doesn't exist" error. Connection
     * failures and insufficient-access errors still bubble through.</p>
     */
    public boolean entryExists(DirectoryConnection dc, String dn) {
        if (dn == null || dn.isBlank()) {
            return false;
        }
        return connectionFactory.withConnection(dc, conn -> {
            try {
                return conn.getEntry(dn, "1.1") != null;
            } catch (LDAPException e) {
                ResultCode rc = e.getResultCode();
                if (rc == ResultCode.NO_SUCH_OBJECT
                        || rc == ResultCode.INVALID_DN_SYNTAX) {
                    return false;
                }
                throw e;
            }
        });
    }

    private Map<String, List<String>> readEntry(LDAPInterface conn, String dn)
            throws LDAPException {
        // Request user attributes ("*") AND operational attributes ("+", RFC
        // 4511). Operational attributes aren't returned by a default search, so
        // without "+" the entry view omits server-maintained values such as
        // OUD/OpenDJ's isMemberOf (reverse group membership), createTimestamp,
        // modifyTimestamp, and entryUUID. The browser shows the full picture.
        // The subordinate hints are named explicitly as well: AD doesn't
        // honour "+" and only returns constructed attributes such as
        // msDS-Approx-Immed-Subordinates when asked for by name.
        SearchResultEntry entry = conn.getEntry(dn, "*", "+",
                ATTR_HAS_SUBORDINATES, ATTR_NUM_SUBORDINATES, ATTR_AD_APPROX_SUBORDINATES);
        if (entry == null) {
            return Map.of();
        }
        Map<String, List<String>> attrs = new LinkedHashMap<>();
        for (var attr : entry.getAttributes()) {
            attrs.put(attr.getBaseName(), Arrays.asList(attr.getValues()));
        }
        return attrs;
    }

    /**
     * Operational attributes a server may maintain on each entry that tell us
     * whether it has children without a follow-up search per child. Which one
     * is present depends on the vendor:
     * <ul>
     *   <li>{@code hasSubordinates} — OpenLDAP (back-mdb), 389 DS, IBM SDS,
     *       OUD/OpenDJ. Boolean, exact.</li>
     *   <li>{@code numSubordinates} — 389 DS, IBM SDS, OUD/OpenDJ. Exact
     *       count of immediate children.</li>
     *   <li>{@code msDS-Approx-Immed-Subordinates} — Active Directory.
     *       Constructed on read; an estimate from index statistics. Apache
     *       Directory Studio relies on it for the same expand-arrow hint, so
     *       we follow that precedent rather than probing every AD child.</li>
     * </ul>
     * Operational attributes are only returned when named explicitly, and
     * servers silently ignore names they don't know, so requesting all three
     * is safe everywhere. When none comes back the caller falls back to the
     * one-level probe.
     */
    static final String ATTR_HAS_SUBORDINATES    = "hasSubordinates";
    static final String ATTR_NUM_SUBORDINATES    = "numSubordinates";
    static final String ATTR_AD_APPROX_SUBORDINATES = "msDS-Approx-Immed-Subordinates";

    private static final String[] CHILD_LISTING_ATTRS = {
            ATTR_HAS_SUBORDINATES, ATTR_NUM_SUBORDINATES, ATTR_AD_APPROX_SUBORDINATES
    };

    /**
     * Every direct child of {@code baseDn}, unfiltered and unbounded. Used by
     * the recursive delete paths, which must see the whole branch — a cap
     * there would leave entries behind and make the parent delete fail.
     */
    private List<ChildEntry> listChildren(LDAPInterface conn,
                                           DirectoryConnection dc,
                                           String baseDn) throws LDAPException {
        return listChildren(conn, dc, baseDn, null, Integer.MAX_VALUE).children();
    }

    /** A page of children plus whether more were available. */
    record ChildListing(List<ChildEntry> children, boolean truncated) {}

    private ChildListing listChildren(LDAPInterface conn,
                                      DirectoryConnection dc,
                                      String baseDn,
                                      Filter childFilter,
                                      int limit) throws LDAPException {
        List<ChildEntry> children = new ArrayList<>();
        boolean truncated = false;
        Filter filter = childFilter != null ? childFilter : Filter.createPresenceFilter("objectClass");

        try {
            ASN1OctetString cookie = null;
            do {
                // Ask only for the subordinate hints (operational attributes,
                // so no user attributes come back). Before this, every child
                // cost a second one-level search just to decide whether to
                // draw the expand arrow — expanding a branch of N entries was
                // N+1 round-trips. With the hints in the listing itself the
                // probe only runs for servers that don't maintain any of them
                // (see hasChildrenHint).
                SearchRequest request = new SearchRequest(
                        baseDn, SearchScope.ONE, filter, CHILD_LISTING_ATTRS);
                // Page size never exceeds what we still need plus one: the
                // extra entry is how we learn there was more without paying
                // for a whole further page.
                int remaining = limit - children.size();
                int pageSize = remaining >= dc.getPagingSize() ? dc.getPagingSize() : remaining + 1;
                request.addControl(new SimplePagedResultsControl(pageSize, cookie));

                SearchResult result = conn.search(request);
                for (SearchResultEntry child : result.getSearchEntries()) {
                    if (children.size() >= limit) {
                        truncated = true;
                        break;
                    }
                    String childDn = child.getDN();
                    String rdn = extractRdn(childDn, baseDn);
                    Boolean hint = hasChildrenHint(child);
                    boolean hasChildren = hint != null ? hint : hasSubEntries(conn, childDn);
                    children.add(new ChildEntry(childDn, rdn, hasChildren));
                }

                SimplePagedResultsControl pageResponse =
                        SimplePagedResultsControl.get(result);
                cookie = (pageResponse != null && pageResponse.moreResultsToReturn())
                        ? pageResponse.getCookie() : null;
                if (truncated && cookie != null && cookie.getValue().length > 0) {
                    abandonPaging(conn, baseDn, filter, cookie);
                    cookie = null;
                }
            } while (cookie != null && cookie.getValue().length > 0);
        } catch (LDAPSearchException e) {
            if (e.getResultCode() == ResultCode.NO_SUCH_OBJECT) {
                log.debug("Base '{}' does not exist — returning empty children", baseDn);
                return new ChildListing(children, false);
            }
            throw e;
        }

        children.sort(Comparator.comparing(ChildEntry::rdn, String.CASE_INSENSITIVE_ORDER));
        return new ChildListing(children, truncated);
    }

    /**
     * Tells the server we won't be asking for the remaining pages. RFC 2696
     * §3: a request with the cookie and a page size of zero lets the server
     * release the paging state instead of holding it until the connection
     * closes. Best-effort — a server that rejects it costs us nothing.
     */
    private void abandonPaging(LDAPInterface conn, String baseDn, Filter filter,
                               ASN1OctetString cookie) {
        abandonPaging(conn, baseDn, SearchScope.ONE, filter, cookie);
    }

    private void abandonPaging(LDAPInterface conn, String baseDn, SearchScope scope,
                               Filter filter, ASN1OctetString cookie) {
        try {
            SearchRequest abandon = new SearchRequest(
                    baseDn, scope, filter, "1.1");
            abandon.addControl(new SimplePagedResultsControl(0, cookie));
            conn.search(abandon);
        } catch (LDAPException e) {
            log.debug("Ignoring failure to release paged-results state under {}: {}",
                    baseDn, e.getResultCode());
        }
    }

    /**
     * Derives the has-children hint from the subordinate attributes on a
     * listed entry, or {@code null} when the server returned none of them
     * (meaning the caller must probe). Package-private for unit testing.
     *
     * <p>Precedence: the exact boolean first, then the exact count, then
     * AD's estimate. A count that fails to parse is treated as absent
     * rather than as zero, so a malformed value can never hide a subtree.</p>
     */
    static Boolean hasChildrenHint(Entry entry) {
        String has = entry.getAttributeValue(ATTR_HAS_SUBORDINATES);
        if (has != null) {
            return "TRUE".equalsIgnoreCase(has.trim());
        }
        for (String countAttr : new String[]{ATTR_NUM_SUBORDINATES, ATTR_AD_APPROX_SUBORDINATES}) {
            String raw = entry.getAttributeValue(countAttr);
            if (raw == null) {
                continue;
            }
            try {
                return Long.parseLong(raw.trim()) > 0;
            } catch (NumberFormatException e) {
                log.debug("Ignoring unparseable {}='{}' on {}", countAttr, raw, entry.getDN());
            }
        }
        return null;
    }

    private boolean hasSubEntries(LDAPInterface conn, String dn) {
        try {
            SearchRequest probe = new SearchRequest(
                    dn, SearchScope.ONE,
                    Filter.createPresenceFilter("objectClass"),
                    "1.1"); // no attributes — just check existence
            probe.setSizeLimit(1);
            SearchResult result = conn.search(probe);
            return !result.getSearchEntries().isEmpty();
        } catch (LDAPException e) {
            // SIZE_LIMIT_EXCEEDED means at least one entry exists;
            // for any other error, assume it might have children
            if (e instanceof LDAPSearchException se) {
                return se.getResultCode() == ResultCode.SIZE_LIMIT_EXCEEDED
                        || se.getEntryCount() > 0;
            }
            return true;
        }
    }

    /**
     * Creates a missing parent container at {@code dn}. Used by bulk-import
     * flows when the caller-supplied parent DN doesn't exist yet — instead
     * of returning N {@code NO_SUCH_OBJECT} errors (one per CSV row), the
     * caller can offer to create the missing container first.
     *
     * <p>The objectClass is inferred from the leftmost RDN attribute:
     * {@code ou=…} → {@code organizationalUnit},
     * {@code o=…} → {@code organization}.
     * Anything else (including {@code cn=…}) defaults to
     * {@code organizationalUnit}, which most directory servers accept;
     * if your server rejects that combination, fall back to creating the
     * container by hand or via the schema-aware entry-create UI.</p>
     *
     * <p>The container's parent must already exist — we create one missing
     * level, not a chain. If the parent of {@code dn} is also missing
     * the underlying {@code add} returns {@code NO_SUCH_OBJECT} which is
     * surfaced as a {@link LdapOperationException} with the original
     * diagnostic message.</p>
     */
    public void createContainer(DirectoryConnection dc, String dn) {
        if (dc.getDirectoryType() == DirectoryType.ENTRA_ID) {
            throw new IllegalArgumentException("Container creation is not supported for Entra ID directories");
        }
        DN parsed = DnValidator.parse(dn);
        RDN rdn = parsed.getRDN();
        String[] rdnAttrs  = rdn.getAttributeNames();
        String[] rdnValues = rdn.getAttributeValues();
        if (rdnAttrs.length == 0) {
            throw new IllegalArgumentException("RDN has no attribute: " + dn);
        }
        String rdnAttr = rdnAttrs[0];
        String objectClass = switch (rdnAttr.toLowerCase(Locale.ROOT)) {
            case "o"  -> "organization";
            case "ou", "cn" -> "organizationalUnit";
            default   -> "organizationalUnit";
        };

        Map<String, List<String>> attrs = new LinkedHashMap<>();
        attrs.put("objectClass", List.of("top", objectClass));
        attrs.put(rdnAttr, List.of(rdnValues[0]));
        createEntry(dc, dn, attrs);
        log.info("Created container {} (objectClass={})", dn, objectClass);
    }

    /**
     * Creates a new LDAP entry with the given DN and attributes.
     */
    public void createEntry(DirectoryConnection dc, String dn,
                            Map<String, List<String>> attributes) {
        List<Attribute> ldapAttrs = new ArrayList<>();
        attributes.forEach((name, values) ->
            ldapAttrs.add(new Attribute(name, values.toArray(new String[0]))));

        connectionFactory.withConnection(dc, conn -> {
            LDAPResult result = conn.add(new AddRequest(dn, ldapAttrs));
            if (result.getResultCode() != ResultCode.SUCCESS) {
                throw new LdapOperationException(
                    "createEntry failed for [" + dn + "]: "
                    + result.getResultCode() + " — " + result.getDiagnosticMessage());
            }
            log.info("Created LDAP entry {}", dn);
            return null;
        });
    }

    /**
     * Updates an existing LDAP entry by applying the given attribute modifications.
     */
    public void updateEntry(DirectoryConnection dc, String dn,
                            List<Modification> modifications) {
        connectionFactory.withConnection(dc, conn -> {
            LDAPResult result = conn.modify(new ModifyRequest(dn, modifications));
            if (result.getResultCode() != ResultCode.SUCCESS) {
                throw new LdapOperationException(
                    "updateEntry failed for [" + dn + "]: "
                    + result.getResultCode() + " — " + result.getDiagnosticMessage());
            }
            log.info("Updated LDAP entry {}", dn);
            return null;
        });
    }

    /**
     * Deletes an LDAP entry.  When {@code recursive} is true, all descendant
     * entries are deleted bottom-up first (OpenLDAP rejects delete on non-leaf).
     */
    public void deleteEntry(DirectoryConnection dc, String dn, boolean recursive) {
        deleteEntry(dc, dn, recursive, false);
    }

    /**
     * Deletes an LDAP entry, or — when {@code childrenOnly} is true — deletes
     * every descendant of the entry while keeping the entry itself (e.g. to
     * empty a container without removing it). In children-only mode the delete
     * is inherently recursive over the descendants, so {@code recursive} is
     * ignored.
     */
    public void deleteEntry(DirectoryConnection dc, String dn, boolean recursive, boolean childrenOnly) {
        connectionFactory.withConnection(dc, conn -> {
            if (childrenOnly) {
                int n = deleteChildren(conn, dc, dn);
                log.info("Deleted {} child entr{} under {} (entry kept)", n, n == 1 ? "y" : "ies", dn);
            } else if (recursive) {
                deleteSubtree(conn, dc, dn);
                log.info("Deleted LDAP entry {} (recursive)", dn);
            } else {
                LDAPResult result = conn.delete(dn);
                if (result.getResultCode() != ResultCode.SUCCESS) {
                    throw new LdapOperationException(
                        "deleteEntry failed for [" + dn + "]: "
                        + result.getResultCode() + " — " + result.getDiagnosticMessage());
                }
                log.info("Deleted LDAP entry {}", dn);
            }
            return null;
        });
    }

    /**
     * Deletes every descendant of {@code dn} (each direct child's whole subtree,
     * bottom-up) but not {@code dn} itself. Returns the number of direct
     * children removed.
     */
    private int deleteChildren(LDAPInterface conn, DirectoryConnection dc, String dn) throws LDAPException {
        List<ChildEntry> children = listChildren(conn, dc, dn);
        for (ChildEntry child : children) {
            deleteSubtree(conn, dc, child.dn());
        }
        return children.size();
    }

    private void deleteSubtree(LDAPInterface conn, DirectoryConnection dc,
                                String dn) throws LDAPException {
        // Depth-first: delete children before the parent
        List<ChildEntry> children = listChildren(conn, dc, dn);
        for (ChildEntry child : children) {
            deleteSubtree(conn, dc, child.dn());
        }
        LDAPResult result = conn.delete(dn);
        if (result.getResultCode() != ResultCode.SUCCESS) {
            throw new LdapOperationException(
                "deleteEntry failed for [" + dn + "]: "
                + result.getResultCode() + " — " + result.getDiagnosticMessage());
        }
    }

    /**
     * Moves an entry to a new parent DN (ModDN with newSuperior).
     */
    public void moveEntry(DirectoryConnection dc, String dn, String newParentDn) {
        connectionFactory.withConnection(dc, conn -> {
            String currentRdn = extractCurrentRdn(dn);
            LDAPResult result = conn.modifyDN(dn, currentRdn, true, newParentDn);
            if (result.getResultCode() != ResultCode.SUCCESS) {
                throw new LdapOperationException(
                    "moveEntry failed for [" + dn + "]: "
                    + result.getResultCode() + " — " + result.getDiagnosticMessage());
            }
            log.info("Moved LDAP entry {} to {}", dn, newParentDn);
            return null;
        });
    }

    /**
     * Renames an entry (changes its RDN in place).
     */
    public void renameEntry(DirectoryConnection dc, String dn, String newRdn) {
        DnValidator.requireValidRdn(newRdn, dc.getDirectoryType());
        connectionFactory.withConnection(dc, conn -> {
            LDAPResult result = conn.modifyDN(dn, newRdn, true);
            if (result.getResultCode() != ResultCode.SUCCESS) {
                throw new LdapOperationException(
                    "renameEntry failed for [" + dn + "]: "
                    + result.getResultCode() + " — " + result.getDiagnosticMessage());
            }
            log.info("Renamed LDAP entry {} to {}", dn, newRdn);
            return null;
        });
    }

    private String extractCurrentRdn(String dn) {
        int idx = dn.indexOf(',');
        return idx > 0 ? dn.substring(0, idx) : dn;
    }

    private String extractRdn(String childDn, String parentDn) {
        // Remove ",parentDn" suffix to get the RDN
        if (childDn.toLowerCase().endsWith("," + parentDn.toLowerCase())) {
            return childDn.substring(0, childDn.length() - parentDn.length() - 1);
        }
        return childDn;
    }

    // ── Search ────────────────────────────────────────────────────────────────

    /**
     * Backward-compat overload: searches with no server-side time limit and
     * no operational-attribute fetch. Internal callers (e.g. discovery,
     * sampling) use this; the controller uses the full overload below to
     * surface the new options to end users.
     */
    public List<SearchEntry> searchEntries(DirectoryConnection dc, String baseDn,
                                           SearchScope scope, String filter,
                                           List<String> attributes, int sizeLimit) {
        return searchEntries(dc, baseDn, scope, filter, attributes, sizeLimit, 0, false);
    }

    /**
     * Searches the DIT with configurable scope, filter, and attribute selection.
     *
     * @param dc                  directory connection
     * @param baseDn              search base DN
     * @param scope               search scope (BASE, ONE, SUB)
     * @param filter              LDAP filter string
     * @param attributes          attributes to return (empty = all)
     * @param sizeLimit           maximum entries to return
     * @param timeLimitSeconds    server-side query timeout; 0 = no limit
     * @param includeOperational  if true, request operational attributes
     *                            (createTimestamp, modifyTimestamp, etc.)
     *                            in addition to whatever's in `attributes`
     * @return list of matching entries with their attributes
     */
    public List<SearchEntry> searchEntries(DirectoryConnection dc, String baseDn,
                                           SearchScope scope, String filter,
                                           List<String> attributes, int sizeLimit,
                                           int timeLimitSeconds,
                                           boolean includeOperational) {
        String searchBase = (baseDn != null && !baseDn.isBlank()) ? baseDn : dc.getBaseDn();
        String effectiveFilter = (filter == null || filter.isBlank()) ? "(objectClass=*)" : filter;
        // Build the effective attribute array. When includeOperational is set,
        // append "+" — RFC 4511's marker for "all operational attributes". If
        // the caller didn't specify any user attributes, also pass "*" so we
        // get user attrs alongside operational ones; otherwise the server
        // would return ONLY operational attributes (surprising default).
        String[] attrArray;
        if (attributes == null || attributes.isEmpty()) {
            attrArray = includeOperational ? new String[]{"*", "+"} : new String[0];
        } else {
            List<String> attrList = new ArrayList<>(attributes);
            if (includeOperational) attrList.add("+");
            attrArray = attrList.toArray(new String[0]);
        }
        int pageSize = Math.min(dc.getPagingSize(), sizeLimit);

        return connectionFactory.withConnection(dc, conn -> {
            List<SearchEntry> results = new ArrayList<>();
            ASN1OctetString cookie = null;

            do {
                SearchRequest request = new SearchRequest(
                        searchBase, scope, Filter.create(effectiveFilter), attrArray);
                request.addControl(new SimplePagedResultsControl(pageSize, cookie));
                // Server-side size limit. The early-return below also caps
                // results at sizeLimit, but setting it on the request lets
                // capable servers (389DS, AD with admin-limit-override)
                // short-circuit at their end rather than streaming entries
                // we'll then discard.
                request.setSizeLimit(sizeLimit);
                // Server-side time limit. 0 means no limit (UnboundID's default
                // and matches LDAP's "0 = no limit" semantic). Negative values
                // are clamped to 0 so a malformed request can't accidentally
                // request -1s and confuse the server.
                if (timeLimitSeconds > 0) {
                    request.setTimeLimitSeconds(timeLimitSeconds);
                }

                SearchResult result;
                try {
                    result = conn.search(request);
                } catch (LDAPSearchException e) {
                    if (e.getResultCode() == ResultCode.NO_SUCH_OBJECT) {
                        return results;
                    }
                    throw e;
                }

                for (SearchResultEntry entry : result.getSearchEntries()) {
                    Map<String, List<String>> attrs = new LinkedHashMap<>();
                    for (var attr : entry.getAttributes()) {
                        attrs.put(attr.getBaseName(), Arrays.asList(attr.getValues()));
                    }
                    results.add(new SearchEntry(entry.getDN(), attrs));
                    if (results.size() >= sizeLimit) {
                        return results;
                    }
                }

                SimplePagedResultsControl pageResponse =
                        SimplePagedResultsControl.get(result);
                cookie = (pageResponse != null && pageResponse.moreResultsToReturn())
                        ? pageResponse.getCookie() : null;
            } while (cookie != null && cookie.getValue().length > 0);

            return results;
        });
    }

    // ── Paged search (directory search page) ───────────────────────────────

    /**
     * Hard ceiling on the number of entries a search returns even when the
     * caller asks for every match (limit 0, the page's "Load all"), and on
     * how far a truncated search counts its matches. Mirrors
     * {@link #MAX_CHILDREN} for the browser.
     */
    public static final int MAX_SEARCH_RESULTS = 50_000;

    /**
     * A page of search results plus what lies beyond it.
     *
     * @param entries           the entries returned (at most the requested limit)
     * @param truncated         more entries matched than were returned
     * @param total             how many entries matched in all — exact when
     *                          the page was complete or the count finished;
     *                          otherwise the number counted before stopping
     * @param totalIsLowerBound the count stopped early (the
     *                          {@link #MAX_SEARCH_RESULTS} ceiling, or a size
     *                          or time limit the server enforced), so at
     *                          least {@code total} entries matched
     */
    public record SearchPage(List<SearchEntry> entries, boolean truncated,
                             int total, boolean totalIsLowerBound) {}

    /**
     * Like {@link #searchEntries} but reports whether the page was cut short
     * and, when it was, how many entries matched in all. {@code limit} is the
     * page size; {@code 0} asks for every match, capped at
     * {@link #MAX_SEARCH_RESULTS}. The "were there more?" answer costs no
     * extra round-trip (one entry beyond the page is requested); the count
     * runs only for a truncated page and fetches DNs only.
     */
    public SearchPage searchPage(DirectoryConnection dc, String baseDn,
                                 SearchScope scope, String filter,
                                 List<String> attributes, int limit,
                                 int timeLimitSeconds,
                                 boolean includeOperational) {
        int effectiveLimit = limit <= 0 ? MAX_SEARCH_RESULTS : Math.min(limit, MAX_SEARCH_RESULTS);
        List<SearchEntry> fetched = searchEntries(dc, baseDn, scope, filter, attributes,
                effectiveLimit + 1, timeLimitSeconds, includeOperational);
        if (fetched.size() <= effectiveLimit) {
            return new SearchPage(fetched, false, fetched.size(), false);
        }
        List<SearchEntry> page = List.copyOf(fetched.subList(0, effectiveLimit));
        MatchCount count = countMatches(dc, baseDn, scope, filter, timeLimitSeconds);
        // The page itself proves at least effectiveLimit + 1 matches exist.
        int total = Math.max(count.count(), effectiveLimit + 1);
        return new SearchPage(page, true, total, count.lowerBound());
    }

    /** @param lowerBound the count stopped early; at least {@code count} matched */
    record MatchCount(int count, boolean lowerBound) {}

    /**
     * Counts the entries matching a search without fetching any attributes
     * ({@code 1.1}), paging through at most {@link #MAX_SEARCH_RESULTS}. A
     * server-enforced size or time limit ends the count early with what was
     * counted so far marked as a lower bound.
     */
    private MatchCount countMatches(DirectoryConnection dc, String baseDn, SearchScope scope,
                                    String filter, int timeLimitSeconds) {
        String searchBase = (baseDn != null && !baseDn.isBlank()) ? baseDn : dc.getBaseDn();
        String effectiveFilter = (filter == null || filter.isBlank()) ? "(objectClass=*)" : filter;

        return connectionFactory.withConnection(dc, conn -> {
            Filter parsed = Filter.create(effectiveFilter);
            int count = 0;
            ASN1OctetString cookie = null;
            try {
                do {
                    SearchRequest request = new SearchRequest(searchBase, scope, parsed, "1.1");
                    request.addControl(new SimplePagedResultsControl(dc.getPagingSize(), cookie));
                    if (timeLimitSeconds > 0) {
                        request.setTimeLimitSeconds(timeLimitSeconds);
                    }
                    SearchResult result = conn.search(request);
                    count += result.getEntryCount();

                    SimplePagedResultsControl pageResponse = SimplePagedResultsControl.get(result);
                    cookie = (pageResponse != null && pageResponse.moreResultsToReturn())
                            ? pageResponse.getCookie() : null;
                    if (count >= MAX_SEARCH_RESULTS && cookie != null && cookie.getValue().length > 0) {
                        abandonPaging(conn, searchBase, scope, parsed, cookie);
                        return new MatchCount(count, true);
                    }
                } while (cookie != null && cookie.getValue().length > 0);
                return new MatchCount(count, false);
            } catch (LDAPSearchException e) {
                if (e.getResultCode() == ResultCode.NO_SUCH_OBJECT) {
                    return new MatchCount(count, false);
                }
                if (e.getResultCode() == ResultCode.SIZE_LIMIT_EXCEEDED
                        || e.getResultCode() == ResultCode.TIME_LIMIT_EXCEEDED
                        || e.getResultCode() == ResultCode.ADMIN_LIMIT_EXCEEDED) {
                    return new MatchCount(count + e.getEntryCount(), true);
                }
                throw e;
            }
        });
    }

    // ── Value objects ─────────────────────────────────────────────────────────

    /**
     * @param truncated              more children matched than were returned
     * @param childCount             total direct children of this entry when
     *                               known (server-maintained count, or the
     *                               size of a complete unfiltered listing);
     *                               null when the server keeps no count and
     *                               the listing was cut short
     * @param childCountApproximate  the count is an estimate (Active
     *                               Directory's msDS-Approx-Immed-Subordinates)
     */
    public record BrowseResult(
            String dn,
            Map<String, List<String>> attributes,
            List<ChildEntry> children,
            boolean truncated,
            Integer childCount,
            boolean childCountApproximate
    ) {
        public BrowseResult(String dn, Map<String, List<String>> attributes, List<ChildEntry> children) {
            this(dn, attributes, children, false, children == null ? null : children.size(), false);
        }
    }

    public record ChildEntry(
            String dn,
            String rdn,
            boolean hasChildren
    ) {}

    public record SearchEntry(
            String dn,
            Map<String, List<String>> attributes
    ) {}
}
