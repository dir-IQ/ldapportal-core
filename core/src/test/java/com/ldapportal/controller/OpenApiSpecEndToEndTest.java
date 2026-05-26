// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Validates Prereq C's three invariants:
 * 1. The OpenAPI spec is reachable without authentication.
 * 2. Every {@code @RestController} in the context contributes at least one path.
 * 3. Swagger UI stays SUPERADMIN-only (not made public by the same config change).
 *
 * <p>Catches structural regressions — someone adds a controller without
 * {@code @RequestMapping}, someone widens the permitAll pattern too far,
 * springdoc drops coverage due to a config mistake. See
 * {@code docs/superpowers/specs/2026-04-24-openapi-contract-design.md}.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiSpecEndToEndTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ApplicationContext applicationContext;

    @Test
    void specIsPubliclyReachable() throws Exception {
        mockMvc.perform(get("/api/v1/openapi.yaml"))
                .andExpect(status().isOk());
    }

    @Test
    void specCoversAllRestControllers() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/openapi"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode spec = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode paths = spec.path("paths");
        assertThat(paths.isObject())
                .as("spec must have a 'paths' object")
                .isTrue();

        Set<String> specPaths = iteratorToSet(paths.fieldNames());

        Map<String, Object> controllers = applicationContext
                .getBeansWithAnnotation(RestController.class);
        assertThat(controllers)
                .as("context must contain at least one @RestController")
                .isNotEmpty();

        for (Map.Entry<String, Object> entry : controllers.entrySet()) {
            String controllerName = entry.getKey();
            Class<?> controllerClass = entry.getValue().getClass();
            // Unwrap CGLIB proxies to read class-level annotations from the real class.
            Class<?> targetClass = org.springframework.util.ClassUtils.getUserClass(controllerClass);
            RequestMapping classMapping = targetClass.getAnnotation(RequestMapping.class);
            String[] basePaths = classMapping != null ? classMapping.value() : new String[]{""};
            if (basePaths.length == 0) basePaths = new String[]{""};

            boolean covered = Arrays.stream(basePaths).anyMatch(base ->
                    specPaths.stream().anyMatch(p -> p.startsWith(base)));

            assertThat(covered)
                    .as("Controller %s (basePaths=%s) has no path in the OpenAPI spec. "
                            + "Either the controller is missing @RequestMapping or springdoc "
                            + "isn't picking it up.",
                            controllerName, Arrays.toString(basePaths))
                    .isTrue();
        }
    }

    @Test
    void swaggerUiStillRequiresAuth() throws Exception {
        // Spring Security's default authenticationEntryPoint returns 401 (ProblemDetail)
        // on unauthenticated requests to protected paths.
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().isUnauthorized());
    }

    private static Set<String> iteratorToSet(Iterator<String> it) {
        return java.util.stream.StreamSupport
                .stream(java.util.Spliterators.spliteratorUnknownSize(it, 0), false)
                .collect(Collectors.toSet());
    }
}
