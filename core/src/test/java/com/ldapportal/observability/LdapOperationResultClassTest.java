// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.observability;

import com.unboundid.ldap.sdk.ResultCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@link LdapOperationMetrics#resultClass} mapping — the bounded set of
 * label values the operation timer's {@code result} tag can take. Keeping this
 * exhaustive guards the cardinality contract: a stray new class would show up
 * here first.
 */
class LdapOperationResultClassTest {

    @Test
    void success_codes_collapse_to_success() {
        assertThat(LdapOperationMetrics.resultClass(ResultCode.SUCCESS)).isEqualTo("success");
        // compare(true/false) are successful operations, not errors.
        assertThat(LdapOperationMetrics.resultClass(ResultCode.COMPARE_TRUE)).isEqualTo("success");
        assertThat(LdapOperationMetrics.resultClass(ResultCode.COMPARE_FALSE)).isEqualTo("success");
    }

    @Test
    void failure_codes_map_to_their_class() {
        assertThat(LdapOperationMetrics.resultClass(ResultCode.NO_SUCH_OBJECT)).isEqualTo("not_found");
        assertThat(LdapOperationMetrics.resultClass(ResultCode.TIME_LIMIT_EXCEEDED)).isEqualTo("timeout");
        assertThat(LdapOperationMetrics.resultClass(ResultCode.SIZE_LIMIT_EXCEEDED)).isEqualTo("limit_exceeded");
        assertThat(LdapOperationMetrics.resultClass(ResultCode.INVALID_CREDENTIALS)).isEqualTo("auth");
        assertThat(LdapOperationMetrics.resultClass(ResultCode.INSUFFICIENT_ACCESS_RIGHTS)).isEqualTo("auth");
        assertThat(LdapOperationMetrics.resultClass(ResultCode.CONSTRAINT_VIOLATION)).isEqualTo("invalid");
        assertThat(LdapOperationMetrics.resultClass(ResultCode.ENTRY_ALREADY_EXISTS)).isEqualTo("invalid");
    }

    @Test
    void connection_unusable_codes_map_to_unavailable() {
        assertThat(LdapOperationMetrics.resultClass(ResultCode.SERVER_DOWN)).isEqualTo("unavailable");
        assertThat(LdapOperationMetrics.resultClass(ResultCode.CONNECT_ERROR)).isEqualTo("unavailable");
        // ResultCode.OTHER reports connection-unusable in the SDK, so it rides the
        // same bucket as LdapConnectionFactory's connection-broken (502) path —
        // the metric's "unavailable" lines up with the user-facing failure.
        assertThat(LdapOperationMetrics.resultClass(ResultCode.OTHER)).isEqualTo("unavailable");
    }

    @Test
    void null_falls_back_to_other() {
        assertThat(LdapOperationMetrics.resultClass(null)).isEqualTo("other");
    }
}
