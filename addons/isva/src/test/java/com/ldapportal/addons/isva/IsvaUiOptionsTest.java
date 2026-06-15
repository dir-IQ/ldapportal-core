// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva;

import com.ldapportal.addons.isva.entity.IsvaTopologyMode;
import org.junit.jupiter.api.Test;

import static com.ldapportal.addons.isva.entity.IsvaTopologyMode.INLINE;
import static com.ldapportal.addons.isva.entity.IsvaTopologyMode.LINKED;
import static org.assertj.core.api.Assertions.assertThat;

class IsvaUiOptionsTest {

    @Test
    void defaultIsLinked() {
        assertThat(IsvaUiOptions.parse("linked")).containsExactly(LINKED);
    }

    @Test
    void inlineOnly() {
        assertThat(IsvaUiOptions.parse("inline")).containsExactly(INLINE);
    }

    @Test
    void bothModes_canonicalOrder_regardlessOfInput() {
        assertThat(IsvaUiOptions.parse("inline,linked")).containsExactly(INLINE, LINKED);
        assertThat(IsvaUiOptions.parse("linked,inline")).containsExactly(INLINE, LINKED);
    }

    @Test
    void caseInsensitive_andTrimsWhitespace() {
        assertThat(IsvaUiOptions.parse("  LINKED , Inline ")).containsExactly(INLINE, LINKED);
    }

    @Test
    void unknownTokensIgnored_blankFallsBackToLinked() {
        assertThat(IsvaUiOptions.parse("bogus,inline")).containsExactly(INLINE);
        assertThat(IsvaUiOptions.parse("")).containsExactly(LINKED);
        assertThat(IsvaUiOptions.parse("   ")).containsExactly(LINKED);
        assertThat(IsvaUiOptions.parse(null)).containsExactly(LINKED);
        assertThat(IsvaUiOptions.parse("nonsense")).containsExactly(LINKED);
    }

    @Test
    void deduplicates() {
        assertThat(IsvaUiOptions.parse("linked,linked")).containsExactly(LINKED);
    }

    @Test
    void neverReturnsEmpty() {
        for (String raw : new String[]{null, "", " , ", "x,y,z"}) {
            assertThat(IsvaUiOptions.parse(raw)).isNotEmpty();
        }
        // sanity: a real value still parses
        assertThat(IsvaUiOptions.parse("inline")).contains(IsvaTopologyMode.INLINE);
    }
}
