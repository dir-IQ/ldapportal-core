// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva;

import com.ldapportal.addons.isva.entity.VendorIntegrationIsvaConfig;
import com.ldapportal.core.provisioning.AddStep;
import com.ldapportal.core.provisioning.StepFailurePolicy;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.exception.LdapOperationException;
import com.ldapportal.ldap.LdapConnectionFactory;
import com.unboundid.asn1.ASN1OctetString;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.RDN;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import com.unboundid.ldap.sdk.controls.SimplePagedResultsControl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * IVIA's record of an imported group. {@code pdadmin group import} doesn't
 * touch the group itself: it creates a separate {@code secGroup} entry in the
 * management DIT's groups container whose {@code secDN} points back at the
 * group, e.g.
 *
 * <pre>
 * dn: cn=Engineers,cn=Groups,secAuthority=Default,&lt;suffix&gt;
 * objectClass: top
 * objectClass: secGroup
 * cn: Engineers
 * secAuthority: Default
 * secDN: cn=Engineers,ou=groups,o=example
 * secHasPolicy: FALSE
 * secUUID: &lt;uuid&gt;
 * </pre>
 *
 * This finds those entries and builds the one an import writes.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IsvaGroupRegistry {

    private final LdapConnectionFactory connectionFactory;

    /**
     * The registration entry whose {@code secDN} is {@code groupDn}, if any.
     * Empty when the container doesn't exist (nothing imported yet).
     */
    public Optional<String> findRegistrationDn(DirectoryConnection dir, String groupContainerDn,
                                               String groupDn) {
        return connectionFactory.withConnection(dir, conn -> {
            SearchRequest req = new SearchRequest(groupContainerDn, SearchScope.SUB,
                    Filter.createANDFilter(
                            Filter.createEqualityFilter("objectClass", "secGroup"),
                            Filter.createEqualityFilter("secDN", groupDn)),
                    "1.1");
            req.setSizeLimit(2);
            try {
                SearchResult result = conn.search(req);
                if (result.getEntryCount() > 1) {
                    log.warn("{} secGroup entries under {} point at {} — using the first.",
                            result.getEntryCount(), groupContainerDn, groupDn);
                }
                return result.getEntryCount() == 0 ? Optional.<String>empty()
                        : Optional.of(result.getSearchEntries().get(0).getDN());
            } catch (LDAPException e) {
                if (e.getResultCode() == ResultCode.NO_SUCH_OBJECT) {
                    return Optional.<String>empty();
                }
                if (e.getResultCode() == ResultCode.SIZE_LIMIT_EXCEEDED
                        && e instanceof com.unboundid.ldap.sdk.LDAPSearchException se
                        && se.getEntryCount() > 0) {
                    return Optional.of(se.getSearchEntries().get(0).getDN());
                }
                throw new LdapOperationException("Failed to look up the IVIA registration of "
                        + groupDn + " under " + groupContainerDn, e);
            }
        });
    }

    /**
     * Every imported group's DN, normalized, mapped to its registration entry
     * — for marking a whole group list in one search. Empty when the container
     * doesn't exist.
     */
    public Map<String, String> registeredGroups(DirectoryConnection dir, String groupContainerDn) {
        return connectionFactory.withConnection(dir, conn -> {
            Map<String, String> out = new LinkedHashMap<>();
            SearchRequest req = new SearchRequest(groupContainerDn, SearchScope.SUB,
                    Filter.createEqualityFilter("objectClass", "secGroup"), "secDN");
            ASN1OctetString cookie = null;
            try {
                do {
                    req.setControls(new SimplePagedResultsControl(Math.max(1, dir.getPagingSize()), cookie));
                    SearchResult result = conn.search(req);
                    for (SearchResultEntry e : result.getSearchEntries()) {
                        String secDn = e.getAttributeValue("secDN");
                        if (secDn != null) {
                            out.putIfAbsent(normalize(secDn), e.getDN());
                        }
                    }
                    SimplePagedResultsControl paging = SimplePagedResultsControl.get(result);
                    cookie = paging != null && paging.moreResultsToReturn() ? paging.getCookie() : null;
                } while (cookie != null && cookie.getValueLength() > 0);
            } catch (LDAPException e) {
                if (e.getResultCode() == ResultCode.NO_SUCH_OBJECT) {
                    return out;
                }
                throw new LdapOperationException("Failed to list IVIA group registrations under "
                        + groupContainerDn, e);
            }
            return out;
        });
    }

    /** Whether {@code dn} exists. */
    public boolean exists(DirectoryConnection dir, String dn) {
        return connectionFactory.withConnection(dir, conn -> {
            try {
                return conn.getEntry(dn, "1.1") != null;
            } catch (LDAPException e) {
                if (e.getResultCode() == ResultCode.NO_SUCH_OBJECT) {
                    return false;
                }
                throw new LdapOperationException("Failed to read " + dn, e);
            }
        });
    }

    /** The registration entry's DN for an IVIA group name: {@code cn=<name>,<container>}. */
    public static String registrationDn(String groupContainerDn, String iviaName) {
        return new RDN("cn", iviaName) + "," + groupContainerDn;
    }

    /**
     * The ADD an import performs — the same entry {@code pdadmin group import}
     * creates. {@code ABORT}: there is nothing before it to compensate.
     */
    public static AddStep importStep(VendorIntegrationIsvaConfig cfg, String groupContainerDn,
                                     String groupDn, String iviaName) {
        List<Attribute> attrs = new ArrayList<>();
        attrs.add(new Attribute("objectClass", "top", "secGroup"));
        attrs.add(new Attribute("cn", iviaName));
        String authority = cfg.getSecAuthority() == null || cfg.getSecAuthority().isBlank()
                ? "Default" : cfg.getSecAuthority().trim();
        attrs.add(new Attribute("secAuthority", authority));
        attrs.add(new Attribute("secDN", groupDn));
        attrs.add(new Attribute("secHasPolicy", "FALSE"));
        attrs.add(new Attribute("secUUID", UUID.randomUUID().toString()));
        return new AddStep(registrationDn(groupContainerDn, iviaName), attrs, StepFailurePolicy.ABORT);
    }

    /** DN in a form that compares equal however it was spelled. */
    public static String normalize(String dn) {
        try {
            return new DN(dn).toNormalizedString();
        } catch (LDAPException e) {
            return dn.trim().toLowerCase(Locale.ROOT);
        }
    }
}
