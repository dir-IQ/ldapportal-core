// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The opaque-cursor codec: the DSEE numeric family round-trips through the token;
 * a non-numeric (cookie) token has no changeNumber interpretation.
 */
class SyncChangelogCursorTest {

    @Test
    void dsee_roundTripsChangeNumberThroughToken() {
        String token = SyncChangelogCursor.fromChangeNumber(42L);
        assertThat(token).isEqualTo("42");
        assertThat(SyncChangelogCursor.toChangeNumber(token)).isEqualTo(42L);
    }

    @Test
    void nullOrBlankToken_isFromTheStart() {
        assertThat(SyncChangelogCursor.toChangeNumber(null)).isNull();
        assertThat(SyncChangelogCursor.toChangeNumber("  ")).isNull();
    }

    @Test
    void cookieToken_hasNoChangeNumberInterpretation() {
        // A DirSync / syncrepl / Entra cookie is opaque to the numeric family.
        assertThat(SyncChangelogCursor.toChangeNumber("AAAAFooCookie==")).isNull();
    }
}
