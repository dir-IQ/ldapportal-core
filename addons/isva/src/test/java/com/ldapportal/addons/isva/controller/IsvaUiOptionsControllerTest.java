// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.controller;

import com.ldapportal.addons.isva.IsvaUiOptions;
import com.ldapportal.addons.isva.entity.IsvaTopologyMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IsvaUiOptionsControllerTest {

    private IsvaUiOptionsController controller(String exposed) {
        IsvaUiOptions opts = new IsvaUiOptions();
        opts.setExposedTopologyModes(exposed);
        return new IsvaUiOptionsController(opts);
    }

    @Test
    void returnsBothModesInCanonicalOrder() {
        var body = controller("linked,inline").get().getBody();
        assertThat(body).isNotNull();
        assertThat(body.exposedTopologyModes())
                .containsExactly(IsvaTopologyMode.INLINE, IsvaTopologyMode.LINKED);
    }

    @Test
    void defaultIsLinkedOnly() {
        var body = controller("linked").get().getBody();
        assertThat(body).isNotNull();
        assertThat(body.exposedTopologyModes()).containsExactly(IsvaTopologyMode.LINKED);
    }
}
