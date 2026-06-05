// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.dto.ldap.LdifImportResult;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.ConflictHandling;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.ldap.LdifService.ParsedRecord;
import com.ldapportal.service.EncryptionService;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Regression net for {@link LdifService} apply/parse (Phase 0 of the LDIF
 * preview work — previously zero tests). Pins add / conflict SKIP vs OVERWRITE
 * / change records / parse-error counting against an in-memory directory, so
 * the parser-extraction refactor is safe and {@code applyParsedRecords} (used
 * by the preview) shares one execution path with {@code importLdif}.
 */
@ExtendWith(MockitoExtension.class)
class LdifServiceTest {

    @Mock private EncryptionService encryptionService;

    private LdapConnectionFactory connectionFactory;
    private LdifService ldifService;
    private InMemoryDirectoryServer server;
    private DirectoryConnection dc;

    private static final String BASE = "dc=example,dc=com";
    private static final String BIND = "cn=admin,dc=example,dc=com";
    private static final String PASS = "adminpass";

    @BeforeEach
    void setUp() throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE);
        config.addAdditionalBindCredentials(BIND, PASS);
        server = new InMemoryDirectoryServer(config);
        server.add(new Entry(BASE, new Attribute("objectClass", "top", "domain"), new Attribute("dc", "example")));
        server.startListening();

        lenient().when(encryptionService.decrypt(anyString())).thenReturn(PASS);
        connectionFactory = new LdapConnectionFactory(encryptionService, null);
        ldifService = new LdifService(connectionFactory, baselineUserService(connectionFactory));
        dc = buildDc();
    }

    @AfterEach
    void tearDown() {
        connectionFactory.closeAll();
        server.shutDown(true);
    }

    private LdifImportResult importLdif(String ldif, ConflictHandling conflict, boolean dryRun) {
        return ldifService.importLdif(dc, new ByteArrayInputStream(ldif.getBytes(StandardCharsets.UTF_8)),
                conflict, dryRun);
    }

    private static String person(String uid, String sn) {
        return "dn: uid=" + uid + "," + BASE + "\n"
                + "objectClass: top\nobjectClass: person\nobjectClass: organizationalPerson\n"
                + "objectClass: inetOrgPerson\n"
                + "cn: " + uid + "\nsn: " + sn + "\n";
    }

    @Test
    void contentEntry_isAdded() throws Exception {
        LdifImportResult r = importLdif(person("bob", "Jones"), ConflictHandling.SKIP, false);
        assertThat(r.added()).isEqualTo(1);
        assertThat(r.failed()).isZero();
        assertThat(server.getEntry("uid=bob," + BASE)).isNotNull();
    }

    @Test
    void existingEntry_skip_leavesItUnchanged() throws Exception {
        importLdif(person("amy", "Adams"), ConflictHandling.SKIP, false);
        LdifImportResult r = importLdif(person("amy", "CHANGED"), ConflictHandling.SKIP, false);
        assertThat(r.skipped()).isEqualTo(1);
        assertThat(r.updated()).isZero();
        assertThat(server.getEntry("uid=amy," + BASE).getAttributeValue("sn")).isEqualTo("Adams");
    }

    @Test
    void existingEntry_overwrite_replacesAttributes() throws Exception {
        importLdif(person("kim", "Original"), ConflictHandling.SKIP, false);
        LdifImportResult r = importLdif(person("kim", "Updated"), ConflictHandling.OVERWRITE, false);
        assertThat(r.updated()).isEqualTo(1);
        assertThat(server.getEntry("uid=kim," + BASE).getAttributeValue("sn")).isEqualTo("Updated");
    }

    @Test
    void changeRecords_addModifyDelete_areApplied() throws Exception {
        String ldif = "dn: uid=carl," + BASE + "\nchangetype: add\n"
                + "objectClass: top\nobjectClass: person\nobjectClass: organizationalPerson\n"
                + "objectClass: inetOrgPerson\ncn: carl\nsn: Smith\n\n"
                + "dn: uid=carl," + BASE + "\nchangetype: modify\nreplace: sn\nsn: Smithson\n-\n\n"
                + "dn: uid=carl," + BASE + "\nchangetype: delete\n";
        LdifImportResult r = importLdif(ldif, ConflictHandling.SKIP, false);
        assertThat(r.added()).isEqualTo(1);    // the add change record
        assertThat(r.updated()).isEqualTo(2);  // modify + delete lumped as "updated"
        assertThat(r.failed()).isZero();
        assertThat(server.getEntry("uid=carl," + BASE)).isNull(); // ended deleted
    }

    @Test
    void parseError_isCountedAsFailed_andDoesNotAbortRemaining() throws Exception {
        String ldif = person("good", "One") + "\n"
                + "dn uid=bad," + BASE + "\nobjectClass: inetOrgPerson\n";  // 'dn' missing colon
        LdifImportResult r = importLdif(ldif, ConflictHandling.SKIP, false);
        assertThat(r.added()).isEqualTo(1);
        assertThat(r.failed()).isEqualTo(1);
        assertThat(r.errors()).hasSize(1);
        assertThat(r.errors().get(0).message()).containsIgnoringCase("parse error");
    }

    @Test
    void dryRun_parsesAndCountsWithoutApplying() throws Exception {
        LdifImportResult r = importLdif(person("dan", "Dry"), ConflictHandling.SKIP, true);
        assertThat(r.added()).isZero();
        assertThat(r.skipped()).isEqualTo(1);
        assertThat(server.getEntry("uid=dan," + BASE)).isNull(); // nothing written
    }

    @Test
    void parse_assignsRowNumbers_andFlagsErrors() {
        String ldif = person("a", "A") + "\n" + person("b", "B") + "\n"
                + "dn uid=bad," + BASE + "\nx: y\n";
        var records = ldifService.parse(new ByteArrayInputStream(ldif.getBytes(StandardCharsets.UTF_8)));
        assertThat(records).hasSize(3);
        assertThat(records.get(0).rowNumber()).isEqualTo(1);
        assertThat(records.get(0).isError()).isFalse();
        ParsedRecord last = records.get(2);
        assertThat(last.rowNumber()).isEqualTo(3);
        assertThat(last.isError()).isTrue();
    }

    private DirectoryConnection buildDc() {
        DirectoryConnection d = new DirectoryConnection();
        d.setId(UUID.randomUUID());
        d.setDisplayName("test-ldap");
        d.setHost("localhost");
        d.setPort(server.getListenPort());
        d.setSslMode(SslMode.NONE);
        d.setTrustAllCerts(false);
        d.setBindDn(BIND);
        d.setBindPasswordEncrypted("enc-placeholder");
        d.setBaseDn(BASE);
        d.setPoolMinSize(1);
        d.setPoolMaxSize(3);
        d.setPoolConnectTimeoutSeconds(5);
        d.setPoolResponseTimeoutSeconds(10);
        d.setPagingSize(100);
        return d;
    }

    /**
     * A real {@link LdapUserService} with no interceptors / enrichers, so
     * user adds route through the baseline single-step ADD — byte-identical
     * to the pre-SPI behaviour these tests pin.
     */
    private static LdapUserService baselineUserService(LdapConnectionFactory cf) {
        return new LdapUserService(cf,
                new com.ldapportal.core.provisioning.ProvisioningInterceptorChain(java.util.List.of()),
                new com.ldapportal.core.provisioning.PlanExecutor(cf),
                new com.ldapportal.core.provisioning.UserReadEnricherChain(java.util.List.of()));
    }
}
