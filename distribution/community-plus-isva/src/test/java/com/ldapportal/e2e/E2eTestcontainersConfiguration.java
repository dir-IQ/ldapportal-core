// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.e2e;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.service.EncryptionService;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.SearchScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;

/**
 * Backing services for the E2E backend ({@link E2eTestApplication}):
 *
 * <ul>
 *   <li>Postgres via {@code @ServiceConnection} — replaces the datasource the
 *       {@code e2e} profile stubs out, so Flyway runs the real migrations.</li>
 *   <li>OpenLDAP seeded with {@code ldap/baseline.ldif} (the
 *       {@code dc=test,dc=local} tree with {@code seedAlice} et al. under
 *       {@code ou=seed,ou=test} and an empty {@code ou=groups}).</li>
 *   <li>An {@link ApplicationRunner} that bridges the OpenLDAP container into
 *       a {@link DirectoryConnection} row named {@code E2E LDAP (auto)} — the
 *       contract {@code frontend/tests/e2e/helpers/directory.ts} looks up.
 *       The container's LDAP port is host-mapped to a random free port, so
 *       only the backend can learn it; specs never talk to LDAP directly.</li>
 * </ul>
 */
@TestConfiguration(proxyBeanMethods = false)
public class E2eTestcontainersConfiguration {

    private static final Logger log = LoggerFactory.getLogger(E2eTestcontainersConfiguration.class);

    static final String SEEDED_DISPLAY_NAME = "E2E LDAP (auto)";
    static final String LDAP_BASE_DN = "dc=test,dc=local";
    static final String LDAP_ADMIN_DN = "cn=admin,dc=test,dc=local";
    static final String LDAP_ADMIN_PASSWORD = "e2e-ldap-admin";
    static final String SEED_MARKER_DN = "ou=seed,ou=test,dc=test,dc=local";

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        // Pinned minor to match compose.yaml / EventBackboneEndToEndTest so
        // Dependabot's docker ecosystem tracks upgrades in one place.
        return new PostgreSQLContainer<>("postgres:16.13-alpine");
    }

    @Bean
    GenericContainer<?> openLdapContainer() {
        // Same image as the compose fixture (openldap-primary). The custom
        // bootstrap LDIF is applied by the image after it creates the
        // dc=test,dc=local base entry from LDAP_DOMAIN; --copy-service is
        // required for custom LDIF to be picked up.
        return new GenericContainer<>("osixia/openldap:1.5.0")
                .withEnv("LDAP_ORGANISATION", "E2E Fixture")
                .withEnv("LDAP_DOMAIN", "test.local")
                .withEnv("LDAP_BASE_DN", LDAP_BASE_DN)
                .withEnv("LDAP_ADMIN_PASSWORD", LDAP_ADMIN_PASSWORD)
                .withEnv("LDAP_TLS", "false")
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("ldap/baseline.ldif"),
                        "/container/service/slapd/assets/config/bootstrap/ldif/custom/baseline.ldif")
                .withCommand("--copy-service")
                .withExposedPorts(389)
                .withStartupTimeout(Duration.ofMinutes(2));
    }

    /**
     * Waits until slapd answers and the baseline LDIF is queryable (the
     * osixia image restarts slapd during bootstrap, so a listening port alone
     * does not mean the seed data is there yet), then upserts the
     * {@code E2E LDAP (auto)} directory row the specs discover by name.
     */
    @Bean
    ApplicationRunner e2eDirectorySeeder(DirectoryConnectionRepository directories,
                                         EncryptionService encryptionService,
                                         GenericContainer<?> openLdapContainer) {
        return args -> {
            String host = openLdapContainer.getHost();
            int port = openLdapContainer.getMappedPort(389);
            awaitSeedData(host, port);

            boolean present = directories.findAll().stream()
                    .anyMatch(d -> SEEDED_DISPLAY_NAME.equals(d.getDisplayName()));
            if (present) {
                log.info("[e2e] Directory '{}' already present; skipping seed", SEEDED_DISPLAY_NAME);
                return;
            }

            DirectoryConnection dc = new DirectoryConnection();
            dc.setDisplayName(SEEDED_DISPLAY_NAME);
            dc.setDirectoryType(DirectoryType.OPENLDAP);
            dc.setHost(host);
            dc.setPort(port);
            dc.setBindDn(LDAP_ADMIN_DN);
            dc.setBindPasswordEncrypted(encryptionService.encrypt(LDAP_ADMIN_PASSWORD));
            dc.setBaseDn(LDAP_BASE_DN);
            directories.save(dc);
            log.info("[e2e] Seeded directory '{}' -> {}:{}", SEEDED_DISPLAY_NAME, host, port);
        };
    }

    private static void awaitSeedData(String host, int port) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Duration.ofMinutes(2).toMillis();
        LDAPException last = null;
        while (System.currentTimeMillis() < deadline) {
            try (LDAPConnection conn =
                         new LDAPConnection(host, port, LDAP_ADMIN_DN, LDAP_ADMIN_PASSWORD)) {
                int entries = conn.search(SEED_MARKER_DN, SearchScope.SUB, "(cn=seedAlice)")
                        .getEntryCount();
                if (entries == 1) {
                    return;
                }
            } catch (LDAPException e) {
                last = e;
            }
            Thread.sleep(1000);
        }
        throw new IllegalStateException(
                "OpenLDAP container never became ready with the baseline seed data at "
                        + host + ":" + port, last);
    }
}
