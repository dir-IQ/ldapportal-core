// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.ldap;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Apply a batch of group-membership changes for a single member (the user
 * identified by the {@code dn} query parameter on the endpoint).
 *
 * <p>Each {@link Change} targets one group. The same member value (the user's
 * DN) is written into every target group's {@code memberAttribute}, mirroring
 * the single-member endpoints — callers using {@code memberUid}/posixGroup
 * schemas remain responsible for the value semantics, exactly as today.</p>
 *
 * <p>The batch is one HTTP round-trip but fans out to one LDAP write per
 * change; it is best-effort, not atomic (LDAP offers no cross-entry
 * transaction here). Per-change outcomes come back in
 * {@link MembershipChangeResult}.</p>
 */
public record MembershipChangeRequest(
        @NotEmpty @Valid List<Change> changes) {

    public enum Op { ADD, REMOVE }

    public record Change(
            @NotBlank String groupDn,
            @NotBlank String memberAttribute,
            @NotNull Op op) {
    }
}
