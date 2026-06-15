// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Fails application startup if any handler method inside a
 * {@code /api/v1/directories/{directoryId}/} controller is missing an explicit
 * authorization annotation ({@link RequiresFeature} or {@link PreAuthorize}).
 *
 * <p>This prevents new endpoints from accidentally relying on the HTTP-level
 * catch-all filter as their only authorization gate.</p>
 */
@Component
@Slf4j
public class AuthorizationAnnotationValidator {

    private static final String DIRECTORY_PATH_SEGMENT = "/directories/{directoryId}";

    /**
     * Path prefixes that are exempt from the annotation requirement because they
     * are public or use a different authorization model (e.g. self-service JWT).
     */
    private static final Set<String> EXEMPT_PREFIXES = Set.of(
            "/api/v1/self-service/",
            "/api/v1/auth/",
            "/api/v1/settings/"
    );

    @EventListener
    public void validate(ContextRefreshedEvent event) {
        Map<String, Object> controllers = event.getApplicationContext()
                .getBeansWithAnnotation(RestController.class);

        List<String> violations = new ArrayList<>();

        for (Object controller : controllers.values()) {
            Class<?> clazz = controller.getClass();
            // unwrap CGLIB proxy
            if (clazz.getName().contains("$$")) {
                clazz = clazz.getSuperclass();
            }

            if (hasClassLevelPreAuthorize(clazz)) {
                continue; // class-level @PreAuthorize covers all methods
            }

            String classPrefix = classLevelPath(clazz);

            for (Method method : clazz.getDeclaredMethods()) {
                if (method.isSynthetic() || method.isBridge()) continue;

                String fullPath = classPrefix + methodLevelPath(method);

                if (!fullPath.contains(DIRECTORY_PATH_SEGMENT)) continue;
                if (isExempt(fullPath)) continue;
                if (!isHandlerMethod(method)) continue;

                boolean hasAuth = method.isAnnotationPresent(RequiresFeature.class)
                        || method.isAnnotationPresent(PreAuthorize.class);

                if (!hasAuth) {
                    violations.add(clazz.getSimpleName() + "." + method.getName()
                            + "() — " + fullPath);
                }
            }
        }

        if (!violations.isEmpty()) {
            String msg = "Directory-scoped endpoints missing authorization annotation:\n  - "
                    + String.join("\n  - ", violations);
            log.error(msg);
            throw new IllegalStateException(msg);
        }

        log.info("Authorization annotation validation passed — all directory-scoped endpoints have explicit authorization");
    }

    private boolean hasClassLevelPreAuthorize(Class<?> clazz) {
        return clazz.isAnnotationPresent(PreAuthorize.class);
    }

    private String classLevelPath(Class<?> clazz) {
        RequestMapping rm = clazz.getAnnotation(RequestMapping.class);
        if (rm == null) return "";
        String[] paths = rm.value().length > 0 ? rm.value() : rm.path();
        return paths.length > 0 ? paths[0] : "";
    }

    private String methodLevelPath(Method method) {
        return Stream.of(
                method.getAnnotationsByType(org.springframework.web.bind.annotation.GetMapping.class),
                method.getAnnotationsByType(org.springframework.web.bind.annotation.PostMapping.class),
                method.getAnnotationsByType(org.springframework.web.bind.annotation.PutMapping.class),
                method.getAnnotationsByType(org.springframework.web.bind.annotation.DeleteMapping.class),
                method.getAnnotationsByType(org.springframework.web.bind.annotation.PatchMapping.class)
        ).flatMap(Stream::of).findFirst().map(a -> {
            try {
                String[] val = (String[]) a.getClass().getMethod("value").invoke(a);
                return val.length > 0 ? val[0] : "";
            } catch (Exception e) {
                return "";
            }
        }).orElse("");
    }

    private boolean isHandlerMethod(Method method) {
        return method.isAnnotationPresent(org.springframework.web.bind.annotation.GetMapping.class)
                || method.isAnnotationPresent(org.springframework.web.bind.annotation.PostMapping.class)
                || method.isAnnotationPresent(org.springframework.web.bind.annotation.PutMapping.class)
                || method.isAnnotationPresent(org.springframework.web.bind.annotation.DeleteMapping.class)
                || method.isAnnotationPresent(org.springframework.web.bind.annotation.PatchMapping.class)
                || method.isAnnotationPresent(org.springframework.web.bind.annotation.RequestMapping.class);
    }

    private boolean isExempt(String path) {
        return EXEMPT_PREFIXES.stream().anyMatch(path::startsWith);
    }
}
