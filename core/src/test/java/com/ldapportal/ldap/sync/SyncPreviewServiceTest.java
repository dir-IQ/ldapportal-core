// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.dto.sync.SyncPreviewAction;
import com.ldapportal.dto.sync.SyncPreviewAttributeChange;
import com.ldapportal.dto.sync.SyncPreviewEntry;
import com.ldapportal.dto.sync.SyncPreviewPage;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.SyncLink;
import com.ldapportal.entity.SyncSet;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.entity.enums.SyncDeletePolicy;
import com.ldapportal.entity.enums.SyncScope;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.MembershipRepository;
import com.ldapportal.repository.RecomputeRequestRepository;
import com.ldapportal.repository.SyncLinkRepository;
import com.ldapportal.repository.SyncSetRepository;
import com.ldapportal.service.EncryptionService;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The per-entry preview dry-runs the engine's real decision path. Beyond each
 * action's shape, the key property is fidelity: what the preview predicts is
 * what a reconcile then does, and after that reconcile the preview finds
 * nothing left to change.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "ldapportal.sync.worker.fixed-delay-ms=3600000",
        "ldapportal.sync.reconcile.initial-delay-ms=3600000",
        "ldapportal.sync.changelog.fixed-delay-ms=3600000"})
class SyncPreviewServiceTest {

    private static final String SRC_BASE = "dc=src,dc=com";
    private static final String DST_BASE = "dc=dst,dc=com";
    private static final String SRC_PEOPLE = "ou=people," + SRC_BASE;
    private static final String DST_USERS = "ou=Users," + DST_BASE;
    private static final String BIND_DN = "cn=admin,dc=com";
    private static final String BIND_PASS = "adminpass";

    @MockitoBean private EncryptionService encryptionService;
    @Autowired private DirectoryConnectionRepository directoryRepo;
    @Autowired private SyncLinkRepository linkRepo;
    @Autowired private SyncSetRepository setRepo;
    @Autowired private MembershipRepository membershipRepo;
    @Autowired private RecomputeRequestRepository requestRepo;
    @Autowired private MembershipReconciler reconciler;
    @Autowired private SyncPreviewService preview;

    private InMemoryDirectoryServer source;
    private InMemoryDirectoryServer target;
    private SyncSet set;

    @BeforeEach
    void setUp() throws Exception {
        when(encryptionService.decrypt(anyString())).thenReturn(BIND_PASS);
        membershipRepo.deleteAll();
        requestRepo.deleteAll();
        setRepo.deleteAll();
        linkRepo.deleteAll();
        directoryRepo.deleteAll();

        source = startServer(SRC_BASE);
        target = startServer(DST_BASE);
        source.add(new Entry(SRC_PEOPLE, new Attribute("objectClass", "top", "organizationalUnit"),
                new Attribute("ou", "people")));
        target.add(new Entry(DST_USERS, new Attribute("objectClass", "top", "organizationalUnit"),
                new Attribute("ou", "Users")));

        DirectoryConnection src = directoryRepo.save(directory("src", source.getListenPort(), SRC_BASE));
        DirectoryConnection dst = directoryRepo.save(directory("dst", target.getListenPort(), DST_BASE));
        SyncLink l = new SyncLink();
        l.setDisplayName("preview link");
        l.setSourceDirId(src.getId());
        l.setTargetDirId(dst.getId());
        SyncLink link = linkRepo.save(l);

        SyncSet s = new SyncSet();
        s.setLinkId(link.getId());
        s.setName("people");
        s.setObjectScopeBaseDn(SRC_PEOPLE);
        s.setObjectScope(SyncScope.SUB);
        s.setTargetBaseDn(DST_USERS);
        s.setApplicabilityFilter("(|(objectClass=inetOrgPerson)(objectClass=groupOfNames))");
        s.setDeletePolicy(SyncDeletePolicy.DELETE);
        set = setRepo.save(s);
    }

    @AfterEach
    void tearDown() {
        if (source != null) source.shutDown(true);
        if (target != null) target.shutDown(true);
    }

