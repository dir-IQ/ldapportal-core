// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.dto.ldap.LdifImportResult;
import com.ldapportal.dto.ldap.LdifPreviewOp;
import com.ldapportal.dto.ldap.LdifPreviewPage;
import com.ldapportal.dto.ldap.LdifPreviewRow;
import com.ldapportal.dto.ldap.LdifPreviewRowDetail;
import com.ldapportal.dto.ldap.LdifPreviewSummary;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.ConflictHandling;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.exception.ResourceNotFoundException;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Tests {@link LdifPreviewService} against an in-memory directory: operation
 * classification, conflict detection via batched existence, DN-syntax and
 * in-scope issues, group member deltas, paging/filtering, owner scoping/expiry,
 * and apply-from-preview.
 */
@ExtendWith(MockitoExtension.class)
class LdifPreviewServiceTest {

    @Mock private EncryptionService encryptionService;

    private LdapConnectionFactory connectionFactory;
    private LdifService ldifService;
    private LdifPreviewService previewService;
    private InMemoryDirectoryServer server;
    private DirectoryConnection dc;

    private final UUID owner = UUID.randomUUID();

    private static final String BASE = "dc=example,dc=com";
    private static final String BIND = "cn=admin,dc=example,dc=com";
    private static final String PASS = "adminpass";

