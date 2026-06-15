// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.changelog;

/**
 * Thrown when a changelog entry's change content (e.g. an OUD {@code changes}
 * LDIF blob) cannot be reconstructed into a structured operation.
 *
 * <p>Distinct from {@link ChangelogStrategy#extractChange} returning empty: an
 * empty result is a normal skip (an entry that carries no replicable change),
 * whereas this exception signals a <b>malformed</b> entry. The replication
 * poller (C3) catches it to <b>dead-letter</b> the raw entry rather than
 * silently skipping it (data loss) or wedging the link (availability) — see
 * design §7A.3.
 */
public class ChangelogParseException extends RuntimeException {

    public ChangelogParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
