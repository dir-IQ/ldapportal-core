// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class AppPropertiesPublicBasePathTest {

    @ParameterizedTest(name = "\"{0}\" normalises to \"{1}\"")
    @CsvSource(value = {
            "'',''",
            "'   ',''",
            "/,''",
            "//,''",
            "idm,/idm",
            "/idm,/idm",
            "/idm/,/idm",
            " /idm/ ,/idm",
            "//idm//,/idm",
            "/idm/portal/,/idm/portal",
    })
    void normalisesLeniently(String raw, String expected) {
        assertThat(AppProperties.normalizeBasePath(raw)).isEqualTo(expected);
    }

    @Test
    void nullNormalisesToRoot() {
        assertThat(AppProperties.normalizeBasePath(null)).isEmpty();
    }

    @Test
    void publicPathIsIdentityAtTheRoot() {
        AppProperties props = new AppProperties();
        assertThat(props.publicPath("/api/v1")).isEqualTo("/api/v1");
        assertThat(props.publicPath("/")).isEqualTo("/");
        assertThat(props.publicPath("/oidc/callback")).isEqualTo("/oidc/callback");
    }

    @Test
    void publicPathPrefixesUnderABasePath() {
        AppProperties props = new AppProperties();
        props.setPublicBasePath("/idm/");
        assertThat(props.getPublicBasePath()).isEqualTo("/idm");
        assertThat(props.publicPath("/api/v1")).isEqualTo("/idm/api/v1");
        assertThat(props.publicPath("api/v1")).isEqualTo("/idm/api/v1");
        // The app root keeps its trailing slash so a Path=/idm/ cookie covers the SPA.
        assertThat(props.publicPath("/")).isEqualTo("/idm/");
        assertThat(props.publicPath("/oidc/callback")).isEqualTo("/idm/oidc/callback");
    }
}
