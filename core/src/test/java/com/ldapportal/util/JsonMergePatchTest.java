// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JsonMergePatchTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode merge(String target, String patch) throws Exception {
        return JsonMergePatch.apply(mapper.readTree(target), mapper.readTree(patch));
    }

    @Test
    void addsNewKeysWithoutTouchingExisting() throws Exception {
        JsonNode out = merge(
                "{\"appearance\":{\"theme\":\"dark\"}}",
                "{\"sidebar\":{\"collapsed\":true}}");
        assertThat(out.path("appearance").path("theme").asText()).isEqualTo("dark");
        assertThat(out.path("sidebar").path("collapsed").asBoolean()).isTrue();
    }

    @Test
    void mergesNestedObjectsRecursively() throws Exception {
        JsonNode out = merge(
                "{\"appearance\":{\"theme\":\"dark\",\"density\":\"compact\"}}",
                "{\"appearance\":{\"theme\":\"light\"}}");
        // theme replaced, density preserved
        assertThat(out.path("appearance").path("theme").asText()).isEqualTo("light");
        assertThat(out.path("appearance").path("density").asText()).isEqualTo("compact");
    }

    @Test
    void nullValueDeletesKey() throws Exception {
        JsonNode out = merge(
                "{\"appearance\":{\"theme\":\"dark\",\"density\":\"compact\"}}",
                "{\"appearance\":{\"density\":null}}");
        assertThat(out.path("appearance").has("density")).isFalse();
        assertThat(out.path("appearance").path("theme").asText()).isEqualTo("dark");
    }

    @Test
    void scalarReplacesObject() throws Exception {
        JsonNode out = merge(
                "{\"sidebar\":{\"collapsed\":true}}",
                "{\"sidebar\":\"x\"}");
        assertThat(out.path("sidebar").isTextual()).isTrue();
    }

    @Test
    void objectReplacesScalarOnTarget() throws Exception {
        JsonNode out = merge(
                "{\"tables\":1}",
                "{\"tables\":{\"audit\":{\"pageSize\":50}}}");
        assertThat(out.path("tables").path("audit").path("pageSize").asInt()).isEqualTo(50);
    }

    @Test
    void mergeIntoEmptyTargetYieldsPatch() throws Exception {
        JsonNode out = merge("{}", "{\"appearance\":{\"theme\":\"dark\"}}");
        assertThat(out.path("appearance").path("theme").asText()).isEqualTo("dark");
    }
}
