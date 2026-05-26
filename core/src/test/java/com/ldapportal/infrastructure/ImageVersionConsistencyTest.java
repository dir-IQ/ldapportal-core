// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.infrastructure;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that every container image referenced anywhere in the repo agrees
 * on a single tag. Catches the kind of drift we hit on 2026-04-26 where
 * docker-compose.yml pinned {@code postgres:16.13-alpine} but two
 * Testcontainers test classes used the floating {@code postgres:16-alpine},
 * silently letting CI and dev pull different image builds.
 *
 * <p>Sources scanned:
 * <ul>
 *   <li>{@code docker-compose.yml} ({@code image:} lines)
 *   <li>{@code Dockerfile} + {@code frontend/Dockerfile} ({@code FROM} lines)
 *   <li>Java test sources that import {@code org.testcontainers}
 *       — Testcontainers constructors and {@code GenericContainer} subclass
 *       {@code super(...)} calls.
 * </ul>
 *
 * <p>Pairs with the {@code docker-images} group in {@code .github/dependabot.yml}:
 * the grouping prevents Dependabot from introducing drift across files in
 * separate PRs; this test prevents humans from introducing it.
 */
class ImageVersionConsistencyTest {

    /** Matches `image: postgres:16.13-alpine` (with optional quoting) in YAML. */
    private static final Pattern COMPOSE_IMAGE =
            Pattern.compile("^\\s*image:\\s*[\"']?([a-z0-9][a-z0-9./_-]*):([a-zA-Z0-9._-]+)[\"']?\\s*(?:#.*)?$");

    /** Matches `FROM image:tag [AS stage]` in Dockerfiles. Skips `FROM scratch`. */
    private static final Pattern DOCKERFILE_FROM =
            Pattern.compile("^\\s*FROM\\s+(?:--platform=\\S+\\s+)?([a-z0-9][a-z0-9./_-]*):([a-zA-Z0-9._-]+)(?:\\s+(?:AS|as)\\s+\\S+)?\\s*$");

    /**
     * Matches Testcontainers constructors that take an image string —
     * {@code new PostgreSQLContainer<>("postgres:16.13-alpine")}, etc.
     */
    private static final Pattern JAVA_CONTAINER_CTOR =
            Pattern.compile("new\\s+\\w+Container\\s*<[^>]*>\\s*\\(\\s*\"([a-z0-9][a-z0-9./_-]*):([a-zA-Z0-9._-]+)\"");

    /**
     * Matches `super("foo/bar:tag")` in classes extending GenericContainer.
     * Restricted to image-shaped strings (must contain `/` or be a known
     * single-component name) to avoid false positives from unrelated
     * {@code super(...)} calls in non-container classes.
     */
    private static final Pattern JAVA_GENERIC_SUPER =
            Pattern.compile("super\\s*\\(\\s*\"([a-z0-9][a-z0-9./_-]*):([a-zA-Z0-9._-]+)\"\\s*\\)");

    @Test
    void allDockerImageReferencesAgreeOnTag() throws IOException {
        Path projectRoot = findProjectRoot();
        // Monorepo-only: this scans docker-compose.yml, the root Dockerfile, and
        // ee test sources — all excluded from the public ldapportal-core snapshot.
        // When docker-compose.yml is absent (that snapshot), there's nothing to
        // cross-check, so skip rather than fail.
        Assumptions.assumeTrue(projectRoot != null,
                "docker-compose.yml not found — image-consistency check is monorepo-only; skipping.");
        // image-base → tag → list of "file:line" sources
        Map<String, Map<String, List<String>>> imageTags = new TreeMap<>();

        // 1. docker-compose.yml
        scanFile(projectRoot.resolve("docker-compose.yml"), COMPOSE_IMAGE, imageTags, projectRoot);

        // 2. Dockerfiles at known locations
        for (String dockerfile : List.of("Dockerfile", "frontend/Dockerfile")) {
            scanFile(projectRoot.resolve(dockerfile), DOCKERFILE_FROM, imageTags, projectRoot);
        }

        // 3. Java test sources that reference Testcontainers
        for (String module : List.of("core", "ee", "commercial")) {
            Path testSrc = projectRoot.resolve(module).resolve("src/test/java");
            if (!Files.exists(testSrc)) continue;
            try (Stream<Path> stream = Files.walk(testSrc)) {
                stream
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(ImageVersionConsistencyTest::importsTestcontainers)
                    .forEach(p -> {
                        try {
                            scanFile(p, JAVA_CONTAINER_CTOR, imageTags, projectRoot);
                            scanFile(p, JAVA_GENERIC_SUPER, imageTags, projectRoot);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
            }
        }

        // Build a list of disagreements, if any.
        List<String> disagreements = new ArrayList<>();
        imageTags.forEach((image, tagToSources) -> {
            if (tagToSources.size() > 1) {
                StringBuilder sb = new StringBuilder();
                sb.append("\n  Image '").append(image).append("' has divergent tags:\n");
                tagToSources.forEach((tag, sources) -> {
                    sb.append("    ").append(tag).append("\n");
                    for (String s : sources) sb.append("      ← ").append(s).append("\n");
                });
                disagreements.add(sb.toString());
            }
        });

        assertThat(disagreements)
            .as("All references to the same container image must agree on a single tag. "
              + "Pin every reference (no floating tags), and use the same tag everywhere "
              + "the image appears. Divergences found:")
            .isEmpty();
    }

    private static boolean importsTestcontainers(Path file) {
        try {
            return Files.readString(file).contains("org.testcontainers");
        } catch (IOException e) {
            return false;
        }
    }

    private static void scanFile(Path file, Pattern pattern,
                                 Map<String, Map<String, List<String>>> sink,
                                 Path projectRoot) throws IOException {
        if (!Files.exists(file)) return;
        List<String> lines = Files.readAllLines(file);
        String relPath = projectRoot.relativize(file).toString().replace('\\', '/');
        boolean inBlockComment = false;
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            String trimmed = raw.stripLeading();

            // Track block-comment state line by line. Heuristic: this misses
            // some edge cases (block comments closing mid-line, /*…*/ on the
            // same line, comments inside string literals) but is sufficient
            // for the limited patterns this scanner cares about.
            if (inBlockComment) {
                if (trimmed.contains("*/")) inBlockComment = false;
                continue;
            }
            if (trimmed.startsWith("/*") && !trimmed.contains("*/")) {
                inBlockComment = true;
                continue;
            }
            if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                // Javadoc continuation, line comment, or single-line block comment.
                continue;
            }

            Matcher m = pattern.matcher(raw);
            while (m.find()) {
                String name = m.group(1);
                String tag = m.group(2);
                // Skip "FROM scratch" and similar non-image references; the
                // pattern already filters most, but defend in depth.
                if ("scratch".equals(name)) continue;
                String source = relPath + ":" + (i + 1);
                sink.computeIfAbsent(name, k -> new TreeMap<>())
                    .computeIfAbsent(tag, k -> new ArrayList<>())
                    .add(source);
            }
        }
    }

    private static Path findProjectRoot() {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("docker-compose.yml"))) {
            p = p.getParent();
        }
        // null when docker-compose.yml is nowhere up the tree (the public core
        // snapshot excludes it); the caller turns that into a skip.
        return p;
    }
}
