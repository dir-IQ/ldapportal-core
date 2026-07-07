// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.e2e;

import com.ldapportal.LDAPPortalApplication;
import org.springframework.boot.SpringApplication;

/**
 * Launcher for the Playwright E2E backend, started by the Maven goal
 * {@code spring-boot:test-run} (see {@code frontend/scripts/run-e2e-server.mjs}
 * and the E2E workflows). Boots the full community-plus-isva application with
 * {@link E2eTestcontainersConfiguration} supplying throwaway Postgres and
 * OpenLDAP containers, under the {@code e2e} profile
 * ({@code src/test/resources/application-e2e.yml}) that provides the test
 * credentials the suite's global-setup expects.
 */
public final class E2eTestApplication {

    private E2eTestApplication() {
    }

    public static void main(String[] args) {
        SpringApplication.from(LDAPPortalApplication::main)
                .with(E2eTestcontainersConfiguration.class)
                .withAdditionalProfiles("e2e")
                .run(args);
    }
}
