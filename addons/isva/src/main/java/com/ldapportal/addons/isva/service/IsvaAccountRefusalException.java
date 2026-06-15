// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.service;

import org.springframework.http.HttpStatus;

/**
 * IVIA-verb refusal carrying a stable refusal {@code code} and the
 * intended HTTP status. P2's REST controller maps this to an RFC 7807
 * {@code ProblemDetail} with the {@code code} as a top-level property,
 * letting the frontend dispatch contextual CTAs (e.g., "Flip the
 * profile override → and retry" for {@code ivia_force_off}) without
 * parsing the message.
 *
 * <p>Codes (mirrored in P5 docs):
 * <ul>
 *   <li>{@code ivia_already_linked} — grant on an already-granted account</li>
 *   <li>{@code ivia_orphan} — revoke/suspend/renew/reset with no IVIA account</li>
 *   <li>{@code ivia_force_off} — grant refused because the profile resolves to FORCE_OFF</li>
 *   <li>{@code ivia_directory_disabled} — directory has no active IVIA config</li>
 *   <li>{@code ivia_renew_not_forward} — proposed validUntil ≤ current secValidUntil</li>
 *   <li>{@code ivia_state_changed} — TOCTOU: probe and verb saw different LDAP state</li>
 * </ul></p>
 *
 * <p>Most codes map to 409. {@code ivia_renew_not_forward} maps to 400
 * — the proposed value is invalid on its own merits, not a conflict
 * with another writer.</p>
 */
public class IsvaAccountRefusalException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public IsvaAccountRefusalException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