    @Test
    void freshSet_listsAdds_withAttributes_andResolvesReferencesToEntriesAddedInTheSameRun() throws Exception {
        addPerson("alice");
        addPerson("bob");
        addGroup("staff", "uid=alice," + SRC_PEOPLE);

        SyncPreviewPage p = preview.previewEntries(set.getId(), 0, 50, true);

        assertThat(p.completeScan()).isTrue();
        assertThat(p.entries()).extracting(SyncPreviewEntry::action).containsOnly(SyncPreviewAction.ADD);
        Map<String, SyncPreviewEntry> byDn = byTargetDn(p);
        SyncPreviewEntry alice = byDn.get(("uid=alice," + DST_USERS).toLowerCase());
        assertThat(changeOf(alice, "sn").added()).containsExactly("alice");
        // alice isn't on the target yet, but this run adds her: the reference resolves.
        SyncPreviewEntry staff = byDn.get(("cn=staff," + DST_USERS).toLowerCase());
        assertThat(changeOf(staff, "member").added())
                .singleElement().satisfies(v -> assertThat(v).isEqualToIgnoringCase("uid=alice," + DST_USERS));
        // Read-only.
        assertThat(target.getEntry("uid=alice," + DST_USERS)).isNull();
        assertThat(membershipRepo.findAllBySyncSetId(set.getId())).isEmpty();
    }

    @Test
    void appliedSet_showsOnlyRealChanges_withBeforeAndAfterValues() throws Exception {
        addPerson("alice");
        addPerson("bob");
        reconciler.reconcile(set.getId());
        source.modify("uid=alice," + SRC_PEOPLE, new Modification(ModificationType.REPLACE, "sn", "Smith"));

        SyncPreviewPage changes = preview.previewEntries(set.getId(), 0, 50, true);

        assertThat(changes.entries()).singleElement().satisfies(e -> {
            assertThat(e.action()).isEqualTo(SyncPreviewAction.MODIFY);
            assertThat(e.targetDn()).isEqualToIgnoringCase("uid=alice," + DST_USERS);
            SyncPreviewAttributeChange sn = changeOf(e, "sn");
            assertThat(sn.removed()).containsExactly("alice");
            assertThat(sn.added()).containsExactly("Smith");
        });
        assertThat(changes.unchangedCount()).isEqualTo(1); // bob

        SyncPreviewPage all = preview.previewEntries(set.getId(), 0, 50, false);
        assertThat(all.entries()).extracting(SyncPreviewEntry::action)
                .containsExactlyInAnyOrder(SyncPreviewAction.MODIFY, SyncPreviewAction.UNCHANGED);
        assertThat(all.unchangedCount()).isNull();
    }

    @Test
    void removedEntry_isADelete_orHeldForReviewUnderTheReviewPolicy() throws Exception {
        addPerson("alice");
        addPerson("bob");
        reconciler.reconcile(set.getId());
        source.delete("uid=bob," + SRC_PEOPLE);

        assertThat(preview.previewEntries(set.getId(), 0, 50, true).entries()).singleElement().satisfies(e -> {
            assertThat(e.action()).isEqualTo(SyncPreviewAction.DELETE);
            assertThat(e.targetDn()).isEqualToIgnoringCase("uid=bob," + DST_USERS);
        });

        SyncSet s = setRepo.findById(set.getId()).orElseThrow();
        s.setDeletePolicy(SyncDeletePolicy.REVIEW);
        setRepo.save(s);

        assertThat(preview.previewEntries(set.getId(), 0, 50, true).entries()).singleElement().satisfies(e -> {
            assertThat(e.action()).isEqualTo(SyncPreviewAction.REVIEW);
            assertThat(e.reason()).contains("deletePolicy=REVIEW");
        });
    }

