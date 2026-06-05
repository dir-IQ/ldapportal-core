// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.exception;

/**
 * Thrown when an {@code If-Match} precondition doesn't match the resource's
 * current version — the caller is trying to update a state it hasn't seen.
 * Maps to HTTP 412 Precondition Failed in the REST layer.
 *
 * <p>Distinct from {@link ConflictException}/optimistic-lock 409s: this is the
 * <em>pre-write</em> check (the client asserted a version that's already
 * stale), whereas the 409 is the backstop when a concurrent commit wins the
 * race between this transaction's read and its flush.</p>
 */
public class PreconditionFailedException extends LdapAdminException {

    public PreconditionFailedException(String message) {
        super(message);
    }
}
