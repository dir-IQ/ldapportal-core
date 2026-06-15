// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Startup-time guard: every handler method under
 * {@code /api/v1/directories/**} must carry either {@link RequiresFeature}
 * or {@link PreAuthorize} at the method level, or its declaring class must
 * carry one at the class level. Endpoints under that prefix touch a
 * specific directory and would otherwise silently fall through to the
 * {@code SecurityConfig} catch-all that admits any {@code ADMIN} or
 * {@code SUPERADMIN}, defeating per-feature gates.
 *
 * <p>If any directory-scoped endpoint is missing both annotations, fail
 * fast on startup with an explicit list of the offenders. This makes the
 * "forgot to annotate" mistake impossible to ship — the bad build will
 * not boot.</p>
 *
 * <p>Fires on {@link ApplicationReadyEvent} so the
 * {@link RequestMappingHandlerMapping} bean is fully populated. Runs once
 * per JVM (per context refresh in tests).</p>
 */
@Component
@Slf4j
public class AuthAnnotationValidator implements ApplicationListener<ApplicationReadyEvent> {

    static final String GUARDED_PREFIX = "/api/v1/directories/";

    private final RequestMappingHandlerMapping handlerMapping;

    public AuthAnnotationValidator(
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping) {
        // Qualifier is needed because Spring Boot Actuator registers a second
        // RequestMappingHandlerMapping bean ("controllerEndpointHandlerMapping")
        // for actuator endpoints. We only want the main MVC mapping that
        // carries the application's controllers.
        this.handlerMapping = handlerMapping;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        List<String> offenders = findOffenders();
        if (!offenders.isEmpty()) {
            throw new IllegalStateException(
                    "Startup guard failed: " + offenders.size()
                    + " endpoint(s) under " + GUARDED_PREFIX
                    + "** lack both @RequiresFeature and @PreAuthorize. "
                    + "Annotate them or move them outside the directory prefix. Offenders:\n  - "
                    + String.join("\n  - ", offenders));
        }
        log.info("AuthAnnotationValidator: all directory endpoints under {}** carry @RequiresFeature or @PreAuthorize",
                GUARDED_PREFIX);
    }

    List<String> findOffenders() {
        List<String> offenders = new ArrayList<>();
        var map = handlerMapping.getHandlerMethods();
        for (var entry : map.entrySet()) {
            HandlerMethod handler = entry.getValue();
            Set<String> patterns = entry.getKey().getDirectPaths();
            // Some mappings use path patterns rather than direct paths; check both.
            if (patterns.isEmpty()) {
                patterns = entry.getKey().getPatternValues();
            }
            boolean guardedRoute = patterns.stream().anyMatch(p -> p != null && p.startsWith(GUARDED_PREFIX));
            if (!guardedRoute) continue;
            if (isAnnotated(handler)) continue;

            Method method = handler.getMethod();
            offenders.add(method.getDeclaringClass().getSimpleName() + "#" + method.getName()
                    + " [" + String.join(",", patterns) + "]");
        }
        return offenders;
    }

    private boolean isAnnotated(HandlerMethod handler) {
        Method method = handler.getMethod();
        Class<?> beanType = handler.getBeanType();
        return AnnotationUtils.findAnnotation(method, RequiresFeature.class) != null
                || AnnotationUtils.findAnnotation(method, PreAuthorize.class) != null
                || AnnotationUtils.findAnnotation(beanType, RequiresFeature.class) != null
                || AnnotationUtils.findAnnotation(beanType, PreAuthorize.class) != null;
    }

    /**
     * Test hook: allows tests to confirm the prefix is reachable without
     * reflecting into the validator. Keep package-private.
     */
    static boolean isGuardedPath(String path) {
        return path != null && path.startsWith(GUARDED_PREFIX);
    }
}