    @Test
    void renamedEntry_isAMove_notAFullReAdd() throws Exception {
        addPerson("carol");
        reconciler.reconcile(set.getId());
        source.modifyDN("uid=carol," + SRC_PEOPLE, "uid=caroline", true);

        assertThat(preview.previewEntries(set.getId(), 0, 50, true).entries()).singleElement().satisfies(e -> {
            assertThat(e.action()).isEqualTo(SyncPreviewAction.MOVE);
            assertThat(e.previousTargetDn()).isEqualToIgnoringCase("uid=carol," + DST_USERS);
            assertThat(e.targetDn()).isEqualToIgnoringCase("uid=caroline," + DST_USERS);
            // The rename carries the RDN; nothing else differs.
            assertThat(e.changes()).isEmpty();
        });
    }

    @Test
    void deleteGuard_holdsBulkDeletesForReview() throws Exception {
        for (int i = 0; i < 20; i++) {
            addPerson("p" + i);
        }
        reconciler.reconcile(set.getId());
        for (int i = 0; i < 5; i++) {                 // 25% of 20 managed > 20%
            source.delete("uid=p" + i + "," + SRC_PEOPLE);
        }

        SyncPreviewPage p = preview.previewEntries(set.getId(), 0, 50, true);

        assertThat(p.guardTripped()).isTrue();
        assertThat(p.entries()).hasSize(5).allSatisfy(e -> {
            assertThat(e.action()).isEqualTo(SyncPreviewAction.REVIEW);
            assertThat(e.reason()).contains("delete guard");
        });
    }

    @Test
    void paging_coversEveryEntryOnce_parentsFirst() throws Exception {
        for (String uid : List.of("e", "d", "c", "b", "a")) {
            addPerson(uid);
        }

        List<String> seen = new ArrayList<>();
        SyncPreviewPage first = preview.previewEntries(set.getId(), 0, 2, false);
        assertThat(first.totalEntries()).isEqualTo(5);
        assertThat(first.totalPages()).isEqualTo(3);
        for (int page = 0; page < first.totalPages(); page++) {
            preview.previewEntries(set.getId(), page, 2, false).entries()
                    .forEach(e -> seen.add(e.targetDn().toLowerCase()));
        }
        assertThat(seen).containsExactly(
                ("uid=a," + DST_USERS).toLowerCase(), ("uid=b," + DST_USERS).toLowerCase(),
                ("uid=c," + DST_USERS).toLowerCase(), ("uid=d," + DST_USERS).toLowerCase(),
                ("uid=e," + DST_USERS).toLowerCase());
        assertThat(preview.previewEntries(set.getId(), 3, 2, false).entries()).isEmpty();
    }

    @Test
    void passwords_areRedacted_evenWhenConfiguredToSync() throws Exception {
        SyncSet s = setRepo.findById(set.getId()).orElseThrow();
        s.setExcludedAttributes(List.of());          // exclude nothing: passwords would sync
        setRepo.save(s);
        source.add(new Entry("uid=pat," + SRC_PEOPLE,
                new Attribute("objectClass", "top", "person", "organizationalPerson", "inetOrgPerson"),
                new Attribute("uid", "pat"), new Attribute("cn", "pat"), new Attribute("sn", "pat"),
                new Attribute("userPassword", "s3cret")));

        SyncPreviewEntry pat = preview.previewEntries(set.getId(), 0, 50, true).entries().get(0);

        assertThat(changeOf(pat, "userPassword").added()).containsExactly(SyncPreviewValues.REDACTED);
    }

