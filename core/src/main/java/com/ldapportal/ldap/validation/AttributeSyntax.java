// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.validation;

import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.entity.enums.InputType;

import java.util.regex.Pattern;

/**
 * Built-in <em>syntax</em> validators for well-known LDAP attribute value kinds,
 * sitting beside the configurable profile-rule engine
 * ({@code ProvisioningProfileService.validateAttributes}). Where the profile
 * engine enforces admin-configured rules (length / regex / allowed-values), this
 * enforces the intrinsic shape of a value implied by the attribute itself —
 * a DN-valued attribute must hold a syntactically valid DN, {@code mail} must
 * look like an email address, a boolean attribute must be {@code TRUE}/{@code FALSE}.
 *
 * <p>Each check throws {@link IllegalArgumentException} on a malformed value,
 * which the core {@code GlobalExceptionHandler} maps to an HTTP 400 ProblemDetail
 * — a clean field-level message rather than the raw {@code INVALID_DN_SYNTAX} /
 * constraint-violation error the directory server would otherwise return at
 * write time.</p>
 *
 * <p><strong>Directory-type aware.</strong> DN-syntax is skipped for
 * {@link DirectoryType#ENTRA_ID} (no DN container model), delegating to
 * {@link DnValidator#requireValidDn} which already encodes that exemption.</p>
 */
public final class AttributeSyntax {

    /** The intrinsic value shape an attribute is expected to hold. */
    public enum Kind { DN, EMAIL, BOOLEAN }

    /**
     * Deliberately permissive email shape — exactly one {@code @}, a non-empty
     * local part and a dotted domain, no whitespace. Strict enough to reject
     * obviously malformed input, lenient enough not to reject the unusual but
     * legitimate addresses real directories carry.
     */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private AttributeSyntax() {
    }

    /**
     * The syntax kind a profile {@code inputType} authoritatively implies, or
     * {@code null} if the input type carries no intrinsic value shape. A
     * {@code DN_LOOKUP} field holds a DN; a {@code BOOLEAN} field holds
     * {@code TRUE}/{@code FALSE}. This is the single source of truth shared by
     * server-side resolution ({@link LdapAttributeValidator}) and the
     * client-facing syntax hints surfaced to the admin UI.
     */
    public static Kind forInputType(InputType inputType) {
        if (inputType == InputType.DN_LOOKUP) {
            return Kind.DN;
        }
        if (inputType == InputType.BOOLEAN) {
            return Kind.BOOLEAN;
        }
        return null;
    }

    /**
     * Validates {@code value} against the given {@code kind}. No-op shapes are
     * the caller's responsibility (a {@code null}/blank value is never passed
     * here — see {@link LdapAttributeValidator}).
     *
     * @throws IllegalArgumentException if {@code value} does not match the kind
     */
    public static void require(Kind kind, String attribute, String value, DirectoryType directoryType) {
        switch (kind) {
            case DN -> requireDn(attribute, value, directoryType);
            case EMAIL -> requireEmail(attribute, value);
            case BOOLEAN -> requireBoolean(attribute, value);
        }
    }

    private static void requireDn(String attribute, String value, DirectoryType directoryType) {
        try {
            DnValidator.requireValidDn(value, directoryType);
        } catch (IllegalArgumentException e) {
            // Re-throw with the attribute name so the UI can key the error to a
            // field, preserving the underlying DN diagnostic as the cause.
            throw new IllegalArgumentException(
                    "Attribute [" + attribute + "] is not a valid DN: " + value, e);
        }
    }

    private static void requireEmail(String attribute, String value) {
        if (!EMAIL.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Attribute [" + attribute + "] is not a valid email address: " + value);
        }
    }

    private static void requireBoolean(String attribute, String value) {
        if (!value.equalsIgnoreCase("TRUE") && !value.equalsIgnoreCase("FALSE")) {
            throw new IllegalArgumentException(
                    "Attribute [" + attribute + "] must be TRUE or FALSE");
        }
    }
}
