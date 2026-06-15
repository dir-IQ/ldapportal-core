// SPDX-License-Identifier: Apache-2.0
package com.ldapportal;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Smoke test — verifies the Spring application context loads without errors.
 * Requires a running PostgreSQL instance (provided by docker-compose for local dev).
 * Phase 2 will add unit tests using the UnboundID in-memory LDAP server.
 */
@SpringBootTest
@ActiveProfiles("test")
class LDAPPortalApplicationTests {

    @Test
    void contextLoads() {
        // Asserts that the Spring context assembles without errors.
    }
}
