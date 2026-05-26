// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Scans all controller classes under {@code com.ldapportal.controller} and
 * generates a feature permission matrix as a markdown table. Also asserts that
 * every directory-scoped endpoint ({@code /directories/&lbrace;directoryId&rbrace;})
 * has an explicit authorization annotation.
 *
 * <p>The generated matrix is written to {@code target/feature-permission-matrix.md}.</p>
 */
class FeaturePermissionMatrixTest {

    private static final String CONTROLLER_PACKAGE = "com.ldapportal.controller";
    private static final String DIRECTORY_PATH_SEGMENT = "/directories/{directoryId}";
    private static final Set<String> EXEMPT_PREFIXES = Set.of(
            "/api/v1/self-service/",
            "/api/v1/auth/",
            "/api/v1/settings/"
    );

    @Test
    void generatePermissionMatrix() throws Exception {
        List<Class<?>> controllers = findControllerClasses();
        List<EndpointEntry> entries = new ArrayList<>();
        List<String> violations = new ArrayList<>();

        for (Class<?> clazz : controllers) {
            boolean classPreAuth = clazz.isAnnotationPresent(PreAuthorize.class);
            String classPreAuthValue = classPreAuth
                    ? clazz.getAnnotation(PreAuthorize.class).value() : null;
            String classPath = classLevelPath(clazz);

            for (Method method : clazz.getDeclaredMethods()) {
                MethodMapping mm = extractMapping(method);
                if (mm == null) continue;

                String fullPath = classPath + mm.path;
                String authMechanism;
                String authDetail;

                if (method.isAnnotationPresent(RequiresFeature.class)) {
                    authMechanism = "@RequiresFeature";
                    authDetail = method.getAnnotation(RequiresFeature.class).value().name();
                } else if (method.isAnnotationPresent(PreAuthorize.class)) {
                    authMechanism = "@PreAuthorize";
                    authDetail = method.getAnnotation(PreAuthorize.class).value();
                } else if (classPreAuth) {
                    authMechanism = "@PreAuthorize (class)";
                    authDetail = classPreAuthValue;
                } else {
                    authMechanism = "catch-all only";
                    authDetail = "-";

                    // Check if this is a directory-scoped endpoint missing auth
                    if (fullPath.contains(DIRECTORY_PATH_SEGMENT) && !isExempt(fullPath)) {
                        violations.add(clazz.getSimpleName() + "." + method.getName()
                                + "() — " + mm.httpMethod + " " + fullPath);
                    }
                }

                entries.add(new EndpointEntry(
                        mm.httpMethod, fullPath, clazz.getSimpleName(),
                        method.getName(), authMechanism, authDetail));
            }
        }

        entries.sort(Comparator.comparing(EndpointEntry::path)
                .thenComparing(EndpointEntry::httpMethod));

        // Write matrix
        StringBuilder md = new StringBuilder();
        md.append("# Feature Permission Matrix\n\n");
        md.append("| HTTP | Path | Controller | Method | Auth | Detail |\n");
        md.append("|------|------|-----------|--------|------|--------|\n");
        for (EndpointEntry e : entries) {
            md.append("| ").append(e.httpMethod)
              .append(" | `").append(e.path).append("`")
              .append(" | ").append(e.controller)
              .append(" | ").append(e.method)
              .append(" | ").append(e.authMechanism)
              .append(" | ").append(e.authDetail)
              .append(" |\n");
        }

        Path output = Path.of("target", "feature-permission-matrix.md");
        Files.createDirectories(output.getParent());
        Files.writeString(output, md.toString());

        System.out.println("Permission matrix written to " + output.toAbsolutePath());
        System.out.println("Total endpoints: " + entries.size());

        if (!violations.isEmpty()) {
            fail("Directory-scoped endpoints missing authorization annotation:\n  - "
                    + String.join("\n  - ", violations));
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private List<Class<?>> findControllerClasses() throws Exception {
        String packagePath = CONTROLLER_PACKAGE.replace('.', '/');
        File baseDir = new File("src/main/java/" + packagePath);
        List<Class<?>> classes = new ArrayList<>();

        if (baseDir.exists()) {
            try (Stream<Path> paths = Files.walk(baseDir.toPath())) {
                paths.filter(p -> p.toString().endsWith(".java"))
                        .forEach(p -> {
                            String rel = baseDir.toPath().relativize(p).toString();
                            String className = CONTROLLER_PACKAGE + "."
                                    + rel.replace(File.separatorChar, '.').replace(".java", "");
                            try {
                                Class<?> clazz = Class.forName(className);
                                if (clazz.isAnnotationPresent(RestController.class)) {
                                    classes.add(clazz);
                                }
                            } catch (ClassNotFoundException ignored) {
                                // Skip non-loadable classes
                            }
                        });
            }
        }
        return classes;
    }

    private String classLevelPath(Class<?> clazz) {
        RequestMapping rm = clazz.getAnnotation(RequestMapping.class);
        if (rm == null) return "";
        String[] paths = rm.value().length > 0 ? rm.value() : rm.path();
        return paths.length > 0 ? paths[0] : "";
    }

    private MethodMapping extractMapping(Method method) {
        GetMapping get = method.getAnnotation(GetMapping.class);
        if (get != null) return new MethodMapping("GET", firstOrEmpty(get.value()));

        PostMapping post = method.getAnnotation(PostMapping.class);
        if (post != null) return new MethodMapping("POST", firstOrEmpty(post.value()));

        PutMapping put = method.getAnnotation(PutMapping.class);
        if (put != null) return new MethodMapping("PUT", firstOrEmpty(put.value()));

        DeleteMapping del = method.getAnnotation(DeleteMapping.class);
        if (del != null) return new MethodMapping("DELETE", firstOrEmpty(del.value()));

        PatchMapping patch = method.getAnnotation(PatchMapping.class);
        if (patch != null) return new MethodMapping("PATCH", firstOrEmpty(patch.value()));

        return null;
    }

    private String firstOrEmpty(String[] arr) {
        return arr.length > 0 ? arr[0] : "";
    }

    private boolean isExempt(String path) {
        return EXEMPT_PREFIXES.stream().anyMatch(path::startsWith);
    }

    record EndpointEntry(String httpMethod, String path, String controller,
                          String method, String authMechanism, String authDetail) {}

    record MethodMapping(String httpMethod, String path) {}
}
