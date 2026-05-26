// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthContextHelperTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void returnsEmptyWhenNoAuthentication() {
        assertThat(AuthContextHelper.currentApiToken()).isEmpty();
    }

    @Test
    void returnsEmptyForJwtAuthenticationWithoutDetails() {
        AuthPrincipal p = new AuthPrincipal(PrincipalType.SUPERADMIN, UUID.randomUUID(), "alice");
        var auth = new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_SUPERADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertThat(AuthContextHelper.currentApiToken()).isEmpty();
    }

    @Test
    void returnsDetailsWhenAuthenticationCarriesApiTokenDetails() {
        UUID tokenId = UUID.randomUUID();
        AuthPrincipal p = new AuthPrincipal(PrincipalType.SUPERADMIN, UUID.randomUUID(), "alice");
        var auth = new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_SUPERADMIN")));
        auth.setDetails(new ApiTokenAuthenticationDetails(tokenId, "ci-terraform"));
        SecurityContextHolder.getContext().setAuthentication(auth);

        Optional<ApiTokenAuthenticationDetails> result = AuthContextHelper.currentApiToken();
        assertThat(result).isPresent();
        assertThat(result.get().tokenId()).isEqualTo(tokenId);
        assertThat(result.get().tokenName()).isEqualTo("ci-terraform");
    }
}
