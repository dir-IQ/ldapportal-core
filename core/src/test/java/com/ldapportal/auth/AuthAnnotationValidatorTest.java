// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.auth;

import com.ldapportal.entity.enums.FeatureKey;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the startup-time guard rejects unannotated handler methods
 * mapped under {@code /api/v1/directories/**} and accepts handlers that
 * carry {@link RequiresFeature} or {@link PreAuthorize}, either at the
 * method or class level.
 */
class AuthAnnotationValidatorTest {

    @Test
    void offendersFound_throwsOnReadyEvent() throws Exception {
        var mapping = mappingFor(UnannotatedController.class, "list");
        var validator = new AuthAnnotationValidator(mapping);

        List<String> offenders = validator.findOffenders();
        assertThat(offenders).hasSize(1);
        assertThat(offenders.get(0)).contains("UnannotatedController", "list");

        assertThatThrownBy(() -> validator.onApplicationEvent(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UnannotatedController")
                .hasMessageContaining("@RequiresFeature");
    }

    @Test
    void methodLevelRequiresFeature_passes() throws Exception {
        var mapping = mappingFor(MethodAnnotatedController.class, "list");
        var validator = new AuthAnnotationValidator(mapping);

        assertThat(validator.findOffenders()).isEmpty();
    }

    @Test
    void classLevelPreAuthorize_passes() throws Exception {
        var mapping = mappingFor(ClassAnnotatedController.class, "list");
        var validator = new AuthAnnotationValidator(mapping);

        assertThat(validator.findOffenders()).isEmpty();
    }

    @Test
    void endpointOutsideDirectoriesPrefix_ignored() throws Exception {
        // An unannotated endpoint outside /api/v1/directories/** must not be flagged —
        // the validator only guards the directory prefix.
        var mapping = mappingFor(SuperadminController.class, "list");
        var validator = new AuthAnnotationValidator(mapping);

        assertThat(validator.findOffenders()).isEmpty();
    }

    // ── Test fixtures ───────────────────────────────────────────────────────

    @RestController
    @RequestMapping("/api/v1/directories/{directoryId}/widgets")
    static class UnannotatedController {
        @GetMapping
        public String list() { return ""; }
    }

    @RestController
    @RequestMapping("/api/v1/directories/{directoryId}/widgets")
    static class MethodAnnotatedController {
        @GetMapping
        @RequiresFeature(FeatureKey.USER_READ)
        public String list() { return ""; }
    }

    @RestController
    @RequestMapping("/api/v1/directories/{directoryId}/widgets")
    @PreAuthorize("hasRole('SUPERADMIN')")
    static class ClassAnnotatedController {
        @GetMapping
        public String list() { return ""; }
    }

    @RestController
    @RequestMapping("/api/v1/superadmin/widgets")
    static class SuperadminController {
        @GetMapping
        public String list() { return ""; }
    }

    // ── Helper: build a RequestMappingHandlerMapping with one route ─────────

    private static RequestMappingHandlerMapping mappingFor(Class<?> controllerClass, String methodName) throws Exception {
        RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
        mapping.setApplicationContext(new org.springframework.web.context.support.StaticWebApplicationContext());
        mapping.afterPropertiesSet();

        Object bean = controllerClass.getDeclaredConstructor().newInstance();
        Method method = controllerClass.getDeclaredMethod(methodName);

        String classPath = controllerClass.getAnnotation(RequestMapping.class).value()[0];
        RequestMappingInfo info = RequestMappingInfo
                .paths(classPath)
                .methods(org.springframework.web.bind.annotation.RequestMethod.GET)
                .options(mapping.getBuilderConfiguration())
                .build();

        // Reflectively register the handler — RequestMappingHandlerMapping exposes
        // registerMapping(info, handler, method) as public via inheritance.
        mapping.registerMapping(info, bean, method);
        // sanity check: HandlerMethod resolution finds our bean
        HandlerMethod handler = (HandlerMethod) mapping.getHandlerMethods().get(info);
        assertThat(handler).isNotNull();
        return mapping;
    }
}
