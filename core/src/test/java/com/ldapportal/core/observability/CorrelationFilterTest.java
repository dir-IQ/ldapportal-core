// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.observability;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationFilterTest {

    private final CorrelationFilter filter = new CorrelationFilter();

    @AfterEach
    void cleanup() {
        CorrelationContext.clear();
    }

    @Test
    void honoursClientSuppliedHeader_andEchoesIt() throws Exception {
        UUID supplied = UUID.randomUUID();
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Correlation-Id", supplied.toString());
        MockHttpServletResponse res = new MockHttpServletResponse();

        AtomicReference<UUID> seenInScope = new AtomicReference<>();
        FilterChain chain = (rq, rs) ->
                seenInScope.set(CorrelationContext.current().orElse(null));

        filter.doFilter(req, res, chain);

        // The client's id is used inside the request scope and echoed back.
        assertThat(seenInScope.get()).isEqualTo(supplied);
        assertThat(res.getHeader("X-Correlation-Id")).isEqualTo(supplied.toString());
        // ThreadLocal is cleared after the request so the pooled thread leaks nothing.
        assertThat(CorrelationContext.current()).isEmpty();
    }

    @Test
    void generatesId_whenHeaderAbsent() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();

        AtomicReference<UUID> seenInScope = new AtomicReference<>();
        FilterChain chain = (rq, rs) ->
                seenInScope.set(CorrelationContext.current().orElse(null));

        filter.doFilter(req, res, chain);

        assertThat(seenInScope.get()).isNotNull();
        assertThat(res.getHeader("X-Correlation-Id")).isEqualTo(seenInScope.get().toString());
    }

    @Test
    void generatesId_whenHeaderMalformed() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Correlation-Id", "not-a-uuid");
        MockHttpServletResponse res = new MockHttpServletResponse();

        AtomicReference<UUID> seenInScope = new AtomicReference<>();
        FilterChain chain = (rq, rs) ->
                seenInScope.set(CorrelationContext.current().orElse(null));

        filter.doFilter(req, res, chain);

        // A malformed trace header is replaced, not rejected.
        assertThat(seenInScope.get()).isNotNull();
        assertThat(res.getHeader("X-Correlation-Id")).isEqualTo(seenInScope.get().toString());
    }
}
