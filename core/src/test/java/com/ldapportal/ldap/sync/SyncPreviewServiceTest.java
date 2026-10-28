// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.dto.sync.SyncPreviewAction;
import com.ldapportal.dto.sync.SyncPreviewAttributeChange;
import com.ldapportal.dto.sync.SyncPreviewEntry;
import com.ldapportal.dto.sync.SyncPreviewPage;
import com.ldapportal.dto.sync.SyncPreviewStatus;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.Membership;
import com.ldapportal.entity.SyncPreviewRun;
import com.ldapportal.entity.enums.MembershipState;
import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedSearchRequest;
import com.unboundid.ldap.listener.interceptor.InMemoryOperationInterceptor;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;
import java.util.UUID;
import com.ldapportal.entity.SyncLink;
import com.ldapportal.entity.SyncSet;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.entity.enums.SyncHoldForReview;
import com.ldapportal.entity.enums.SyncPreviewRunStatus;
import com.ldapportal.entity.enums.SyncScope;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.MembershipRepository;
import com.ldapportal.repository.RecomputeRequestRepository;
import com.ldapportal.repository.SyncLinkRepository;
import com.ldapportal.repository.SyncPreviewEntryRowRepository;
import com.ldapportal.repository.SyncPreviewRunRepository;
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
    @Autowired private SyncPreviewRunRepository runRepo;
    @Autowired private SyncPreviewEntryRowRepository entryRepo;

    private InMemoryDirectoryServer source;
    private InMemoryDirectoryServer target;
    private SyncLink link;
    private SyncSet set;
    /** Target reads of this DN fail (a busy or partitioned server), when set. */
    private volatile String failReadsOf;

    @BeforeEach
    void setUp() throws Exception {
        when(encryptionService.decrypt(anyString())).thenReturn(BIND_PASS);
        entryRepo.deleteAll();
        runRepo.deleteAll();
        membershipRepo.deleteAll();
        requestRepo.deleteAll();
        setRepo.deleteAll();
        linkRepo.deleteAll();
        directoryRepo.deleteAll();

        failReadsOf = null;
        source = startServer(SRC_BASE, null);
        target = startServer(DST_BASE, new InMemoryOperationInterceptor() {
            @Override
            public void processSearchRequest(InMemoryInterceptedSearchRequest request) throws LDAPException {
                String dn = failReadsOf;
                if (dn != null && request.getRequest().getBaseDN().equalsIgnoreCase(dn)) {
                    throw new LDAPException(ResultCode.UNAVAILABLE, "target busy");
                }
            }
        });
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
        link = linkRepo.save(l);

        SyncSet s = new SyncSet();
        s.setLinkId(link.getId());
        s.setName("people");
        s.setObjectScopeBaseDn(SRC_PEOPLE);
        s.setObjectScope(SyncScope.SUB);
        s.setTargetBaseDn(DST_USERS);
        s.setApplicabilityFilter("(|(objectClass=inetOrgPerson)(objectClass=groupOfNames))");
        s.setHoldForReview(SyncHoldForReview.NONE);
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

        SyncPreviewPage p = previewEntries(0, 50, true);

        assertThat(preview.status(set.getId()).completeScan()).isTrue();
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

        SyncPreviewPage changes = previewEntries(0, 50, true);

        assertThat(changes.entries()).singleElement().satisfies(e -> {
            assertThat(e.action()).isEqualTo(SyncPreviewAction.MODIFY);
            assertThat(e.targetDn()).isEqualToIgnoringCase("uid=alice," + DST_USERS);
            SyncPreviewAttributeChange sn = changeOf(e, "sn");
            assertThat(sn.removed()).containsExactly("alice");
            assertThat(sn.added()).containsExactly("Smith");
        });
        assertThat(preview.status(set.getId()).unchangedCount()).isEqualTo(1); // bob
        // The summary's per-outcome counts are the stored entries', so they match the list.
        assertThat(preview.status(set.getId()).actionCounts())
                .containsExactly(Map.entry("MODIFY", 1L), Map.entry("UNCHANGED", 1L));

        SyncPreviewPage all = preview.page(set.getId(), 0, 50, false);
        assertThat(all.entries()).extracting(SyncPreviewEntry::action)
                .containsExactlyInAnyOrder(SyncPreviewAction.MODIFY, SyncPreviewAction.UNCHANGED);
    }

    @Test
    void removedEntry_isADelete_orHeldForReviewUnderTheReviewPolicy() throws Exception {
        addPerson("alice");
        addPerson("bob");
        reconciler.reconcile(set.getId());
        source.delete("uid=bob," + SRC_PEOPLE);

        assertThat(previewEntries(0, 50, true).entries()).singleElement().satisfies(e -> {
            assertThat(e.action()).isEqualTo(SyncPreviewAction.DELETE);
            assertThat(e.targetDn()).isEqualToIgnoringCase("uid=bob," + DST_USERS);
        });

        SyncSet s = setRepo.findById(set.getId()).orElseThrow();
        s.setHoldForReview(SyncHoldForReview.DELETES);
        setRepo.save(s);

        assertThat(previewEntries(0, 50, true).entries()).singleElement().satisfies(e -> {
            assertThat(e.action()).isEqualTo(SyncPreviewAction.REVIEW);
            assertThat(e.reason()).contains("holdForReview=DELETES");
        });
    }

    @Test
    void holdAllUpdates_previewsHeldWritesAsReview_withTheChangesTheyHold() throws Exception {
        addPerson("alice");
        reconciler.reconcile(set.getId());
        SyncSet s = setRepo.findById(set.getId()).orElseThrow();
        s.setHoldForReview(SyncHoldForReview.ALL);
        setRepo.save(s);
        addPerson("bob");
        source.modify("uid=alice," + SRC_PEOPLE, new Modification(ModificationType.REPLACE, "sn", "Smith"));

        Map<String, SyncPreviewEntry> byDn = byTargetDn(previewEntries(0, 50, true));

        SyncPreviewEntry alice = byDn.get(("uid=alice," + DST_USERS).toLowerCase());
        assertThat(alice.action()).isEqualTo(SyncPreviewAction.REVIEW);
        assertThat(alice.reason()).isEqualTo("update held for review (update sn)");
        assertThat(changeOf(alice, "sn").added()).containsExactly("Smith");
        SyncPreviewEntry bob = byDn.get(("uid=bob," + DST_USERS).toLowerCase());
        assertThat(bob.action()).isEqualTo(SyncPreviewAction.REVIEW);
        assertThat(bob.reason()).isEqualTo("update held for review (add)");
        assertThat(changeOf(bob, "sn").added()).containsExactly("bob");
        // Read-only.
        assertThat(target.getEntry("uid=alice," + DST_USERS).getAttributeValue("sn")).isEqualTo("alice");
    }

    @Test
    void renamedEntry_isAMove_notAFullReAdd() throws Exception {
        addPerson("carol");
        reconciler.reconcile(set.getId());
        source.modifyDN("uid=carol," + SRC_PEOPLE, "uid=caroline", true);

        assertThat(previewEntries(0, 50, true).entries()).singleElement().satisfies(e -> {
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

        SyncPreviewPage p = previewEntries(0, 50, true);

        assertThat(preview.status(set.getId()).guardTripped()).isTrue();
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
        SyncPreviewPage first = previewEntries(0, 2, false);
        assertThat(first.totalEntries()).isEqualTo(5);
        assertThat(first.totalPages()).isEqualTo(3);
        for (int page = 0; page < first.totalPages(); page++) {
            preview.page(set.getId(), page, 2, false).entries()
                    .forEach(e -> seen.add(e.targetDn().toLowerCase()));
        }
        assertThat(seen).containsExactly(
                ("uid=a," + DST_USERS).toLowerCase(), ("uid=b," + DST_USERS).toLowerCase(),
                ("uid=c," + DST_USERS).toLowerCase(), ("uid=d," + DST_USERS).toLowerCase(),
                ("uid=e," + DST_USERS).toLowerCase());
        // Past the end: the last page, not an empty one with no way back.
        SyncPreviewPage past = preview.page(set.getId(), 7, 2, false);
        assertThat(past.page()).isEqualTo(2);
        assertThat(past.entries()).extracting(e -> e.targetDn().toLowerCase())
                .containsExactly(("uid=e," + DST_USERS).toLowerCase());
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

        SyncPreviewEntry pat = previewEntries(0, 50, true).entries().get(0);

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

        Map<String, SyncPreviewEntry> predicted = byTargetDn(previewEntries(0, 50, true));
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
        assertThat(previewEntries(0, 50, true).entries()).isEmpty();
    }

    @Test
    void worksForADisabledSet_andRejectsBadPaging() throws Exception {
        addPerson("alice");
        SyncSet s = setRepo.findById(set.getId()).orElseThrow();
        s.setEnabled(false);
        setRepo.save(s);

        assertThat(previewEntries(0, 50, true).entries())
                .extracting(SyncPreviewEntry::action).containsExactly(SyncPreviewAction.ADD);
        assertThatThrownBy(() -> preview.page(set.getId(), 0, 0, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> preview.page(set.getId(), -1, 50, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> preview.page(set.getId(), 0, SyncPreviewService.MAX_PAGE_SIZE + 1, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTargetReadFailure_isAnErrorForThatEntry_andTheRestStillPreview() throws Exception {
        addPerson("alice");
        addPerson("bob");
        failReadsOf = "uid=bob," + DST_USERS;

        Map<String, SyncPreviewEntry> byDn = byTargetDn(previewEntries(0, 50, true));

        assertThat(byDn.get(("uid=alice," + DST_USERS).toLowerCase()).action()).isEqualTo(SyncPreviewAction.ADD);
        SyncPreviewEntry bob = byDn.get(("uid=bob," + DST_USERS).toLowerCase());
        assertThat(bob.action()).isEqualTo(SyncPreviewAction.ERROR);
        assertThat(bob.reason()).contains("target busy");
        assertThat(preview.status(set.getId()).actionableCount()).isEqualTo(2); // errors are listed
    }

    @Test
    void anUnevaluatableFilter_stopsPlanningLikeAReconcile_insteadOfFailing() throws Exception {
        addPerson("alice");
        reconciler.reconcile(set.getId());
        SyncSet s = setRepo.findById(set.getId()).orElseThrow();
        s.setApplicabilityFilter("(cn:caseExactMatch:=alice)"); // parses, but can't be evaluated locally
        setRepo.save(s);

        SyncPreviewStatus status = preview.runNow(set.getId());

        assertThat(status.status()).isEqualTo(SyncPreviewRunStatus.COMPLETE);
        assertThat(status.completeScan()).isFalse();
        assertThat(status.note()).contains("Evaluating").contains("no deletes");
        // Incomplete scan: alice's row isn't swept as not-seen.
        assertThat(preview.page(set.getId(), 0, 50, true).entries()).isEmpty();
    }

    @Test
    void changesOnly_hidesEntriesAlreadyHeldForReview_andTargetsThatAlreadyMatch() throws Exception {
        SyncSet s = setRepo.findById(set.getId()).orElseThrow();
        s.setHoldForReview(SyncHoldForReview.DELETES);
        set = setRepo.save(s);
        addPerson("alice");
        addPerson("bob");
        reconciler.reconcile(set.getId());
        source.delete("uid=bob," + SRC_PEOPLE);
        reconciler.reconcile(set.getId());                 // bob's delete is now held for review
        // alice's content changes, but someone already made the same change on the target.
        source.modify("uid=alice," + SRC_PEOPLE, new Modification(ModificationType.REPLACE, "sn", "Smith"));
        target.modify("uid=alice," + DST_USERS, new Modification(ModificationType.REPLACE, "sn", "Smith"));

        SyncPreviewPage changes = previewEntries(0, 50, true);

        assertThat(changes.entries()).isEmpty();
        assertThat(changes.totalEntries()).isZero();
        assertThat(preview.status(set.getId()).unchangedCount()).isEqualTo(2);
        assertThat(preview.page(set.getId(), 0, 50, false).entries())
                .extracting(SyncPreviewEntry::action)
                .containsExactlyInAnyOrder(SyncPreviewAction.NO_CHANGE, SyncPreviewAction.UNCHANGED);
    }

    @Test
    void anIdenticalGroup_withAnUnsyncedMember_andSpacedMemberDns_isNoChange() throws Exception {
        String stray = "secAuthority=default,cn=foo"; // a member pointing at no synced entry
        addPerson("alice");
        addPerson("bob");
        source.add(new Entry("cn=staff," + SRC_PEOPLE,
                new Attribute("objectClass", "top", "groupOfNames"), new Attribute("cn", "staff"),
                new Attribute("member", "uid=alice, ou=people, " + SRC_BASE, "uid=bob, ou=people, " + SRC_BASE,
                        stray)));
        for (String uid : List.of("alice", "bob")) {
            target.add(new Entry("uid=" + uid + "," + DST_USERS,
                    new Attribute("objectClass", "top", "person", "organizationalPerson", "inetOrgPerson"),
                    new Attribute("uid", uid), new Attribute("cn", uid), new Attribute("sn", uid)));
        }
        // The same group on the target, its member DNs written with spaces too.
        target.add(new Entry("cn=staff," + DST_USERS,
                new Attribute("objectClass", "top", "groupOfNames"), new Attribute("cn", "staff"),
                new Attribute("member", "uid=alice, ou=Users, " + DST_BASE, "uid=bob, ou=Users, " + DST_BASE,
                        stray)));

        SyncPreviewEntry staff = byTargetDn(previewAll()).get(("cn=staff," + DST_USERS).toLowerCase());

        // The stray member is kept, and spacing alone isn't a change.
        assertThat(staff.action()).isEqualTo(SyncPreviewAction.NO_CHANGE);
        assertThat(staff.reason()).isEqualTo("member: kept 1 value as on the target, pointing to " + stray
                + ", which isn't synced yet. The sync updates it once that entry is synced.");

        // A real reconcile leaves the group alone.
        reconciler.reconcile(set.getId());
        assertThat(target.getEntry("cn=staff," + DST_USERS).getAttributeValues("member"))
                .containsExactlyInAnyOrder("uid=alice, ou=Users, " + DST_BASE, "uid=bob, ou=Users, " + DST_BASE,
                        stray);

        // A member removed on the source is still removed, and the list shows only that one.
        source.modify("cn=staff," + SRC_PEOPLE,
                new Modification(ModificationType.DELETE, "member", "uid=bob, ou=people, " + SRC_BASE));
        SyncPreviewEntry changed = byTargetDn(previewAll()).get(("cn=staff," + DST_USERS).toLowerCase());
        assertThat(changed.action()).isEqualTo(SyncPreviewAction.MODIFY);
        assertThat(changeOf(changed, "member").removed()).containsExactly("uid=bob, ou=Users, " + DST_BASE);
        assertThat(changeOf(changed, "member").added()).isEmpty();
    }

    /** alice and bob on both sides, and group staff listing them plus {@code extra} on both sides. */
    private void staffOnBothSides(String... extra) throws Exception {
        addPerson("alice");
        addPerson("bob");
        List<String> src = new java.util.ArrayList<>(List.of("uid=alice," + SRC_PEOPLE, "uid=bob," + SRC_PEOPLE));
        List<String> dst = new java.util.ArrayList<>(List.of("uid=alice," + DST_USERS, "uid=bob," + DST_USERS));
        src.addAll(List.of(extra));
        dst.addAll(List.of(extra));
        source.add(new Entry("cn=staff," + SRC_PEOPLE, new Attribute("objectClass", "top", "groupOfNames"),
                new Attribute("cn", "staff"), new Attribute("member", src)));
        target.add(new Entry("cn=staff," + DST_USERS, new Attribute("objectClass", "top", "groupOfNames"),
                new Attribute("cn", "staff"), new Attribute("member", dst)));
    }

    @Test
    void aKeptUnsyncedMember_isRemoved_onceTheSourceDropsIt() throws Exception {
        String stray = "secAuthority=default,cn=foo";
        staffOnBothSides(stray);
        reconciler.reconcile(set.getId());
        assertThat(target.getEntry("cn=staff," + DST_USERS).getAttributeValues("member")).contains(stray);

        source.modify("cn=staff," + SRC_PEOPLE, new Modification(ModificationType.DELETE, "member", stray));

        // Its removal changes the group's content hash, so the group isn't skipped.
        SyncPreviewEntry staff = byTargetDn(previewAll()).get(("cn=staff," + DST_USERS).toLowerCase());
        assertThat(staff.action()).isEqualTo(SyncPreviewAction.MODIFY);
        assertThat(changeOf(staff, "member").removed()).containsExactly(stray);
        reconciler.reconcile(set.getId());
        assertThat(target.getEntry("cn=staff," + DST_USERS).getAttributeValues("member")).doesNotContain(stray);
    }

    @Test
    void aMemberWhoseEntryTheSyncDeletes_isRemovedFromItsGroups_notKept() throws Exception {
        staffOnBothSides();
        reconciler.reconcile(set.getId());
        // bob leaves the set (the filter now excludes him) but stays in the source group.
        SyncSet s = setRepo.findById(set.getId()).orElseThrow();
        s.setApplicabilityFilter("(|(&(objectClass=inetOrgPerson)(!(uid=bob)))(objectClass=groupOfNames))");
        set = setRepo.save(s);

        Map<String, SyncPreviewEntry> byDn = byTargetDn(previewAll());
        assertThat(byDn.get(("uid=bob," + DST_USERS).toLowerCase()).action()).isEqualTo(SyncPreviewAction.DELETE);
        SyncPreviewEntry staff = byDn.get(("cn=staff," + DST_USERS).toLowerCase());
        assertThat(staff.action()).isEqualTo(SyncPreviewAction.MODIFY);
        assertThat(changeOf(staff, "member").removed()).containsExactly("uid=bob," + DST_USERS);

        reconciler.reconcile(set.getId());
        reconciler.reconcile(set.getId()); // the group, re-driven once bob's entry is gone
        assertThat(target.getEntry("uid=bob," + DST_USERS)).isNull();
        assertThat(target.getEntry("cn=staff," + DST_USERS).getAttributeValues("member"))
                .containsExactly("uid=alice," + DST_USERS);
    }

    @Test
    void membersMovedByThisPlan_showTheirGroupsRewritten() throws Exception {
        staffOnBothSides();
        reconciler.reconcile(set.getId());
        // The set now places entries under ou=Staff: the plan moves alice, bob and the group.
        target.add(new Entry("ou=Staff," + DST_BASE, new Attribute("objectClass", "top", "organizationalUnit"),
                new Attribute("ou", "Staff")));
        SyncSet s = setRepo.findById(set.getId()).orElseThrow();
        s.setTargetBaseDn("ou=Staff," + DST_BASE);
        set = setRepo.save(s);

        SyncPreviewEntry staff = byTargetDn(previewAll()).get(("cn=staff,ou=Staff," + DST_BASE).toLowerCase());

        // The members resolve to where this plan puts them, not to the index's old DNs.
        assertThat(staff.action()).isEqualTo(SyncPreviewAction.MOVE);
        assertThat(changeOf(staff, "member").added())
                .containsExactlyInAnyOrder("uid=alice,ou=Staff," + DST_BASE, "uid=bob,ou=Staff," + DST_BASE);
    }

    @Test
    void aReferenceToAnUnsyncedEntry_isKeptOnTheTarget_withANote() throws Exception {
        // Both point their manager at an entry outside the set's scope, which no
        // link syncs, so the reference can't be translated.
        String boss = "uid=boss,ou=elsewhere," + SRC_BASE;
        addPerson("alice");
        source.modify("uid=alice," + SRC_PEOPLE, new Modification(ModificationType.ADD, "manager", boss));
        addPerson("bob");
        source.modify("uid=bob," + SRC_PEOPLE, new Modification(ModificationType.ADD, "manager", boss));
        // alice is already on the target, identical, with her manager set there.
        String targetBoss = "uid=boss,ou=elsewhere," + DST_BASE;
        target.add(new Entry("uid=alice," + DST_USERS,
                new Attribute("objectClass", "top", "person", "organizationalPerson", "inetOrgPerson"),
                new Attribute("uid", "alice"), new Attribute("cn", "alice"), new Attribute("sn", "alice"),
                new Attribute("manager", targetBoss)));

        Map<String, SyncPreviewEntry> byDn = byTargetDn(previewAll());

        SyncPreviewEntry alice = byDn.get(("uid=alice," + DST_USERS).toLowerCase());
        assertThat(alice.action()).isEqualTo(SyncPreviewAction.NO_CHANGE); // manager isn't deleted
        assertThat(alice.reason()).isEqualTo("manager kept as it is on the target: it points to " + boss
                + ", which isn't synced yet. The sync updates it once that entry is synced.");
        SyncPreviewEntry bob = byDn.get(("uid=bob," + DST_USERS).toLowerCase());
        assertThat(bob.action()).isEqualTo(SyncPreviewAction.ADD);
        assertThat(bob.changes()).noneMatch(c -> c.attribute().equalsIgnoreCase("manager"));
        assertThat(bob.reason()).startsWith("manager left out: it points to " + boss);

        // A real reconcile keeps it too.
        reconciler.reconcile(set.getId());
        assertThat(target.getEntry("uid=alice," + DST_USERS).getAttributeValue("manager")).isEqualTo(targetBoss);
    }

    @Test
    void aTargetOwnedByAnotherIdentity_isForgotten_notDeleted() throws Exception {
        addPerson("alice");
        reconciler.reconcile(set.getId());
        SyncSet other = new SyncSet();
        other.setLinkId(link.getId());
        other.setName("others");
        other.setObjectScopeBaseDn("ou=others," + SRC_BASE);
        other.setObjectScope(SyncScope.SUB);
        other.setTargetBaseDn(DST_USERS);
        other.setHoldForReview(SyncHoldForReview.NONE);
        other = setRepo.save(other);
        Membership owner = new Membership();
        owner.setSyncSetId(other.getId());
        owner.setIdentity("another-identity");
        owner.setSourceDn("uid=alice,ou=others," + SRC_BASE);
        owner.setTargetDn("uid=alice," + DST_USERS);
        owner.setContentHash(new byte[] {1});
        owner.setState(MembershipState.APPLIED);
        membershipRepo.save(owner);
        source.delete("uid=alice," + SRC_PEOPLE);

        assertThat(previewEntries(0, 50, true).entries()).singleElement().satisfies(e -> {
            assertThat(e.action()).isEqualTo(SyncPreviewAction.FORGET);
            assertThat(e.reason()).contains("owned by another identity");
        });
    }

    @Test
    void brownfield_adoptsAnAnchoredTarget_andHoldsAnUnanchoredOneForReview() throws Exception {
        SyncSet s = setRepo.findById(set.getId()).orElseThrow();
        s.setSourceAnchorAttribute("employeeNumber");
        set = setRepo.save(s);
        addPerson("alice");
        addPerson("zed");
        String aliceId = source.getEntry("uid=alice," + SRC_PEOPLE, "entryUUID").getAttributeValue("entryUUID");
        // alice already exists on the target under another DN, anchored to her source identity...
        target.add(new Entry("uid=alice.old," + DST_USERS,
                new Attribute("objectClass", "top", "person", "organizationalPerson", "inetOrgPerson"),
                new Attribute("uid", "alice.old"), new Attribute("cn", "alice"), new Attribute("sn", "alice"),
                new Attribute("employeeNumber", aliceId)));
        // ...and an unrelated entry already sits where zed would go.
        target.add(new Entry("uid=zed," + DST_USERS,
                new Attribute("objectClass", "top", "person", "organizationalPerson", "inetOrgPerson"),
                new Attribute("uid", "zed"), new Attribute("cn", "zed"), new Attribute("sn", "zed")));

        Map<String, SyncPreviewEntry> byDn = byTargetDn(previewEntries(0, 50, true));

        SyncPreviewEntry alice = byDn.get(("uid=alice," + DST_USERS).toLowerCase());
        assertThat(alice.action()).isEqualTo(SyncPreviewAction.MOVE);
        assertThat(alice.previousTargetDn()).isEqualToIgnoringCase("uid=alice.old," + DST_USERS);
        SyncPreviewEntry zed = byDn.get(("uid=zed," + DST_USERS).toLowerCase());
        assertThat(zed.action()).isEqualTo(SyncPreviewAction.REVIEW);
        assertThat(zed.reason()).contains("unanchored entry already at placement DN");
    }

    @Test
    void onlyOneRunPerSetAtATime_andAStaleRunCanBeReplaced() throws Exception {
        addPerson("alice");
        SyncPreviewStatus first = preview.runNow(set.getId());
        assertThat(first.status()).isEqualTo(SyncPreviewRunStatus.COMPLETE);
        assertThat(first.requestedBy()).isNull();

        // Simulate a live run: claiming again is refused and changes nothing.
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now();
        UUID live = UUID.randomUUID();
        assertThat(runRepo.claim(set.getId(), live, "ann", now, now.minusMinutes(2))).isEqualTo(1);
        assertThat(preview.start(set.getId(), "bob").runId()).isEqualTo(live);
        assertThat(preview.status(set.getId()).requestedBy()).isEqualTo("ann");
        assertThatThrownBy(() -> preview.page(set.getId(), 0, 50, true))
                .isInstanceOf(com.ldapportal.exception.ResourceNotFoundException.class);

        // Its server stops: once the heartbeat is stale it reads as interrupted and can be replaced.
        SyncPreviewRun run = runRepo.findById(set.getId()).orElseThrow();
        run.setHeartbeatAt(now.minusMinutes(10));
        runRepo.save(run);
        assertThat(preview.status(set.getId()).status()).isEqualTo(SyncPreviewRunStatus.FAILED);
        assertThat(preview.status(set.getId()).error()).isEqualTo(SyncPreviewService.INTERRUPTED);
        SyncPreviewStatus replaced = preview.runNow(set.getId());
        assertThat(replaced.status()).isEqualTo(SyncPreviewRunStatus.COMPLETE);
        assertThat(replaced.runId()).isNotEqualTo(live).isNotEqualTo(first.runId());
        assertThat(preview.page(set.getId(), 0, 50, true).entries()).hasSize(1);
        // Only the current run's entries are kept.
        assertThat(entryRepo.count()).isEqualTo(1);
    }

    @Test
    void start_runsInTheBackground_andMarksTheResultOutdatedOnceTheSetChanges() throws Exception {
        addPerson("alice");

        preview.start(set.getId(), "ann");
        SyncPreviewStatus done = awaitFinished();

        assertThat(done.status()).isEqualTo(SyncPreviewRunStatus.COMPLETE);
        assertThat(done.requestedBy()).isEqualTo("ann");
        assertThat(done.total()).isEqualTo(1);
        assertThat(done.processed()).isEqualTo(1);
        assertThat(done.outdated()).isFalse();
        reconciler.reconcile(set.getId());
        assertThat(preview.status(set.getId()).outdated()).isTrue();
    }

    @Test
    void purge_dropsRunsPastTheRetention() throws Exception {
        addPerson("alice");
        preview.runNow(set.getId());
        SyncPreviewRun run = runRepo.findById(set.getId()).orElseThrow();
        run.setHeartbeatAt(java.time.OffsetDateTime.now().minusDays(2));
        runRepo.save(run);

        preview.purge();

        assertThat(runRepo.count()).isZero();
        assertThat(entryRepo.count()).isZero();
        assertThat(preview.status(set.getId()).status()).isNull();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private SyncPreviewStatus awaitFinished() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        SyncPreviewStatus status = preview.status(set.getId());
        while (status.status() == SyncPreviewRunStatus.RUNNING && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
            status = preview.status(set.getId());
        }
        return status;
    }

    /** Run a fresh preview to completion, then read one page of it. */
    private SyncPreviewPage previewEntries(int page, int size, boolean changesOnly) {
        SyncPreviewStatus status = preview.runNow(set.getId());
        assertThat(status.status()).as("preview run status (%s)", status.error())
                .isEqualTo(SyncPreviewRunStatus.COMPLETE);
        return preview.page(set.getId(), page, size, changesOnly);
    }

    /** Run a fresh preview to completion and read every entry, unchanged ones included. */
    private SyncPreviewPage previewAll() {
        return previewEntries(0, 50, false);
    }

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

    private static InMemoryDirectoryServer startServer(String base, InMemoryOperationInterceptor interceptor)
            throws Exception {
        InMemoryDirectoryServerConfig cfg = new InMemoryDirectoryServerConfig(base);
        cfg.addAdditionalBindCredentials(BIND_DN, BIND_PASS);
        if (interceptor != null) {
            cfg.addInMemoryOperationInterceptor(interceptor);
        }
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
