// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** An {@code entryUUID} anchor value read back off a target entry must look like one. */
class EntryUuidIdentityStrategyTest {

    private final EntryUuidIdentityStrategy strategy = new EntryUuidIdentityStrategy();

    @Test
    void isWellFormed_acceptsANormalizedUuid_only() {
        assertThat(strategy.isWellFormed(strategy.normalize(" 11111111-2222-3333-4444-AAAAAAAAAAAA ")))
                .isTrue();
        assertThat(strategy.isWellFormed("11111111-2222-3333-4444-55555555555")).isFalse(); // one digit short
        assertThat(strategy.isWellFormed("2nd floor")).isFalse();
        assertThat(strategy.isWellFormed("")).isFalse();
        assertThat(strategy.isWellFormed(null)).isFalse();
    }

    @Test
    void theDefaultShapeCheck_onlyNeedsAValue() {
        IdentityStrategy plain = new EntraIdentityStrategy();
        assertThat(plain.isWellFormed("anything")).isTrue();
        assertThat(plain.isWellFormed(" ")).isFalse();
    }
}
