// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync.identity;

import com.ldapportal.entity.enums.DirectoryType;
import org.springframework.stereotype.Component;

/**
 * Identity via Active Directory's {@code objectGUID}. AD returns it as a 16-byte
 * binary value (never {@code objectSID}, which changes on domain migration);
 * the binary → canonical-string normalization that the membership index keys on
 * lands with the rich-identity phase. Phase-0 declares the seam only.
 */
@Component
public class ObjectGuidIdentityStrategy implements IdentityStrategy {

    @Override
    public boolean supports(DirectoryType type) {
        return type == DirectoryType.ACTIVE_DIRECTORY;
    }

    @Override
    public String identityAttribute() {
        return "objectGUID";
    }
}
