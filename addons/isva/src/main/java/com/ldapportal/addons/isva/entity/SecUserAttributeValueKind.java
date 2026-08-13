// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.entity;

/**
 * How a {@link SecUserAttribute}'s value is produced when a grant
 * writes it onto the {@code secUser} identity.
 *
 * <ul>
 *   <li>{@link #LITERAL} — the value is written verbatim (e.g.
 *       {@code TRUE}, {@code Default}). No interpolation, no
 *       functions.</li>
 *   <li>{@link #COMPUTED} — the value is an <em>expression</em>
 *       evaluated per user at provisioning time (see
 *       {@link com.ldapportal.addons.isva.SecUserExpressionEvaluator}):
 *       {@code ${user.<attr>}} / {@code ${sec.<attr>}} references and
 *       the {@code uuid()} / {@code now()} / {@code nowPlusYears(n)}
 *       functions.</li>
 * </ul>
 */
public enum SecUserAttributeValueKind {
    LITERAL,
    COMPUTED
}
