// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.auth;

import com.ldapportal.entity.enums.SuperadminPermission;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Architecture guard for the superadmin view / manage split. Every REST
 * controller mounted under {@code /api/v1/superadmin/**} must resolve a
 * {@link SuperadminPermission} for each endpoint (class- or method-level
 * {@link RequiresSuperadminPermission}), and every write endpoint must resolve
 * to a {@code MANAGE_*} key — a class-level {@code VIEW_*} key alone would let
 * a view-only superadmin through to the write.
 *
 * <p>Directory <em>data</em> endpoints (the superadmin browse controller) are
 * out of scope: they gate on the directory-scoped feature model, not on
 * system-scoped superadmin permissions.</p>
 */
class SuperadminControllerPermissionCoverageTest {

    private static final Set<String> DIRECTORY_DATA_CONTROLLERS = Set.of(
            "com.ldapportal.controller.superadmin.BrowseController");

    private static final List<Class<? extends Annotation>> WRITE_MAPPINGS =
            List.of(PostMapping.class, PutMapping.class, DeleteMapping.class, PatchMapping.class);

    @Test
    void everySuperadminEndpoint_resolvesAPermission_andWritesResolveToManage() {
        List<String> problems = new ArrayList<>();
        for (Class<?> controller : superadminControllers()) {
            RequiresSuperadminPermission onClass = controller.getAnnotation(RequiresSuperadminPermission.class);
            for (Method m : controller.getDeclaredMethods()) {
                if (!isEndpoint(m)) continue;
                RequiresSuperadminPermission onMethod = m.getAnnotation(RequiresSuperadminPermission.class);
                SuperadminPermission resolved = onMethod != null ? onMethod.value()
                        : onClass != null ? onClass.value() : null;
                String where = controller.getSimpleName() + "#" + m.getName();
                if (resolved == null) {
                    problems.add(where + " has no @RequiresSuperadminPermission (class or method)");
                } else if (isWrite(m) && resolved.isViewTier()) {
                    problems.add(where + " is a write but resolves to view-tier " + resolved);
                }
            }
        }
        assertThat(problems).isEmpty();
    }

    private static List<Class<?>> superadminControllers() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Class<?>> result = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.ldapportal")) {
            String name = bd.getBeanClassName();
            if (name == null || DIRECTORY_DATA_CONTROLLERS.contains(name)) continue;
            Class<?> c;
            try {
                c = Class.forName(name);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
            // Nested classes are test-local stubs (e.g. the fixtures in
            // AuthAnnotationValidatorTest), not real controllers.
            if (c.getEnclosingClass() != null) continue;
            RequestMapping rm = AnnotatedElementUtils.findMergedAnnotation(c, RequestMapping.class);
            if (rm == null) continue;
            if (Arrays.stream(rm.value()).anyMatch(p -> p.startsWith("/api/v1/superadmin"))) {
                result.add(c);
            }
        }
        assertThat(result).as("superadmin controllers found on the classpath").isNotEmpty();
        return result;
    }

    private static boolean isEndpoint(Method m) {
        return m.isAnnotationPresent(GetMapping.class) || m.isAnnotationPresent(RequestMapping.class)
                || isWrite(m);
    }

    private static boolean isWrite(Method m) {
        return WRITE_MAPPINGS.stream().anyMatch(m::isAnnotationPresent);
    }
}
