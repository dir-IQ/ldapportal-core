// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.diff;

import com.ldapportal.entity.enums.DirectoryDiffPairing;

import java.util.List;
import java.util.UUID;

/**
 * What to compare: two sides, how their entries pair, and which attributes
 * count.
 *
 * @param pairBy        {@code DN} (default) or {@code ATTRIBUTE}
 * @param pairAttribute the attribute entries pair by, for {@code ATTRIBUTE}
 * @param attributes    compare only these; {@code null} or empty for every user attribute
 * @param ignore        also leave these out (on top of operational attributes and passwords)
 */
public record DirectoryDiffRequest(Side a, Side b, DirectoryDiffPairing pairBy, String pairAttribute,
                                   List<String> attributes, List<String> ignore) {

    /**
     * One side of the comparison.
     *
     * @param baseDn blank for the directory's base DN
     * @param scope  {@code SUB} (default) or {@code ONE}
     * @param filter an LDAP filter; blank for every entry
     */
    public record Side(UUID directoryId, String baseDn, String scope, String filter) {
    }
}
