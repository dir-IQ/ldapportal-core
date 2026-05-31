// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.exception.LdapOperationException;
import com.ldapportal.ldap.annotation.LdapWriteAuthorized;
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
     * Fetches the entry at {@code dn} together with its direct children.
     *
     * @param dc   directory connection
     * @param dn   the base DN to browse (null falls back to directory base DN)
     * @return browse result containing the entry's attributes and child list
     */
    public BrowseResult browse(DirectoryConnection dc, String dn) {
        if (dc.getDirectoryType() == DirectoryType.ENTRA_ID) {
            throw new IllegalArgumentException("This feature is not supported for Entra ID directories");
        }
        String baseDn = (dn != null && !dn.isBlank()) ? dn : dc.getBaseDn();

        return connectionFactory.withConnection(dc, conn -> {
            // 1. Read the entry itself
            Map<String, List<String>> attributes = readEntry(conn, baseDn);

            // 2. One-level search to find direct children
            List<ChildEntry> children = listChildren(conn, dc, baseDn);

            return new BrowseResult(baseDn, attributes, children);
        });
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
        SearchResultEntry entry = conn.getEntry(dn);
        if (entry == null) {
            return Map.of();
        }
        Map<String, List<String>> attrs = new LinkedHashMap<>();
        for (var attr : entry.getAttributes()) {
            attrs.put(attr.getBaseName(), Arrays.asList(attr.getValues()));
        }
        return attrs;
    }

    private List<ChildEntry> listChildren(LDAPInterface conn,
                                           DirectoryConnection dc,
                                           String baseDn) throws LDAPException {
        List<ChildEntry> children = new ArrayList<>();

        try {
            ASN1OctetString cookie = null;
            do {
                SearchRequest request = new SearchRequest(
                        baseDn, SearchScope.ONE,
                        Filter.createPresenceFilter("objectClass"),
                        "dn");
                request.addControl(new SimplePagedResultsControl(dc.getPagingSize(), cookie));

                SearchResult result = conn.search(request);
                for (SearchResultEntry child : result.getSearchEntries()) {
                    String childDn = child.getDN();
                    String rdn = extractRdn(childDn, baseDn);
                    boolean hasChildren = hasSubEntries(conn, childDn);
                    children.add(new ChildEntry(childDn, rdn, hasChildren));
                }

                SimplePagedResultsControl pageResponse =
                        SimplePagedResultsControl.get(result);
                cookie = (pageResponse != null && pageResponse.moreResultsToReturn())
                        ? pageResponse.getCookie() : null;
            } while (cookie != null && cookie.getValue().length > 0);
        } catch (LDAPSearchException e) {
            if (e.getResultCode() == ResultCode.NO_SUCH_OBJECT) {
                log.debug("Base '{}' does not exist — returning empty children", baseDn);
                return children;
            }
            throw e;
        }

        children.sort(Comparator.comparing(ChildEntry::rdn, String.CASE_INSENSITIVE_ORDER));
        return children;
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
        DN parsed;
        try {
            parsed = new DN(dn);
        } catch (LDAPException e) {
            throw new IllegalArgumentException("Invalid DN: " + dn, e);
        }
        if (parsed.isNullDN() || parsed.getRDNs().length == 0) {
            throw new IllegalArgumentException("Cannot create container at empty DN");
        }
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
        connectionFactory.withConnection(dc, conn -> {
            if (recursive) {
                deleteSubtree(conn, dc, dn);
            } else {
                LDAPResult result = conn.delete(dn);
                if (result.getResultCode() != ResultCode.SUCCESS) {
                    throw new LdapOperationException(
                        "deleteEntry failed for [" + dn + "]: "
                        + result.getResultCode() + " — " + result.getDiagnosticMessage());
                }
            }
            log.info("Deleted LDAP entry {}{}", dn, recursive ? " (recursive)" : "");
            return null;
        });
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

    // ── Value objects ─────────────────────────────────────────────────────────

    public record BrowseResult(
            String dn,
            Map<String, List<String>> attributes,
            List<ChildEntry> children
    ) {}

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
