// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.ldap;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Broken member references to remove from their groups, as an integrity
 * check reported them ({@code BROKEN_MEMBER} issues).
 */
public record RemoveBrokenReferencesRequest(
        @NotEmpty @Size(max = RemoveBrokenReferencesRequest.MAX_REFERENCES) List<@Valid Reference> references) {

    /** References one request may name. */
    public static final int MAX_REFERENCES = 5_000;

    /**
     * @param groupDn   the group holding the reference
     * @param attribute {@code member} or {@code uniqueMember}
     * @param value     the referenced DN, as the group stores it
     */
    public record Reference(@NotBlank String groupDn, @NotBlank String attribute, @NotBlank String value) {
    }
}