    @Test
    void previewPredictsTheReconcile_andAfterItNothingIsLeft() throws Exception {
        addPerson("alice");
        addPerson("bob");
        addPerson("carol");
        reconciler.reconcile(set.getId());
        source.modify("uid=alice," + SRC_PEOPLE, new Modification(ModificationType.REPLACE, "sn", "Smith"));
        source.delete("uid=bob," + SRC_PEOPLE);
        addPerson("dave");

        Map<String, SyncPreviewEntry> predicted = byTargetDn(preview.previewEntries(set.getId(), 0, 50, true));
        assertThat(predicted).hasSize(3);
        assertThat(predicted.get(("uid=alice," + DST_USERS).toLowerCase()).action())
                .isEqualTo(SyncPreviewAction.MODIFY);
        assertThat(predicted.get(("uid=bob," + DST_USERS).toLowerCase()).action())
                .isEqualTo(SyncPreviewAction.DELETE);
        assertThat(predicted.get(("uid=dave," + DST_USERS).toLowerCase()).action())
                .isEqualTo(SyncPreviewAction.ADD);

        reconciler.reconcile(set.getId());

        assertThat(target.getEntry("uid=alice," + DST_USERS).getAttributeValue("sn")).isEqualTo("Smith");
        assertThat(target.getEntry("uid=bob," + DST_USERS)).isNull();
        assertThat(target.getEntry("uid=dave," + DST_USERS)).isNotNull();
        assertThat(preview.previewEntries(set.getId(), 0, 50, true).entries()).isEmpty();
    }

    @Test
    void worksForADisabledSet_andRejectsBadPaging() throws Exception {
        addPerson("alice");
        SyncSet s = setRepo.findById(set.getId()).orElseThrow();
        s.setEnabled(false);
        setRepo.save(s);

        assertThat(preview.previewEntries(set.getId(), 0, 50, true).entries())
                .extracting(SyncPreviewEntry::action).containsExactly(SyncPreviewAction.ADD);
        assertThatThrownBy(() -> preview.previewEntries(set.getId(), 0, 0, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> preview.previewEntries(set.getId(), -1, 50, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> preview.previewEntries(set.getId(), 0, SyncPreviewService.MAX_PAGE_SIZE + 1, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Map<String, SyncPreviewEntry> byTargetDn(SyncPreviewPage p) {
        return p.entries().stream().collect(Collectors.toMap(e -> e.targetDn().toLowerCase(), Function.identity()));
    }

    private static SyncPreviewAttributeChange changeOf(SyncPreviewEntry e, String attribute) {
        return e.changes().stream().filter(c -> c.attribute().equalsIgnoreCase(attribute)).findFirst()
                .orElseThrow(() -> new AssertionError("no change for " + attribute + " in " + e));
    }

    private void addPerson(String uid) throws Exception {
        source.add(new Entry("uid=" + uid + "," + SRC_PEOPLE,
                new Attribute("objectClass", "top", "person", "organizationalPerson", "inetOrgPerson"),
                new Attribute("uid", uid), new Attribute("cn", uid), new Attribute("sn", uid)));
    }

    private void addGroup(String cn, String... members) throws Exception {
        source.add(new Entry("cn=" + cn + "," + SRC_PEOPLE,
                new Attribute("objectClass", "top", "groupOfNames"),
                new Attribute("cn", cn), new Attribute("member", members)));
    }

    private static InMemoryDirectoryServer startServer(String base) throws Exception {
        InMemoryDirectoryServerConfig cfg = new InMemoryDirectoryServerConfig(base);
        cfg.addAdditionalBindCredentials(BIND_DN, BIND_PASS);
        InMemoryDirectoryServer s = new InMemoryDirectoryServer(cfg);
        s.add(new Entry(base, new Attribute("objectClass", "top", "domain"),
                new Attribute("dc", base.substring(3, base.indexOf(',')))));
        s.startListening();
        return s;
    }

    private DirectoryConnection directory(String slug, int port, String base) {
        DirectoryConnection d = new DirectoryConnection();
        d.setSlug(slug);
        d.setDisplayName(slug);
        d.setDirectoryType(DirectoryType.GENERIC);
        d.setHost("localhost");
        d.setPort(port);
        d.setSslMode(SslMode.NONE);
        d.setTrustAllCerts(false);
        d.setBindDn(BIND_DN);
        d.setBindPasswordEncrypted("enc-placeholder");
        d.setBaseDn(base);
        d.setPagingSize(100);
        d.setPoolMinSize(1);
        d.setPoolMaxSize(3);
        d.setPoolConnectTimeoutSeconds(5);
        d.setPoolResponseTimeoutSeconds(10);
        d.setEnabled(true);
        return d;
    }
}
