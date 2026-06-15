// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.ldap.model.LdapEntry;
import com.ldapportal.ldap.model.LdapGroup;
import com.ldapportal.ldap.model.LdapUser;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.SearchResultEntry;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts UnboundID {@link SearchResultEntry} objects into the
 * {@link LdapEntry} / {@link LdapUser} / {@link LdapGroup} model hierarchy.
 *
 * <p>All attribute names are lower-cased during conversion to normalise
 * lookups across OpenLDAP and Active Directory schemas.</p>
 */
final class LdapEntryMapper {

    private LdapEntryMapper() {}

    static LdapUser toUser(SearchResultEntry entry) {
        return new LdapUser(entry.getDN(), extractAttributes(entry));
    }

    static LdapGroup toGroup(SearchResultEntry entry) {
        return new LdapGroup(entry.getDN(), extractAttributes(entry));
    }

    static LdapEntry toEntry(SearchResultEntry entry) {
        return new LdapEntry(entry.getDN(), extractAttributes(entry));
    }

    private static Map<String, List<String>> extractAttributes(SearchResultEntry entry) {
        Map<String, List<String>> attrs = new LinkedHashMap<>();
        for (Attribute attr : entry.getAttributes()) {
            attrs.put(attr.getBaseName().toLowerCase(), Arrays.asList(attr.getValues()));
        }
        return attrs;
    }
}
