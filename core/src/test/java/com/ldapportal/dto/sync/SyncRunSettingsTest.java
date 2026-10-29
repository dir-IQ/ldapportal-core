// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SyncRunSettingsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readsARunSavedWithTheFormerDeletePolicy() throws Exception {
        assertThat(mapper.readValue("{\"deletePolicy\":\"REVIEW\"}", SyncRunSettings.class).holdForReview())
                .isEqualTo("DELETES");
        assertThat(mapper.readValue("{\"deletePolicy\":\"DELETE\"}", SyncRunSettings.class).holdForReview())
                .isEqualTo("NONE");
    }

    @Test
    void readsHoldForReview() throws Exception {
        assertThat(mapper.readValue("{\"holdForReview\":\"ALL\"}", SyncRunSettings.class).holdForReview())
                .isEqualTo("ALL");
    }
}
