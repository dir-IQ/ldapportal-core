// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.db;

import com.ldapportal.entity.enums.FeatureKey;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards against drift between the {@link FeatureKey} enum and the
 * {@code admin_feature_permissions.feature_key} CHECK constraint
 * ({@code chk_feature_key}).
 *
 * <p>That constraint lives only in the Flyway/Postgres schema — the H2 test DB
 * is built from entities ({@code ddl-auto: create-drop}, {@code flyway.enabled:
 * false}), so the constraint is never exercised by integration tests. When it
 * falls behind the enum, production rejects a valid override toggle with
 * {@code violates check constraint "chk_feature_key"}. This test reparses the
 * latest migration that defines the constraint and asserts its allow-list is an
 * exact mirror of the enum.</p>
 */
class FeatureKeyCheckConstraintTest {

    private static final Path MIGRATION_DIR =
            Path.of("src/main/resources/db/migration/core");

    @Test
    void checkConstraintMatchesFeatureKeyEnum() throws IOException {
        Set<String> allowed = extractDottedTokens(latestFeatureKeyConstraint());
        Set<String> enumValues = Arrays.stream(FeatureKey.values())
                .map(FeatureKey::getDbValue)
                .collect(Collectors.toSet());

        assertThat(allowed)
                .as("chk_feature_key allow-list must mirror the FeatureKey enum exactly "
                        + "(add a migration when FeatureKey changes)")
                .isEqualTo(enumValues);
    }

    /** Constraint body from the highest-versioned migration that defines it. */
    private static String latestFeatureKeyConstraint() throws IOException {
        try (Stream<Path> files = Files.list(MIGRATION_DIR)) {
            Path latest = files
                    .filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql"))
                    .filter(FeatureKeyCheckConstraintTest::definesConstraint)
                    .max(Comparator.comparingInt(FeatureKeyCheckConstraintTest::version))
                    .orElseThrow(() -> new AssertionError("no migration defines chk_feature_key"));
            String content = Files.readString(latest);
            // "CONSTRAINT chk_feature_key" matches the definition (ADD/inline)
            // but not "DROP CONSTRAINT IF EXISTS chk_feature_key".
            int start = content.indexOf("CONSTRAINT chk_feature_key");
            int end = content.indexOf(';', start);
            return content.substring(start, end);
        }
    }

    private static boolean definesConstraint(Path p) {
        try {
            return Files.readString(p).contains("CONSTRAINT chk_feature_key");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int version(Path p) {
        String name = p.getFileName().toString();
        return Integer.parseInt(name.substring(1, name.indexOf("__")));
    }

    private static Set<String> extractDottedTokens(String sql) {
        Matcher m = Pattern.compile("'([a-z][a-z_]*\\.[a-z_]+)'").matcher(sql);
        Set<String> tokens = new HashSet<>();
        while (m.find()) tokens.add(m.group(1));
        return tokens;
    }
}
