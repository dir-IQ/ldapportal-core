// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.web;

import com.ldapportal.exception.PreconditionFailedException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ETagSupportTest {

    @Test
    void format_rendersQuotedStrongTag() {
        assertThat(ETagSupport.format(7)).isEqualTo("\"7\"");
    }

    @Test
    void parseIfMatch_nullOrBlank_returnsNull() {
        assertThat(ETagSupport.parseIfMatch(null)).isNull();
        assertThat(ETagSupport.parseIfMatch("   ")).isNull();
    }

    @Test
    void parseIfMatch_acceptsQuotedUnquotedAndWeak() {
        assertThat(ETagSupport.parseIfMatch("\"3\"")).isEqualTo(3L);
        assertThat(ETagSupport.parseIfMatch("3")).isEqualTo(3L);
        assertThat(ETagSupport.parseIfMatch("W/\"3\"")).isEqualTo(3L);
    }

    @Test
    void parseIfMatch_malformedOrWildcard_isBadRequest() {
        assertThatThrownBy(() -> ETagSupport.parseIfMatch("\"abc\""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ETagSupport.parseIfMatch("*"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requireMatch_nullExpected_isNoOp() {
        assertThatCode(() -> ETagSupport.requireMatch(null, 4L)).doesNotThrowAnyException();
    }

    @Test
    void requireMatch_equal_isNoOp() {
        assertThatCode(() -> ETagSupport.requireMatch(4L, 4L)).doesNotThrowAnyException();
    }

    @Test
    void requireMatch_mismatch_throwsPreconditionFailed() {
        assertThatThrownBy(() -> ETagSupport.requireMatch(2L, 5L))
                .isInstanceOf(PreconditionFailedException.class)
                .hasMessageContaining("version 5");
    }
}
