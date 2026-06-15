// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LDAP entry projection for group objects.
 * Supports the three most common membership attribute conventions:
 * <ul>
 *   <li>{@code member} — groupOfNames / groupOfUniqueNames (DN values)</li>
 *   <li>{@code uniqueMember} — groupOfUniqueNames (DN values)</li>
 *   <li>{@code memberUid} — posixGroup (UID string values)</li>
 * </ul>
 */
public class LdapGroup extends LdapEntry {

    public LdapGroup(String dn, Map<String, List<String>> attributes) {
        super(dn, attributes);
    }

    public String getCn() {
        return getFirstValue("cn");
    }

    public String getDescription() {
        return getFirstValue("description");
    }

    /** DN-valued members (groupOfNames / AD group). */
    public List<String> getMember() {
        return getValues("member");
    }

    /** DN-valued members (groupOfUniqueNames). */
    public List<String> getUniqueMember() {
        return getValues("uniquemember");
    }

    /** UID-string members (posixGroup). */
    public List<String> getMemberUid() {
        return getValues("memberuid");
    }

    /**
     * Returns all member values regardless of which attribute convention is used.
     * DN-valued attributes ({@code member}, {@code uniqueMember}) are combined;
     * {@code memberUid} strings are added if no DN-based members were found.
     */
    public List<String> getAllMembers() {
        List<String> members = new ArrayList<>(getMember());
        members.addAll(getUniqueMember());
        if (members.isEmpty()) {
            members.addAll(getMemberUid());
        }
        return members;
    }

    @Override
    public String toString() {
        return "LdapGroup{dn='" + getDn() + "', cn='" + getCn() + "'}";
    }
}
