// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.validation;

import com.ldapportal.entity.enums.DirectoryType;
import com.unboundid.ldap.sdk.RDN;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Naming-attribute consistency for entry creation.
 *
 * <p>LDAP requires every attribute-value assertion (AVA) in an entry's RDN to
 * be present among the entry's attribute values — including each component of
 * a multi-valued RDN such as {@code o=0001+cn=Sanjay Mishra,ou=People,…}.
 * Active Directory derives the naming values implicitly, but OpenLDAP, OUD,
 * and DSEE reject the add with a naming violation when they are missing from
 * the attribute set.</p>
 *
 * <p>Merging the DN's naming values into the attribute map (rather than
 * rejecting the request) makes the behaviour uniform across vendors and lets
 * operator-overridden DNs land correctly for direct API and bulk callers, not
 * just the web form (which applies the same merge client-side for instant
 * feedback).</p>
 */
public final class NamingAttributes {

    private NamingAttributes() {
    }

    /**
     * Returns a copy of {@code attributes} in which every AVA of the entry
     * DN's leading RDN is present among the entry's values: appended when the
     * attribute already exists with other values, added when absent.
     * Attribute names and values are matched case-insensitively (LDAP names
     * always are, and the common directory string syntaxes are caseIgnore);
     * existing key case is preserved. No-op for Entra ID, which identifies
     * objects by object id / UPN rather than by DN.
     *
     * @throws IllegalArgumentException if {@code dn} is not a valid DN
     */
    public static Map<String, List<String>> mergeRdnValues(String dn,
                                                           Map<String, List<String>> attributes,
                                                           DirectoryType directoryType) {
        if (directoryType == DirectoryType.ENTRA_ID) {
            return attributes;
        }
        RDN rdn = DnValidator.parse(dn).getRDN();
        Map<String, List<String>> merged = new LinkedHashMap<>(attributes);
        String[] names  = rdn.getAttributeNames();
        String[] values = rdn.getAttributeValues();
        for (int i = 0; i < names.length; i++) {
            String key = findKey(merged, names[i]);
            String value = values[i];
            List<String> existing = key == null ? List.of() : merged.get(key);
            if (existing.stream().noneMatch(v -> v.equalsIgnoreCase(value))) {
                List<String> withNaming = new ArrayList<>(existing);
                withNaming.add(value);
                merged.put(key == null ? names[i] : key, withNaming);
            }
        }
        return merged;
    }

    /** The map's own key for {@code name}, matched case-insensitively. */
    private static String findKey(Map<String, List<String>> attributes, String name) {
        return attributes.keySet().stream()
                .filter(k -> k.equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }
}
