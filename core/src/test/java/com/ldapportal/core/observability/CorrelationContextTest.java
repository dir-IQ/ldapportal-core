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
    void currentOrEphemeral_returnsActiveScopeId_whenPresent() {
        UUID scope = UUID.randomUUID();
        CorrelationContext.set(scope);
        assertThat(CorrelationContext.currentOrEphemeral()).isEqualTo(scope);
    }

    @Test
    void currentOrEphemeral_mintsWithoutInstalling_whenNoScope() {
        // Must NOT write the ThreadLocal — otherwise a pooled scheduler/async
        // thread with no CorrelationFilter to clear it would leak the minted
        // id into the next unrelated task on that thread.
        UUID first = CorrelationContext.currentOrEphemeral();
        assertThat(first).isNotNull();
        assertThat(CorrelationContext.current()).isEmpty();
        // A second call mints a different id (nothing was installed).
        assertThat(CorrelationContext.currentOrEphemeral()).isNotEqualTo(first);
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
