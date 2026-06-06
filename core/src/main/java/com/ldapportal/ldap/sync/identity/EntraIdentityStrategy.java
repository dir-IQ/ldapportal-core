// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync.identity;

import com.ldapportal.entity.enums.DirectoryType;
import org.springframework.stereotype.Component;

/**
 * Identity via the Microsoft Graph {@code id} for Entra ID. Entra is read
 * through the Graph delta subsystem rather than LDAP, so there is no LDAP
 * identity attribute; the Graph object id is already a stable opaque GUID and
 * needs no normalization.
 */
@Component
public class EntraIdentityStrategy implements IdentityStrategy {

    @Override
    public boolean supports(DirectoryType type) {
        return type == DirectoryType.ENTRA_ID;
    }

    @Override
    public String identityAttribute() {
        // Entra is not read via LDAP; the Graph object id is the identity.
        return null;
    }
}
