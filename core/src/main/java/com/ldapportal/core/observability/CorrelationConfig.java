// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.observability;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;

import java.util.UUID;

/**
 * Propagates the {@link CorrelationContext} across {@code @Async}
 * boundaries. Spring Boot applies a single {@link TaskDecorator} bean to
 * the auto-configured task executor that {@code @Async} uses by default,
 * so the audit write — which runs asynchronously on a worker thread —
 * inherits the correlation id of the request (or scheduler tick) that
 * triggered it. Without this the async hop would lose the ThreadLocal and
 * audit rows would carry a fresh, unrelated id.
 */
@Configuration
public class CorrelationConfig {

    @Bean
    public TaskDecorator correlationTaskDecorator() {
        return runnable -> {
            UUID captured = CorrelationContext.current().orElse(null);
            if (captured == null) {
                return runnable;
            }
            return () -> CorrelationContext.withCorrelation(captured, runnable);
        };
    }
}
