// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.ldap.model.DirectoryCapabilities;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the JSONB round-trip for {@link DirectoryCapabilities}, the first
 * Java {@code record} used as a JSONB column type in this codebase
 * (other JSONB columns are {@code Map<String, Object>} or {@code String}
 * — see PlaybookStep, DashboardLayout, AuditEvent, etc).
 *
 * <p>Two specific risks the existing tests don't cover:
 * <ul>
 *   <li>Records have no default constructor — Hibernate's JsonFormatMapper
 *       (Jackson under the hood) must call the canonical constructor.
 *       A misconfigured mapper would throw on read.</li>
 *   <li>{@code probedAt} is an {@code OffsetDateTime}. Without
 *       {@code JavaTimeModule} on the JSON mapper, Jackson defaults to
 *       serialising it as a numeric array — and fails to deserialise
 *       it back into an {@code OffsetDateTime} on read.</li>
 * </ul>
 *
 * <p>Runs against H2 in PostgreSQL compatibility mode (see
 * {@code application-test.yml}); H2 stores the JSONB column as varchar
 * but the Jackson serialisation path is identical to real Postgres, so
 * a mapper-config regression would surface here. A future migration to
 * a Testcontainers Postgres profile would tighten this further; until
 * then, this is the cheapest insurance available.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class DirectoryConnectionCapabilitiesJsonbRoundTripTest {

    @Autowired private DirectoryConnectionRepository repo;
    @Autowired private EntityManager                 em;

    @Test
    void capabilities_roundTripThroughJsonb() {
        OffsetDateTime probedAt = OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS);
        DirectoryCapabilities caps = new DirectoryCapabilities(
                "Oracle Corporation",
                "12.2.1.4.0",
                List.of("1.2.840.113556.1.4.319", "2.16.840.1.113730.3.4.2"),
                List.of("1.3.6.1.4.1.4203.1.11.1"),
                List.of("DIGEST-MD5", "GSSAPI", "PLAIN"),
                List.of("dc=oudtest,dc=example,dc=com"),
                probedAt);

        DirectoryConnection saved = repo.save(buildDc(caps));
        // flush() pushes the INSERT through Hibernate but leaves the
        // entity attached in the L1 cache; without clear(), the
        // subsequent findById returns the SAME Java instance and the
        // Jackson deserialiser is never exercised — the "round-trip"
        // assertion below would pass even if jackson-datatype-jsr310
        // were missing or the record's canonical constructor broken.
        // flushAndClear forces a true read-after-write through the
        // JSONB column and the Jackson mapper.
        em.flush();
        em.clear();

        DirectoryConnection fetched = repo.findById(saved.getId()).orElseThrow();
        DirectoryCapabilities readBack = fetched.getCapabilities();

        assertThat(readBack).isNotNull();
        assertThat(readBack.vendorName()).isEqualTo("Oracle Corporation");
        assertThat(readBack.vendorVersion()).isEqualTo("12.2.1.4.0");
        assertThat(readBack.supportedControls())
                .containsExactly("1.2.840.113556.1.4.319", "2.16.840.1.113730.3.4.2");
        assertThat(readBack.supportedExtensions())
                .containsExactly("1.3.6.1.4.1.4203.1.11.1");
        assertThat(readBack.supportedSaslMechanisms())
                .containsExactly("DIGEST-MD5", "GSSAPI", "PLAIN");
        assertThat(readBack.namingContexts())
                .containsExactly("dc=oudtest,dc=example,dc=com");
        // OffsetDateTime equality: round-tripped through Jackson and the
        // JSONB column. If JavaTimeModule isn't registered on Hibernate's
        // mapper, this assertion fails — either the read returns a null
        // probedAt (numeric-array serialisation drops the type) or the
        // initial save throws InvalidDefinitionException.
        assertThat(readBack.probedAt())
                .isEqualToIgnoringNanos(probedAt);
    }

    @Test
    void capabilities_nullPersistsAsNull() {
        // Negative case: a directory whose probe hasn't run yet (or
        // returned null) leaves the column literally NULL — not an empty
        // DirectoryCapabilities record. Pins that the entity mapping
        // doesn't accidentally apply default-construct semantics on
        // read. flushAndClear() for the same reason as the round-trip
        // test — without clearing the L1 cache, findById would return
        // the in-memory instance with capabilities=null trivially.
        DirectoryConnection saved = repo.save(buildDc(null));
        em.flush();
        em.clear();

        DirectoryConnection fetched = repo.findById(saved.getId()).orElseThrow();
        assertThat(fetched.getCapabilities()).isNull();
    }

    private static DirectoryConnection buildDc(DirectoryCapabilities caps) {
        DirectoryConnection dc = new DirectoryConnection();
        dc.setDirectoryType(DirectoryType.ORACLE_UNIFIED_DIRECTORY);
        dc.setDisplayName("OUD round-trip fixture");
        dc.setHost("ldap.example.com");
        dc.setPort(389);
        dc.setSslMode(SslMode.NONE);
        dc.setBindDn("cn=Directory Manager");
        dc.setBindPasswordEncrypted("enc-placeholder");
        dc.setBaseDn("dc=oudtest,dc=example,dc=com");
        dc.setPagingSize(500);
        dc.setPoolMinSize(1);
        dc.setPoolMaxSize(2);
        dc.setPoolConnectTimeoutSeconds(10);
        dc.setPoolResponseTimeoutSeconds(30);
        dc.setCapabilities(caps);
        return dc;
    }
}
