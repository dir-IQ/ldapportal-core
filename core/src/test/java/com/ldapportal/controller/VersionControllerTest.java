// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller;

import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the contract that the deployment-skew banner depends on:
 * the response always carries an {@code sha} field, even when
 * {@link BuildProperties} isn't on the classpath (running under
 * {@code spring-boot:run} without a packaged JAR).
 */
class VersionControllerTest {

    @Test
    void version_returnsShaAndVersionWhenBuildPropertiesPresent() {
        Properties props = new Properties();
        props.setProperty("git.sha", "abc1234");
        props.setProperty("version", "0.0.1-SNAPSHOT");
        props.setProperty("time", "2026-05-10T17:30:00Z");

        VersionController controller = new VersionController();
        ReflectionTestUtils.setField(controller, "buildProperties", new BuildProperties(props));

        Map<String, String> result = controller.version();

        assertThat(result.get("sha")).isEqualTo("abc1234");
        assertThat(result.get("version")).isEqualTo("0.0.1-SNAPSHOT");
        assertThat(result.get("buildTime")).isEqualTo("2026-05-10T17:30:00Z");
    }

    @Test
    void version_fallsBackToDevWhenBuildPropertiesAbsent() {
        VersionController controller = new VersionController();
        // buildProperties stays null — simulates @Autowired(required=false) miss

        Map<String, String> result = controller.version();

        assertThat(result.get("sha")).isEqualTo("dev");
        assertThat(result.get("version")).isEqualTo("dev");
        assertThat(result).doesNotContainKey("buildTime");
    }

    @Test
    void version_fallsBackToUnknownWhenShaPropertyMissing() {
        // Older builds may have BuildProperties without git.sha (e.g. someone
        // ran the build-info goal but not the git-commit-id plugin). Verify
        // we don't blow up and surface a recognisable sentinel instead.
        Properties props = new Properties();
        props.setProperty("version", "0.0.1-SNAPSHOT");
        props.setProperty("time", Instant.parse("2026-05-10T17:30:00Z").toString());

        VersionController controller = new VersionController();
        ReflectionTestUtils.setField(controller, "buildProperties", new BuildProperties(props));

        Map<String, String> result = controller.version();

        assertThat(result.get("sha")).isEqualTo("unknown");
    }
}
