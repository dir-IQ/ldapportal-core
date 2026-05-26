// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.entity.ProvisioningProfile;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;

/**
 * Generates random passwords based on a provisioning profile's password policy settings.
 */
@Service
public class PasswordGeneratorService {

    private static final String UPPERCASE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String LOWERCASE = "abcdefghijklmnopqrstuvwxyz";
    private static final String DIGITS    = "0123456789";

    private final SecureRandom random = new SecureRandom();

    public String generate(ProvisioningProfile profile) {
        StringBuilder pool = new StringBuilder();
        if (profile.isPasswordUppercase()) pool.append(UPPERCASE);
        if (profile.isPasswordLowercase()) pool.append(LOWERCASE);
        if (profile.isPasswordDigits())    pool.append(DIGITS);
        if (profile.isPasswordSpecial() && profile.getPasswordSpecialChars() != null
                && !profile.getPasswordSpecialChars().isEmpty()) {
            pool.append(profile.getPasswordSpecialChars());
        }
        if (pool.isEmpty()) {
            // Fallback: use all character classes
            pool.append(UPPERCASE).append(LOWERCASE).append(DIGITS).append("!@#$%^&*");
        }

        int length = Math.max(profile.getPasswordLength(), 8);
        char[] password = new char[length];

        // Guarantee at least one character from each enabled class
        int idx = 0;
        if (profile.isPasswordUppercase()) {
            password[idx++] = randomChar(UPPERCASE);
        }
        if (profile.isPasswordLowercase()) {
            password[idx++] = randomChar(LOWERCASE);
        }
        if (profile.isPasswordDigits()) {
            password[idx++] = randomChar(DIGITS);
        }
        if (profile.isPasswordSpecial() && profile.getPasswordSpecialChars() != null
                && !profile.getPasswordSpecialChars().isEmpty()) {
            password[idx++] = randomChar(profile.getPasswordSpecialChars());
        }

        // Fill remaining positions from the full pool
        String poolStr = pool.toString();
        for (int i = idx; i < length; i++) {
            password[i] = randomChar(poolStr);
        }

        // Shuffle to avoid predictable positions for guaranteed chars
        for (int i = length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            char tmp = password[i];
            password[i] = password[j];
            password[j] = tmp;
        }

        return new String(password);
    }

    private char randomChar(String chars) {
        return chars.charAt(random.nextInt(chars.length()));
    }
}
