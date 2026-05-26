// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.entitlement;

import lombok.Getter;

/**
 * Thrown by {@link EntitlementService#requireEntitlement(Entitlement)} when
 * the running license does not include the requested entitlement.
 *
 * <p>Mapped to HTTP 402 (Payment Required) by the global exception handler.
 * The handler includes {@link #getEntitlement()} and {@link #getCurrentEdition()}
 * in the ProblemDetail so the frontend can render an accurate upgrade modal.</p>
 */
@Getter
public class EntitlementMissingException extends RuntimeException {

    private final Entitlement entitlement;
    private final Edition currentEdition;

    public EntitlementMissingException(Entitlement entitlement, Edition currentEdition) {
        super("Feature not licensed: " + entitlement
              + " (current edition: " + currentEdition + ")");
        this.entitlement = entitlement;
        this.currentEdition = currentEdition;
    }
}