    @BeforeEach
    void setUp() throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE);
        config.addAdditionalBindCredentials(BIND, PASS);
        server = new InMemoryDirectoryServer(config);
        server.add(new Entry(BASE, new Attribute("objectClass", "top", "domain"), new Attribute("dc", "example")));
        server.add(new Entry("ou=people," + BASE,
                new Attribute("objectClass", "top", "organizationalUnit"), new Attribute("ou", "people")));
        // An existing person to drive conflict detection.
        server.add(new Entry("uid=amy,ou=people," + BASE,
                new Attribute("objectClass", "top", "person", "organizationalPerson", "inetOrgPerson"),
                new Attribute("cn", "amy"), new Attribute("sn", "Adams")));
        server.startListening();

        lenient().when(encryptionService.decrypt(anyString())).thenReturn(PASS);
        connectionFactory = new LdapConnectionFactory(encryptionService, null);
        ldifService = new LdifService(connectionFactory);
        previewService = new LdifPreviewService(connectionFactory, ldifService);
        ReflectionTestUtils.setField(previewService, "maxRecords", 50000);
        ReflectionTestUtils.setField(previewService, "ttlMinutes", 30L);
        ReflectionTestUtils.setField(previewService, "maxCacheEntries", 20);
        ReflectionTestUtils.setField(previewService, "maxValuesPerAttr", 200);
        dc = buildDc();
    }

    @AfterEach
    void tearDown() {
        connectionFactory.closeAll();
        server.shutDown(true);
    }

    private LdifPreviewSummary preview(String ldif, ConflictHandling conflict) {
        return previewService.createPreview(dc,
                new ByteArrayInputStream(ldif.getBytes(StandardCharsets.UTF_8)), conflict, owner);
    }

    private static String person(String uid, String sn) {
        return "dn: uid=" + uid + ",ou=people," + BASE + "\n"
                + "objectClass: top\nobjectClass: person\nobjectClass: organizationalPerson\n"
                + "objectClass: inetOrgPerson\ncn: " + uid + "\nsn: " + sn + "\n";
    }

    // ── Classification ────────────────────────────────────────────────────────

    @Test
    void newEntry_classifiesAsAdd_existing_dependsOnConflict() {
        String ldif = person("bob", "B") + "\n" + person("amy", "A");

        LdifPreviewSummary skip = preview(ldif, ConflictHandling.SKIP);
        assertThat(skip.countsByOp().add()).isEqualTo(1);   // bob
        assertThat(skip.countsByOp().skip()).isEqualTo(1);  // amy exists, SKIP

        LdifPreviewSummary over = preview(ldif, ConflictHandling.OVERWRITE);
        assertThat(over.countsByOp().add()).isEqualTo(1);     // bob
        assertThat(over.countsByOp().modify()).isEqualTo(1);  // amy exists, OVERWRITE
        // The amy row carries a CONFLICT_EXISTS info issue.
        LdifPreviewRow amy = rowFor(over, "uid=amy,ou=people," + BASE);
        assertThat(amy.issues()).anyMatch(i -> "CONFLICT_EXISTS".equals(i.code()));
    }

    @Test
    void changeRecords_classifyByChangeType() {
        String ldif = "dn: uid=carl,ou=people," + BASE + "\nchangetype: add\n"
                + "objectClass: inetOrgPerson\ncn: carl\nsn: C\n\n"
                + "dn: uid=amy,ou=people," + BASE + "\nchangetype: modify\nreplace: sn\nsn: New\n-\n\n"
                + "dn: uid=gone,ou=people," + BASE + "\nchangetype: delete\n";
        LdifPreviewSummary s = preview(ldif, ConflictHandling.SKIP);
        assertThat(s.countsByOp().add()).isEqualTo(1);
        assertThat(s.countsByOp().modify()).isEqualTo(1);
        assertThat(s.countsByOp().delete()).isEqualTo(1);
    }

    @Test
    void parseError_isClassifiedAsErrorRow() {
        String ldif = person("ok", "K") + "\n" + "dn uid=bad,ou=people," + BASE + "\nx: y\n";
        LdifPreviewSummary s = preview(ldif, ConflictHandling.SKIP);
        assertThat(s.countsByOp().error()).isEqualTo(1);
        assertThat(s.errorCount()).isEqualTo(1);
        LdifPreviewRow err = s.page0().rows().stream()
                .filter(r -> r.op() == LdifPreviewOp.ERROR).findFirst().orElseThrow();
        assertThat(err.issues()).anyMatch(i -> "PARSE_ERROR".equals(i.code()));
    }

    @Test
    void outOfScopeDn_raisesWarning() {
        String ldif = "dn: uid=stray,ou=people,dc=other,dc=org\n"
                + "objectClass: inetOrgPerson\ncn: stray\nsn: S\n";
        LdifPreviewSummary s = preview(ldif, ConflictHandling.SKIP);
        assertThat(s.warningCount()).isEqualTo(1);
        LdifPreviewRow row = s.page0().rows().get(0);
        assertThat(row.issues()).anyMatch(i -> "OUT_OF_SCOPE".equals(i.code()));
    }

    @Test
    void groupAdd_reportsMemberCount_groupModify_reportsDelta() {
        String add = "dn: cn=team,ou=people," + BASE + "\nchangetype: add\n"
                + "objectClass: groupOfNames\ncn: team\n"
                + "member: uid=a,ou=people," + BASE + "\nmember: uid=b,ou=people," + BASE + "\n\n"
                + "dn: cn=team,ou=people," + BASE + "\nchangetype: modify\n"
                + "add: member\nmember: uid=c,ou=people," + BASE + "\n-\n"
                + "delete: member\nmember: uid=a,ou=people," + BASE + "\n-\n";
        LdifPreviewSummary s = preview(add, ConflictHandling.SKIP);

        LdifPreviewRow addRow = s.page0().rows().stream()
                .filter(r -> r.op() == LdifPreviewOp.ADD).findFirst().orElseThrow();
        assertThat(addRow.memberCount()).isEqualTo(2);

        LdifPreviewRow modRow = s.page0().rows().stream()
                .filter(r -> r.op() == LdifPreviewOp.MODIFY).findFirst().orElseThrow();
        assertThat(modRow.memberDelta()).isNotNull();
        assertThat(modRow.memberDelta().added()).isEqualTo(1);
        assertThat(modRow.memberDelta().removed()).isEqualTo(1);
    }

    // ── Paging / filter / detail ──────────────────────────────────────────────

    @Test
    void page_filtersByOp_andSearchesDn() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) sb.append(person("new" + i, "N")).append("\n");
        sb.append(person("amy", "A"));   // existing → SKIP under SKIP mode
        LdifPreviewSummary s = preview(sb.toString(), ConflictHandling.SKIP);
        UUID id = UUID.fromString(s.previewId());

        LdifPreviewPage adds = previewService.page(id, owner, "ADD", null, 0, 10);
        assertThat(adds.totalFiltered()).isEqualTo(5);

        LdifPreviewPage search = previewService.page(id, owner, null, "new3", 0, 10);
        assertThat(search.totalFiltered()).isEqualTo(1);
        assertThat(search.rows().get(0).dn()).contains("uid=new3");

        LdifPreviewPage conflicts = previewService.page(id, owner, "CONFLICTS", null, 0, 10);
        assertThat(conflicts.totalFiltered()).isEqualTo(1); // amy
    }

    @Test
    void rowDetail_returnsCappedAttributes() {
        LdifPreviewSummary s = preview(person("bob", "Jones"), ConflictHandling.SKIP);
        UUID id = UUID.fromString(s.previewId());
        LdifPreviewRowDetail detail = previewService.rowDetail(id, owner, 1);
        assertThat(detail.attributes()).containsKeys("cn", "sn", "objectClass");
        assertThat(detail.attributes().get("sn")).containsExactly("Jones");
    }

    // ── Cache scoping ─────────────────────────────────────────────────────────

    @Test
    void preview_isScopedToOwner() {
        LdifPreviewSummary s = preview(person("bob", "B"), ConflictHandling.SKIP);
        UUID id = UUID.fromString(s.previewId());
        UUID otherUser = UUID.randomUUID();
        assertThatThrownBy(() -> previewService.page(id, otherUser, null, null, 0, 10))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void overSizedUpload_isRejected() {
        ReflectionTestUtils.setField(previewService, "maxRecords", 1);
        String ldif = person("a", "A") + "\n" + person("b", "B");
        assertThatThrownBy(() -> preview(ldif, ConflictHandling.SKIP))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Apply ─────────────────────────────────────────────────────────────────

    @Test
    void apply_executesPreviewedRecords_andEvicts() throws Exception {
        LdifPreviewSummary s = preview(person("zoe", "Z"), ConflictHandling.SKIP);
        UUID id = UUID.fromString(s.previewId());

        LdifImportResult result = previewService.apply(id, owner, dc);
        assertThat(result.added()).isEqualTo(1);
        assertThat(server.getEntry("uid=zoe,ou=people," + BASE)).isNotNull();

        // Cache entry is evicted after apply.
        assertThatThrownBy(() -> previewService.page(id, owner, null, null, 0, 10))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private static LdifPreviewRow rowFor(LdifPreviewSummary s, String dn) {
        return s.page0().rows().stream().filter(r -> dn.equalsIgnoreCase(r.dn()))
                .findFirst().orElseThrow();
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
}
