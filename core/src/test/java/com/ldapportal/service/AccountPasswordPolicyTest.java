// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pin the {@link AccountPasswordPolicy} baseline. The DTO-level
 * {@code @Size(min=8)} caught empty input but nothing else — these
 * cases lock in the actual service-side checks so they can't be
 * silently relaxed.
 */
class AccountPasswordPolicyTest {

    @Test
    void rejectsNullAndBlank() {
        assertThatThrownBy(() -> AccountPasswordPolicy.validate(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccountPasswordPolicy.validate("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTooShort() {
        // 11 chars — meets old @Size(min=8) but under new MIN_LENGTH=12.
        assertThatThrownBy(() -> AccountPasswordPolicy.validate("Abc12345!aa"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 12 characters");
    }

    @Test
    void rejectsCommonPassword() {
        // Case-insensitive blocklist hit. Use one that's also long
        // enough so it would otherwise pass the length check.
        assertThatThrownBy(() -> AccountPasswordPolicy.validate("Password12345"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too common");
    }

    @Test
    void rejectsTooFewCategories() {
        // 12 lowercase letters — meets length but only one category.
        assertThatThrownBy(() -> AccountPasswordPolicy.validate("abcdefghijkl"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 3 of");
    }

    @Test
    void acceptsStrong() {
        // Length 14, lower + upper + digit + special, not blocklisted.
        assertThatCode(() -> AccountPasswordPolicy.validate("MyN3w-pw-2026!"))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsThreeOfFourCategories() {
        // No special character but 12+ chars with lower + upper + digit
        // — three categories is enough.
        assertThatCode(() -> AccountPasswordPolicy.validate("MyN3wPw2026OK"))
                .doesNotThrowAnyException();
    }
}
