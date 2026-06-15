// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.model;

import java.util.List;
import java.util.Map;

/**
 * LDAP entry projection for user objects.
 * Provides convenience accessors for common user attributes that work across
 * OpenLDAP (inetOrgPerson / posixAccount) and Active Directory schemas.
 */
public class LdapUser extends LdapEntry {

    public LdapUser(String dn, Map<String, List<String>> attributes) {
        super(dn, attributes);
    }

    /** Common Name — present in all user schemas. */
    public String getCn() {
        return getFirstValue("cn");
    }

    /** Unix uid / OpenLDAP login name. */
    public String getUid() {
        return getFirstValue("uid");
    }

    /** Active Directory login name. */
    public String getSamAccountName() {
        return getFirstValue("samaccountname");
    }

    /** Primary login — uid if present, sAMAccountName otherwise. */
    public String getLoginName() {
        String uid = getUid();
        return uid != null ? uid : getSamAccountName();
    }

    public String getMail() {
        return getFirstValue("mail");
    }

    public String getGivenName() {
        return getFirstValue("givenname");
    }

    public String getSn() {
        return getFirstValue("sn");
    }

    public String getDisplayName() {
        return getFirstValue("displayname");
    }

    /** Active Directory userAccountControl (raw string value). */
    public String getUserAccountControl() {
        return getFirstValue("useraccountcontrol");
    }

    /** OpenLDAP / POSIX account description. */
    public String getDescription() {
        return getFirstValue("description");
    }

    /** Groups this user belongs to (member-of style). */
    public List<String> getMemberOf() {
        return getValues("memberof");
    }

    @Override
    public String toString() {
        return "LdapUser{dn='" + getDn() + "', login='" + getLoginName() + "'}";
    }
}
