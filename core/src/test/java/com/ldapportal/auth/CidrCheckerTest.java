// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CidrCheckerTest {

    @Test
    void empty_and_null_never_match() {
        assertThat(CidrChecker.parse(null).contains("10.0.0.1")).isFalse();
        assertThat(CidrChecker.parse("").contains("10.0.0.1")).isFalse();
        assertThat(CidrChecker.parse("   \n\n  ").contains("10.0.0.1")).isFalse();
    }

    @Test
    void ipv4_exact_match_via_implicit_slash_32() {
        CidrChecker c = CidrChecker.parse("192.168.1.42");
        assertThat(c.contains("192.168.1.42")).isTrue();
        assertThat(c.contains("192.168.1.43")).isFalse();
    }

    @Test
    void ipv4_slash_24_matches_whole_subnet() {
        CidrChecker c = CidrChecker.parse("10.1.2.0/24");
        assertThat(c.contains("10.1.2.0")).isTrue();
        assertThat(c.contains("10.1.2.1")).isTrue();
        assertThat(c.contains("10.1.2.255")).isTrue();
        assertThat(c.contains("10.1.3.0")).isFalse();
    }

    @Test
    void ipv4_network_bits_in_input_are_masked() {
        // 10.0.0.7/24 should behave as 10.0.0.0/24.
        CidrChecker c = CidrChecker.parse("10.0.0.7/24");
        assertThat(c.contains("10.0.0.1")).isTrue();
        assertThat(c.contains("10.0.1.1")).isFalse();
    }

    @Test
    void ipv6_match() {
        CidrChecker c = CidrChecker.parse("2001:db8::/32");
        assertThat(c.contains("2001:db8::1")).isTrue();
        assertThat(c.contains("2001:db8:abcd::1")).isTrue();
        assertThat(c.contains("2001:db9::1")).isFalse();
    }

    @Test
    void ipv4_and_ipv6_ranges_do_not_cross_match() {
        // An IPv4 range should not match a pure IPv6 literal, and vice versa.
        // (Note: ::ffff:x.y.z.w resolves to the 4-byte IPv4 form in Java, so
        // we pick a literal IPv6 address that has no IPv4-mapped equivalent.)
        CidrChecker v4 = CidrChecker.parse("10.0.0.0/8");
        assertThat(v4.contains("2001:db8::1")).isFalse();

        CidrChecker v6 = CidrChecker.parse("2001:db8::/32");
        assertThat(v6.contains("10.1.2.3")).isFalse();
    }

    @Test
    void multiple_ranges_all_consulted() {
        CidrChecker c = CidrChecker.parse("""
                10.0.0.0/8
                # a comment line is ignored
                172.16.0.0/12
                192.168.1.0/24
                """);
        assertThat(c.contains("10.1.2.3")).isTrue();
        assertThat(c.contains("172.20.0.1")).isTrue();
        assertThat(c.contains("192.168.1.42")).isTrue();
        assertThat(c.contains("8.8.8.8")).isFalse();
    }

    @Test
    void malformed_lines_are_skipped_not_fatal() {
        CidrChecker c = CidrChecker.parse("""
                10.0.0.0/8
                not-an-ip
                10.0.0.0/99
                172.16.0.0/12
                """);
        assertThat(c.contains("10.1.2.3")).isTrue();
        assertThat(c.contains("172.20.0.1")).isTrue();
    }

    @Test
    void blank_query_never_matches() {
        CidrChecker c = CidrChecker.parse("10.0.0.0/8");
        assertThat(c.contains(null)).isFalse();
        assertThat(c.contains("")).isFalse();
        assertThat(c.contains("   ")).isFalse();
    }

    @Test
    void isEmpty_reflects_parse_result() {
        assertThat(CidrChecker.parse(null).isEmpty()).isTrue();
        assertThat(CidrChecker.parse("10.0.0.0/8").isEmpty()).isFalse();
    }
}
