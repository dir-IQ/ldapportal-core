// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "http://169.254.169.254/latest/meta-data/", // cloud metadata
            "http://localhost/admin",
            "http://127.0.0.1:8080/",
            "http://[::1]/",                             // IPv6 loopback
            "http://10.0.0.5/internal",                  // RFC1918 site-local
            "http://192.168.1.1/",
            "http://172.16.0.1/",
            "https://foo.internal/",
            "https://bar.local/",
            "http://metadata.google.internal/",
    })
    void rejectsSsrfProneDestinations(String url) {
        assertThatThrownBy(() -> UrlValidator.requireSafeUrl(url))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://example.com/file",
            "file:///etc/passwd",
            "gopher://example.com/",
            "jar:http://example.com!/",
    })
    void rejectsNonHttpSchemes(String url) {
        assertThatThrownBy(() -> UrlValidator.requireSafeUrl(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheme");
    }

    @Test
    void rejectsUrlWithoutHost() {
        assertThatThrownBy(() -> UrlValidator.requireSafeUrl("http:///path"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com/webhook",
            "http://example.org:9000/events",
            "https://hooks.slack.com/services/abc",
    })
    void allowsPublicHttpDestinations(String url) {
        assertThatCode(() -> UrlValidator.requireSafeUrl(url)).doesNotThrowAnyException();
    }

    @Test
    void allowsNullAndBlank() {
        assertThatCode(() -> UrlValidator.requireSafeUrl(null)).doesNotThrowAnyException();
        assertThatCode(() -> UrlValidator.requireSafeUrl("")).doesNotThrowAnyException();
        assertThatCode(() -> UrlValidator.requireSafeUrl("   ")).doesNotThrowAnyException();
    }
}
