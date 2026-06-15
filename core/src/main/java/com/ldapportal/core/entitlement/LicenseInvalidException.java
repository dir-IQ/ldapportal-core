// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.entitlement;

/**
 * Thrown when a license JWT fails structural or cryptographic
 * validation — bad signature, missing required claim, malformed
 * value. Distinct from "expired past grace"; an expired license
 * verifies successfully and the caller decides how to handle it.
 */
public class LicenseInvalidException extends RuntimeException {
    public LicenseInvalidException(String message) {
        super(message);
    }
    public LicenseInvalidException(String message, Throwable cause) {
        super(message, cause);
    }
}
