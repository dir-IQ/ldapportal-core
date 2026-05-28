// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import java.util.Set;

/**
 * Server-side password policy for application accounts (admins +
 * superadmins). DTO {@code @Size(min=8)} catches obviously-empty input;
 * this class enforces the actual baseline operators expect for sysadmin
 * credentials.
 *
 * <p>Baseline:
 * <ul>
 *   <li>Minimum 12 characters.</li>
 *   <li>At least three of {lowercase, uppercase, digit, special}.</li>
 *   <li>Rejects a small blocklist of frequently-leaked passwords —
 *       not a substitute for a haveibeenpwned check, just a cheap
 *       guard against the worst defaults.</li>
 * </ul>
 *
 * <p>Self-service LDAP user passwords are governed by the per-profile
 * {@link com.ldapportal.entity.ProvisioningProfile} password fields,
 * not this class. This applies only to {@code Account} (admin /
 * superadmin) passwords.</p>
 */
public final class AccountPasswordPolicy {

    private AccountPasswordPolicy() {}

    /** Minimum total length. */
    public static final int MIN_LENGTH = 12;

    /**
     * Out of {lower, upper, digit, special} at least this many must
     * appear in the candidate password.
     */
    public static final int MIN_CATEGORIES = 3;

    /**
     * Tiny common-password blocklist. Not exhaustive; the point is to
     * reject "P@ssword1234" / "Admin@12345" reflexes without pulling
     * in a multi-megabyte wordlist. Compared case-insensitively against
     * the candidate's full value, so leetspeak doesn't slip "password"
     * through as "Password".
     */
    private static final Set<String> BLOCKED = Set.of(
            "password",
            "password1",
            "password12",
            "password123",
            "password1234",
            "password12345",
            "admin",
            "admin123",
            "admin1234",
            "administrator",
            "superadmin",
            "welcome",
            "welcome1",
            "welcome123",
            "qwerty",
            "qwertyuiop",
            "letmein",
            "changeme",
            "changeme1",
            "changeme123",
            "passw0rd",
            "p@ssw0rd",
            "p@ssword",
            "p@ssword1",
            "iloveyou",
            "trustno1");

    /**
     * Validates {@code password} against the baseline. Throws
     * {@link IllegalArgumentException} with a user-facing message
     * (mapped to 400 by GlobalExceptionHandler) when the check fails.
     */
    public static void validate(String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Password must not be empty");
        }
        if (password.length() < MIN_LENGTH) {
            throw new IllegalArgumentException(
                    "Password must be at least " + MIN_LENGTH + " characters");
        }
        if (BLOCKED.contains(password.toLowerCase())) {
            throw new IllegalArgumentException(
                    "Password is too common — pick something less guessable");
        }

        int categories = 0;
        if (containsAny(password, c -> Character.isLowerCase(c))) categories++;
        if (containsAny(password, c -> Character.isUpperCase(c))) categories++;
        if (containsAny(password, c -> Character.isDigit(c)))     categories++;
        if (containsAny(password, AccountPasswordPolicy::isSpecial)) categories++;
        if (categories < MIN_CATEGORIES) {
            throw new IllegalArgumentException(
                    "Password must include at least " + MIN_CATEGORIES
                            + " of: lowercase, uppercase, digit, special character");
        }
    }

    private static boolean containsAny(String s, java.util.function.IntPredicate test) {
        for (int i = 0; i < s.length(); i++) {
            if (test.test(s.charAt(i))) return true;
        }
        return false;
    }

    /**
     * Special = any printable non-alphanumeric character. Avoids
     * hard-coding a fixed set so users aren't surprised that their
     * preferred separator doesn't count.
     */
    private static boolean isSpecial(int c) {
        return !Character.isLetterOrDigit(c) && !Character.isWhitespace(c)
                && c >= 0x20 && c < 0x7F;
    }
}
