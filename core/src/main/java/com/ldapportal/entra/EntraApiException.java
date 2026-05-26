// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.entra;

/**
 * Thrown when a Microsoft Graph API call fails.
 */
public class EntraApiException extends RuntimeException {

    public EntraApiException(String message) {
        super(message);
    }

    public EntraApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
