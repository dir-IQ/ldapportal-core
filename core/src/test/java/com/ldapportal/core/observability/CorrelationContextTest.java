// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationContextTest {

    @AfterEach
    void cleanup() {
        CorrelationContext.clear();
    }

    @Test
    void current_isEmpty_whenNoScope() {
        assertThat(CorrelationContext.current()).isEmpty();
    }

    @Test
    void currentOrGenerate_mintsAndInstalls() {
        UUID id = CorrelationContext.currentOrGenerate();
        assertThat(id).isNotNull();
        // A second call returns the same installed id (not a fresh one).
        assertThat(CorrelationContext.currentOrGenerate()).isEqualTo(id);
        assertThat(CorrelationContext.current()).contains(id);
    }

    @Test
    void withCorrelation_restoresPreviousScopeOnExit() {
        UUID outer = UUID.randomUUID();
        UUID inner = UUID.randomUUID();
        CorrelationContext.set(outer);

        CorrelationContext.withCorrelation(inner, () -> {
            assertThat(CorrelationContext.current()).contains(inner);
        });

        // Nesting-safe: the outer scope is restored, not cleared.
        assertThat(CorrelationContext.current()).contains(outer);
    }

    @Test
    void withCorrelation_clearsWhenNoPreviousScope() {
        UUID id = UUID.randomUUID();

        String result = CorrelationContext.withCorrelation(id, () -> {
            assertThat(CorrelationContext.current()).contains(id);
            return "done";
        });

        assertThat(result).isEqualTo("done");
        assertThat(CorrelationContext.current()).isEmpty();
    }
}
