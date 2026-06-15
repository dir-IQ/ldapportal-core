// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.exception;

/**
 * Thrown when AES-256 GCM encryption or decryption fails (e.g. invalid key,
 * corrupted ciphertext, authentication tag mismatch).
 */
public class EncryptionException extends LdapAdminException {

    public EncryptionException(String message) {
        super(message);
    }

    public EncryptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
