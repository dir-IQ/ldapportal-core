// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PrincipalType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AttributeSyntaxController.class)
class AttributeSyntaxControllerTest extends BaseControllerTest {

    @Autowired MockMvc mockMvc;

    static final String URL = "/api/v1/attribute-syntax";

    @Test
    void returnsWellKnownAndInputTypeSyntaxMaps() throws Exception {
        mockMvc.perform(get(URL).with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wellKnownAttributes.manager").value("DN"))
                .andExpect(jsonPath("$.wellKnownAttributes.member").value("DN"))
                .andExpect(jsonPath("$.wellKnownAttributes.mail").value("EMAIL"))
                .andExpect(jsonPath("$.inputTypeSyntax.DN_LOOKUP").value("DN"))
                .andExpect(jsonPath("$.inputTypeSyntax.DN").value("DN"))
                .andExpect(jsonPath("$.inputTypeSyntax.BOOLEAN").value("BOOLEAN"));
    }

    @Test
    void plainTextInputTypeHasNoSyntaxEntry() throws Exception {
        // Only input types that imply a value shape are listed.
        mockMvc.perform(get(URL).with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inputTypeSyntax.TEXT").doesNotExist());
    }

    @Test
    void unauthenticated_returns401() throws Exception {
        mockMvc.perform(get(URL))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void selfServiceRole_returns403() throws Exception {
        // It is admin-form metadata — the global /api/v1/** rule requires ADMIN/SUPERADMIN.
        AuthPrincipal p = new AuthPrincipal(PrincipalType.SELF_SERVICE, UUID.randomUUID(), "user");
        var selfService = new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_SELF_SERVICE")));

        mockMvc.perform(get(URL).with(authentication(selfService)))
                .andExpect(status().isForbidden());
    }
}
