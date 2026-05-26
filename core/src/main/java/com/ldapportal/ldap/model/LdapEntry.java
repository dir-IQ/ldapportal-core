// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable representation of a single LDAP entry returned from the directory.
 * Attributes are stored as a lower-cased map to normalise lookup across
 * OpenLDAP (lower-case) and Active Directory (mixed-case) schemas.
 */
public class LdapEntry {

    private final String dn;
    private final Map<String, List<String>> attributes;

    public LdapEntry(String dn, Map<String, List<String>> attributes) {
        this.dn = dn;
        this.attributes = Collections.unmodifiableMap(attributes);
    }

    public String getDn() {
        return dn;
    }

    public Map<String, List<String>> getAttributes() {
        return attributes;
    }

    /**
     * Returns all values for {@code attribute}, or an empty list if the
     * attribute is absent.  Lookup is case-insensitive.
     */
    public List<String> getValues(String attribute) {
        return attributes.getOrDefault(attribute.toLowerCase(), Collections.emptyList());
    }

    /**
     * Returns the first value of {@code attribute}, or {@code null} if absent.
     */
    public String getFirstValue(String attribute) {
        List<String> values = getValues(attribute);
        return values.isEmpty() ? null : values.get(0);
    }

    /**
     * Returns an Optional of the first value of {@code attribute}.
     */
    public Optional<String> findFirstValue(String attribute) {
        return Optional.ofNullable(getFirstValue(attribute));
    }

    public boolean hasAttribute(String attribute) {
        return attributes.containsKey(attribute.toLowerCase());
    }

    @Override
    public String toString() {
        return "LdapEntry{dn='" + dn + "', attributes=" + attributes.keySet() + "}";
    }
}
