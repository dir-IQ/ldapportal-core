// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.dto.sync.SyncDeleteApproval;
import com.ldapportal.dto.sync.SyncPreviewAction;
import com.ldapportal.dto.sync.SyncReconcileRunDetail;
import com.ldapportal.dto.sync.SyncReviewKind;
import com.ldapportal.dto.sync.SyncVerifyResult;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.Membership;
import com.ldapportal.entity.RecomputeRequest;
import com.ldapportal.entity.SyncLink;
import com.ldapportal.entity.SyncReconcileRun;
import com.ldapportal.entity.SyncSet;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.entity.enums.MembershipState;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.entity.enums.SyncReconcileRunStatus;
import com.ldapportal.entity.enums.SyncReconcileTrigger;
import com.ldapportal.entity.enums.SyncScope;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.MembershipRepository;
import com.ldapportal.repository.RecomputeRequestRepository;
import com.ldapportal.repository.SyncLinkRepository;
import com.ldapportal.repository.SyncReconcileRunRepository;
import com.ldapportal.repository.SyncSetRepository;
import com.ldapportal.service.EncryptionService;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.unboundid.ldap.sdk.SearchResultEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * End-to-end exercise of the membership/sync engine against two in-memory
 * directory servers (source + target): the full diff matrix, convergence under
 * duplicate triggers, stable-identity rename as a MODDN, DN-reference remapping +
 * closure with hash-gated termination, reconcile's not-seen sweep, and
 * per-identity fault isolation. Recompute is driven synchronously via the engine
 * (the scheduled worker is parked) so each transition is asserted deterministically.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "ldapportal.sync.worker.fixed-delay-ms=3600000",
        "ldapportal.sync.reconcile.initial-delay-ms=3600000"})
class SyncEngineIntegrationTest {

    private static final String SRC_BASE = "dc=src,dc=com";
    private static final String DST_BASE = "dc=dst,dc=com";
    private static final String SRC_PEOPLE = "ou=people," + SRC_BASE;
    private static final String SRC_GROUPS = "ou=groups," + SRC_BASE;
    private static final String DST_USERS = "ou=Users," + DST_BASE;
    private static final String DST_GROUPS = "ou=Groups," + DST_BASE;
    private static final String BIND_DN = "cn=admin,dc=com";
    private static final String BIND_PASS = "adminpass";

    @MockitoBean private EncryptionService encryptionService;
    @Autowired private DirectoryConnectionRepository directoryRepo;
    @Autowired private SyncLinkRepository linkRepo;
    @Autowired private SyncSetRepository setRepo;
    @Autowired private MembershipRepository membershipRepo;
    @Autowired private RecomputeRequestRepository requestRepo;
    // A spy (real behavior) so a test can act in the middle of a reconcile.
    @MockitoSpyBean private RecomputeEngine engine;
    @Autowired private MembershipReconciler reconciler;
    @Autowired private SyncContentVerifier verifier;
    @Autowired private RecomputeEnqueuer enqueuer;
    @Autowired private SyncReconcileRunRepository runRepo;
    @Autowired private SyncReconcileHistory history;

    private InMemoryDirectoryServer source;
    private InMemoryDirectoryServer target;
    private InMemoryDirectoryServer source2;
    private InMemoryDirectoryServer target2;
    private SyncLink link;
    private SyncSet peopleSet;
    private SyncSet groupsSet;

    @BeforeEach
    void setUp() throws Exception {
        when(encryptionService.decrypt(anyString())).thenReturn(BIND_PASS);

        membershipRepo.deleteAll();
        requestRepo.deleteAll();
        runRepo.deleteAll();
        setRepo.deleteAll();
        linkRepo.deleteAll();
        directoryRepo.deleteAll();

        source = startServer(SRC_BASE);
        target = startServer(DST_BASE);
        source.add(new Entry(SRC_PEOPLE, new Attribute("objectClass", "top", "organizationalUnit"),
                new Attribute("ou", "people")));
        source.add(new Entry(SRC_GROUPS, new Attribute("objectClass", "top", "organizationalUnit"),
                new Attribute("ou", "groups")));
        target.add(new Entry(DST_USERS, new Attribute("objectClass", "top", "organizationalUnit"),
                new Attribute("ou", "Users")));
        target.add(new Entry(DST_GROUPS, new Attribute("objectClass", "top", "organizationalUnit"),
                new Attribute("ou", "Groups")));

        DirectoryConnection src = directoryRepo.save(directory("src", source.getListenPort(), SRC_BASE));
        DirectoryConnection dst = directoryRepo.save(directory("dst", target.getListenPort(), DST_BASE));

        SyncLink l = new SyncLink();
        l.setDisplayName("src->dst");
        l.setSourceDirId(src.getId());
        l.setTargetDirId(dst.getId());
        link = linkRepo.save(l);

        peopleSet = setRepo.save(syncSet("people", SRC_PEOPLE, DST_USERS,
                "(&(objectClass=inetOrgPerson)(employeeType=staff))", null));
        groupsSet = setRepo.save(syncSet("groups", SRC_GROUPS, DST_GROUPS,
                "(objectClass=groupOfNames)", "member"));
    }

    @AfterEach
    void tearDown() {
        if (source != null) source.shutDown(true);
        if (target != null) target.shutDown(true);
        if (source2 != null) source2.shutDown(true);
        if (target2 != null) target2.shutDown(true);
    }

    // ── ADD / MODIFY (hash gate) / DELETE ───────────────────────────────────────

    @Test
    void add_modify_delete_lifecycle() throws Exception {
        addPerson("alice", "staff", "alice@src");
        engine.process(peopleSet.getId(), dn("alice"));

        SearchResultEntry t = target.getEntry("uid=alice," + DST_USERS);
        assertThat(t).isNotNull();
        assertThat(t.getAttributeValue("mail")).isEqualTo("alice@src");
        Membership m = membership("alice");
        assertThat(m.getState()).isEqualTo(MembershipState.APPLIED);
        byte[] hashAfterAdd = m.getContentHash();

        // MODIFY: change mail -> target updates, hash changes.
        source.modify("uid=alice," + SRC_PEOPLE, new Modification(ModificationType.REPLACE, "mail", "alice@new"));
        engine.process(peopleSet.getId(), dn("alice"));
        assertThat(target.getEntry("uid=alice," + DST_USERS).getAttributeValue("mail")).isEqualTo("alice@new");
        assertThat(membership("alice").getContentHash()).isNotEqualTo(hashAfterAdd);

        // DELETE at source -> target removed, index row gone.
        source.delete("uid=alice," + SRC_PEOPLE);
        engine.process(peopleSet.getId(), dn("alice"));
        assertThat(target.getEntry("uid=alice," + DST_USERS)).isNull();
        assertThat(membershipRepo.findAllBySyncSetId(peopleSet.getId())).isEmpty();
    }

    // ── Applicability-driven scope enter (MODIFY at source becomes ADD at target) ─

    @Test
    void attributeChange_entersScope_asTargetAdd() throws Exception {
        addPerson("bob", "contractor", "bob@src");
        engine.process(peopleSet.getId(), dn("bob"));
        assertThat(target.getEntry("uid=bob," + DST_USERS)).isNull(); // contractor -> OUT

        source.modify("uid=bob," + SRC_PEOPLE, new Modification(ModificationType.REPLACE, "employeeType", "staff"));
        engine.process(peopleSet.getId(), dn("bob"));
        assertThat(target.getEntry("uid=bob," + DST_USERS)).isNotNull(); // now IN -> ADD
    }

    // ── Stable identity => rename is a MODDN, not delete+recreate ────────────────

    @Test
    void rename_underStableIdentity_isModdn() throws Exception {
        addPerson("alice", "staff", "alice@src");
        engine.process(peopleSet.getId(), dn("alice"));
        String identityBefore = membership("alice").getIdentity();

        source.modifyDN("uid=alice," + SRC_PEOPLE, "uid=aadams", true);
        engine.process(peopleSet.getId(), "uid=aadams," + SRC_PEOPLE);

        assertThat(target.getEntry("uid=alice," + DST_USERS)).isNull();
        assertThat(target.getEntry("uid=aadams," + DST_USERS)).isNotNull();
        // Same identity row, just a new source/target DN — not a new membership.
        assertThat(membershipRepo.findAllBySyncSetId(peopleSet.getId())).hasSize(1);
        assertThat(membership(identityBefore).getTargetDn()).isEqualToIgnoringCase("uid=aadams," + DST_USERS);
    }

    // ── A recompute keyed on the PRE-move DN (changelog modrdn) is a MODDN ───────

    @Test
    void recomputeByOldDn_afterMove_isModdnNotDelete() throws Exception {
        addPerson("alice", "staff", "alice@src");
        engine.process(peopleSet.getId(), dn("alice"));
        String identityBefore = membership("alice").getIdentity();

        // Entry moves; a changelog feed reports the OLD dn.
        source.modifyDN("uid=alice," + SRC_PEOPLE, "uid=aadams", true);
        engine.process(peopleSet.getId(), dn("alice")); // key = pre-move DN

        // Re-searched by identity → MODDN, not a delete of the (still-present) entry.
        assertThat(target.getEntry("uid=alice," + DST_USERS)).isNull();
        assertThat(target.getEntry("uid=aadams," + DST_USERS)).isNotNull();
        assertThat(membershipRepo.findAllBySyncSetId(peopleSet.getId())).hasSize(1);
        assertThat(membership(identityBefore).getState()).isEqualTo(MembershipState.APPLIED);
    }

    // ── Convergence: a duplicate trigger is a no-op ──────────────────────────────

    @Test
    void duplicateTrigger_isNoop() throws Exception {
        addPerson("carol", "staff", "carol@src");
        engine.process(peopleSet.getId(), dn("carol"));
        byte[] hashBefore = membership("carol").getContentHash();

        engine.process(peopleSet.getId(), dn("carol")); // identical state
        engine.process(peopleSet.getId(), dn("carol"));

        // No new membership rows, hash unchanged (the content-hash gate suppressed
        // any redundant target write), target still correct.
        assertThat(membershipRepo.findAllBySyncSetId(peopleSet.getId())).hasSize(1);
        assertThat(membership("carol").getContentHash()).isEqualTo(hashBefore);
        assertThat(target.getEntry("uid=carol," + DST_USERS)).isNotNull();
    }

    // ── DN-reference remapping + in-stream closure + termination ─────────────────

    @Test
    void groupReference_remapsAndClosesOverMemberChange() throws Exception {
        addPerson("alice", "staff", "alice@src");
        addPerson("bob", "contractor", "bob@src");
        addPerson("carol", "staff", "carol@src");
        source.add(new Entry("cn=eng," + SRC_GROUPS,
                new Attribute("objectClass", "top", "groupOfNames"),
                new Attribute("cn", "eng"),
                new Attribute("member",
                        "uid=alice," + SRC_PEOPLE, "uid=bob," + SRC_PEOPLE, "uid=carol," + SRC_PEOPLE)));

        // Seed people first so the group's references resolve through the index.
        engine.process(peopleSet.getId(), dn("alice"));
        engine.process(peopleSet.getId(), dn("bob"));
        engine.process(peopleSet.getId(), dn("carol"));
        engine.process(groupsSet.getId(), "cn=eng," + SRC_GROUPS);

        SearchResultEntry group = target.getEntry("cn=eng," + DST_GROUPS);
        assertThat(group).isNotNull();
        // alice + carol remapped to target DNs; bob (contractor) dropped.
        assertThat(group.getAttributeValues("member")).containsExactlyInAnyOrder(
                "uid=alice," + DST_USERS, "uid=carol," + DST_USERS);

        // Promote bob -> staff: closure should enqueue the group's recompute.
        requestRepo.deleteAll();
        source.modify("uid=bob," + SRC_PEOPLE, new Modification(ModificationType.REPLACE, "employeeType", "staff"));
        engine.process(peopleSet.getId(), dn("bob"));
        assertThat(target.getEntry("uid=bob," + DST_USERS)).isNotNull();
        List<RecomputeRequest> enqueued = requestRepo.findAllBySyncSetId(groupsSet.getId());
        assertThat(enqueued).extracting(RecomputeRequest::getRequestKey)
                .anyMatch(k -> k.startsWith("cn=eng"));

        // Drain the closure trigger: group re-projects with bob added.
        engine.process(groupsSet.getId(), "cn=eng," + SRC_GROUPS);
        assertThat(target.getEntry("cn=eng," + DST_GROUPS).getAttributeValues("member"))
                .containsExactlyInAnyOrder("uid=alice," + DST_USERS,
                        "uid=bob," + DST_USERS, "uid=carol," + DST_USERS);

        // Termination: re-processing the now-stable group is hash-gated to a
        // no-op, so its content hash is unchanged and no further closure fires.
        byte[] groupHashBefore = membershipRepo.findAllBySyncSetId(groupsSet.getId()).get(0).getContentHash();
        engine.process(groupsSet.getId(), "cn=eng," + SRC_GROUPS);
        assertThat(membershipRepo.findAllBySyncSetId(groupsSet.getId()).get(0).getContentHash())
                .isEqualTo(groupHashBefore);
    }

    // ── Cross-link references (IVIA secDN: c=admin secUser → c=us demographic) ──

    @Test
    void reference_resolvesAcrossLinks_andClosureSpansLinks() throws Exception {
        // A second, independent link (its own source + target connections) whose
        // entries reference DNs mirrored by the first link — the shape OUD forces
        // when c=us and c=admin are separate top-level roots.
        String src2Base = "dc=src2,dc=com";
        String dst2Base = "dc=dst2,dc=com";
        source2 = startServer(src2Base);
        target2 = startServer(dst2Base);
        source2.add(new Entry("ou=accounts," + src2Base,
                new Attribute("objectClass", "top", "organizationalUnit"), new Attribute("ou", "accounts")));
        target2.add(new Entry("ou=accounts," + dst2Base,
                new Attribute("objectClass", "top", "organizationalUnit"), new Attribute("ou", "accounts")));
        DirectoryConnection src2 = directoryRepo.save(directory("src2", source2.getListenPort(), src2Base));
        DirectoryConnection dst2 = directoryRepo.save(directory("dst2", target2.getListenPort(), dst2Base));
        SyncLink l2 = new SyncLink();
        l2.setDisplayName("src2->dst2");
        l2.setSourceDirId(src2.getId());
        l2.setTargetDirId(dst2.getId());
        SyncLink link2 = linkRepo.save(l2);
        SyncSet accounts = syncSet("accounts", "ou=accounts," + src2Base, "ou=accounts," + dst2Base,
                "(objectClass=inetOrgPerson)", "manager");
        accounts.setLinkId(link2.getId());
        SyncSet accountsSet = setRepo.save(accounts);

        String daveSrc = "uid=dave,ou=accounts," + src2Base;
        String daveDst = "uid=dave,ou=accounts," + dst2Base;
        source2.add(new Entry(daveSrc,
                new Attribute("objectClass", "top", "person", "organizationalPerson", "inetOrgPerson"),
                new Attribute("uid", "dave"), new Attribute("cn", "dave"), new Attribute("sn", "dave"),
                new Attribute("manager", "uid=alice," + SRC_PEOPLE)));

        // Referent not synced anywhere yet → the reference is dropped, not invented.
        engine.process(accountsSet.getId(), daveSrc);
        assertThat(target2.getEntry(daveDst)).isNotNull();
        assertThat(target2.getEntry(daveDst).hasAttribute("manager")).isFalse();

        // Alice lands via the *other* link: closure must re-enqueue dave in link 2.
        requestRepo.deleteAll();
        addPerson("alice", "staff", "alice@src");
        engine.process(peopleSet.getId(), dn("alice"));
        List<RecomputeRequest> enqueued = requestRepo.findAllBySyncSetId(accountsSet.getId());
        assertThat(enqueued).extracting(RecomputeRequest::getRequestKey)
                .anyMatch(k -> k.equalsIgnoreCase(daveSrc));

        // Draining it resolves the reference through link 1's index.
        engine.process(accountsSet.getId(), daveSrc);
        assertThat(target2.getEntry(daveDst).getAttributeValue("manager"))
                .isEqualTo("uid=alice," + DST_USERS);
    }

    // ── Verify: a correctly-synced set reports in-sync (references included) ─────

    @Test
    void verify_groupWithReferences_reportsInSync() throws Exception {
        addPerson("alice", "staff", "alice@src");
        addPerson("carol", "staff", "carol@src");
        source.add(new Entry("cn=eng," + SRC_GROUPS,
                new Attribute("objectClass", "top", "groupOfNames"),
                new Attribute("cn", "eng"),
                new Attribute("member", "uid=alice," + SRC_PEOPLE, "uid=carol," + SRC_PEOPLE)));

        engine.process(peopleSet.getId(), dn("alice"));
        engine.process(peopleSet.getId(), dn("carol"));
        engine.process(groupsSet.getId(), "cn=eng," + SRC_GROUPS);

        // Regression: the group's member references must resolve through the index
        // exactly as the engine projected them. With NO_RESOLVER the member values
        // were dropped from the desired image, so every group showed spurious drift.
        SyncVerifyResult g = verifier.verify(groupsSet.getId());
        assertThat(g.sourceMembers()).isEqualTo(1);
        assertThat(g.targetEntries()).isEqualTo(1);
        assertThat(g.inSync()).isEqualTo(1);
        assertThat(g.contentMismatches()).isZero();
        assertThat(g.missingOnTarget()).isZero();
        assertThat(g.orphanOnTarget()).isZero();

        // The people set verifies clean too (no references involved).
        SyncVerifyResult p = verifier.verify(peopleSet.getId());
        assertThat(p.inSync()).isEqualTo(2);
        assertThat(p.contentMismatches()).isZero();
    }

    @Test
    void verify_detectsOutOfBandTargetDrift() throws Exception {
        addPerson("alice", "staff", "alice@src");
        engine.process(peopleSet.getId(), dn("alice"));
        assertThat(verifier.verify(peopleSet.getId()).contentMismatches()).isZero();

        // Someone edits the target out of band — verification flags the real drift.
        target.modify("uid=alice," + DST_USERS,
                new Modification(ModificationType.REPLACE, "mail", "tampered@dst"));

        SyncVerifyResult r = verifier.verify(peopleSet.getId());
        assertThat(r.contentMismatches()).isEqualTo(1);
        assertThat(r.sampleMismatches()).anyMatch(d -> d.contains("uid=alice"));
    }

    // ── Reconcile: not-seen sweep removes an orphan left by a missed delete ──────

    @Test
    void reconcile_notSeenSweep_removesOrphan() throws Exception {
        addPerson("alice", "staff", "alice@src");
        addPerson("carol", "staff", "carol@src");
        reconciler.reconcile(peopleSet.getId());
        assertThat(target.getEntry("uid=alice," + DST_USERS)).isNotNull();
        assertThat(target.getEntry("uid=carol," + DST_USERS)).isNotNull();

        // Delete carol at source WITHOUT notifying the engine (a missed event).
        source.delete("uid=carol," + SRC_PEOPLE);
        reconciler.reconcile(peopleSet.getId());

        assertThat(target.getEntry("uid=carol," + DST_USERS)).isNull();
        assertThat(target.getEntry("uid=alice," + DST_USERS)).isNotNull();
        assertThat(membershipRepo.findAllBySyncSetId(peopleSet.getId())).hasSize(1);
    }

    // ── Per-identity fault isolation: one poison entry doesn't block another ─────

    @Test
    void failedIdentity_doesNotBlockOthers() throws Exception {
        // Park the target Users OU so alice's ADD fails (no parent), while a
        // second set with a valid target still applies.
        target.delete(DST_USERS);
        addPerson("alice", "staff", "alice@src");
        addPerson("carol", "staff", "carol@src");

        engine.process(peopleSet.getId(), dn("alice"));
        engine.process(peopleSet.getId(), dn("carol"));

        // Both fail (shared missing OU) but each is recorded independently as FAILED.
        assertThat(membership("alice").getState()).isEqualTo(MembershipState.FAILED);
        assertThat(membership("carol").getState()).isEqualTo(MembershipState.FAILED);

        // Restore the OU; carol recomputes to APPLIED without any dependence on alice.
        target.add(new Entry(DST_USERS, new Attribute("objectClass", "top", "organizationalUnit"),
                new Attribute("ou", "Users")));
        engine.process(peopleSet.getId(), dn("carol"));
        assertThat(membership("carol").getState()).isEqualTo(MembershipState.APPLIED);
        assertThat(target.getEntry("uid=carol," + DST_USERS)).isNotNull();
    }

    @Test
    void failReason_carriesServerDiagnostic() throws Exception {
        // A missing target parent OU makes the ADD fail; the row's reason should
        // carry the server's diagnostic ("…does not exist…") beyond the bare code,
        // so an operator knows *what* to fix before recomputing.
        target.delete(DST_USERS);
        addPerson("alice", "staff", "alice@src");
        engine.process(peopleSet.getId(), dn("alice"));

        Membership m = membership("alice");
        assertThat(m.getState()).isEqualTo(MembershipState.FAILED);
        assertThat(m.getFailReason())
                .startsWith("apply failed:")
                .contains("—")                  // a server diagnostic was appended
                .containsIgnoringCase("exist");  // …the missing-parent message
    }

    // ── Queue lease: a re-trigger during processing survives the settle delete ──

    @Test
    void recomputeRequestLease_deletesOnlyWhenStillClaimed() {
        enqueuer.enqueue(peopleSet.getId(), "k1", null);
        assertThat(requestRepo.claim(peopleSet.getId(), "k1", OffsetDateTime.now())).isEqualTo(1);
        assertThat(requestRepo.claim(peopleSet.getId(), "k1", OffsetDateTime.now())).isEqualTo(0);

        // Re-trigger mid-processing nulls the claim, so the settle delete misses.
        enqueuer.enqueue(peopleSet.getId(), "k1", null);
        requestRepo.deleteIfClaimed(peopleSet.getId(), "k1");
        assertThat(requestRepo.findAllBySyncSetId(peopleSet.getId())).hasSize(1);
        assertThat(requestRepo.findAllBySyncSetId(peopleSet.getId()).get(0).getClaimedAt()).isNull();

        // A clean claim + settle removes it.
        requestRepo.claim(peopleSet.getId(), "k1", OffsetDateTime.now());
        requestRepo.deleteIfClaimed(peopleSet.getId(), "k1");
        assertThat(requestRepo.findAllBySyncSetId(peopleSet.getId())).isEmpty();
    }

    @Test
    void recomputeRequestLease_staleClaimIsReleased() {
        enqueuer.enqueue(peopleSet.getId(), "k2", null);
        requestRepo.claim(peopleSet.getId(), "k2", OffsetDateTime.now().minusMinutes(30));
        assertThat(requestRepo.findBatchUnclaimed(org.springframework.data.domain.PageRequest.of(0, 10)))
                .isEmpty();

        assertThat(requestRepo.releaseStaleClaims(OffsetDateTime.now().minusMinutes(10))).isEqualTo(1);
        assertThat(requestRepo.findBatchUnclaimed(org.springframework.data.domain.PageRequest.of(0, 10)))
                .hasSize(1);
    }

    // ── Brownfield adoption + quarantine (sourceAnchor) ─────────────────────────

    @Test
    void brownfield_adoptsExistingAnchoredEntry_doesNotDuplicate() throws Exception {
        peopleSet.setSourceAnchorAttribute("description");
        setRepo.save(peopleSet);
        addPerson("alice", "staff", "alice@src");
        String uuid = sourceEntryUuid("alice");
        // A pre-existing, hand-made target entry already carries our anchor.
        target.add(new Entry("uid=alice," + DST_USERS,
                new Attribute("objectClass", "top", "person", "organizationalPerson", "inetOrgPerson"),
                new Attribute("uid", "alice"), new Attribute("cn", "OLD"), new Attribute("sn", "OLD"),
                new Attribute("description", uuid)));

        engine.process(peopleSet.getId(), dn("alice"));

        // Adopted (converged), not duplicated.
        assertThat(membership("alice").getState()).isEqualTo(MembershipState.APPLIED);
        assertThat(target.getEntry("uid=alice," + DST_USERS).getAttributeValue("cn")).isEqualTo("alice");
        assertThat(target.search(DST_USERS, com.unboundid.ldap.sdk.SearchScope.SUB, "(description=" + uuid + ")")
                .getEntryCount()).isEqualTo(1);
    }

    @Test
    void brownfield_quarantinesUnanchoredCollisionAtPlacementDn() throws Exception {
        peopleSet.setSourceAnchorAttribute("description");
        setRepo.save(peopleSet);
        addPerson("alice", "staff", "alice@src");
        // An unanchored entry already sits at the placement DN.
        target.add(new Entry("uid=alice," + DST_USERS,
                new Attribute("objectClass", "top", "person", "organizationalPerson", "inetOrgPerson"),
                new Attribute("uid", "alice"), new Attribute("cn", "HANDMADE"), new Attribute("sn", "x")));

        engine.process(peopleSet.getId(), dn("alice"));

        assertThat(membership("alice").getState()).isEqualTo(MembershipState.REVIEW);
        // Target left untouched.
        assertThat(target.getEntry("uid=alice," + DST_USERS).getAttributeValue("cn")).isEqualTo("HANDMADE");
    }

    @Test
    void brownfield_quarantinesAmbiguousMultipleAnchorMatches() throws Exception {
        peopleSet.setSourceAnchorAttribute("description");
        setRepo.save(peopleSet);
        addPerson("alice", "staff", "alice@src");
        String uuid = sourceEntryUuid("alice");
        for (String cn : List.of("dup1", "dup2")) {
            target.add(new Entry("cn=" + cn + "," + DST_USERS,
                    new Attribute("objectClass", "top", "person"),
                    new Attribute("cn", cn), new Attribute("sn", "x"),
                    new Attribute("description", uuid)));
        }

        engine.process(peopleSet.getId(), dn("alice"));

        assertThat(membership("alice").getState()).isEqualTo(MembershipState.REVIEW);
        assertThat(membership("alice").getFailReason()).contains("ambiguous");
    }

    @Test
    void deletePolicyReview_quarantinesScopeExit_retainsTarget() throws Exception {
        peopleSet.setDeletePolicy(com.ldapportal.entity.enums.SyncDeletePolicy.REVIEW);
        setRepo.save(peopleSet);
        addPerson("alice", "staff", "alice@src");
        engine.process(peopleSet.getId(), dn("alice"));
        assertThat(target.getEntry("uid=alice," + DST_USERS)).isNotNull();

        source.delete("uid=alice," + SRC_PEOPLE);
        engine.process(peopleSet.getId(), dn("alice"));

        // Held for review, target retained (not deleted).
        assertThat(membership("alice").getState()).isEqualTo(MembershipState.REVIEW);
        assertThat(target.getEntry("uid=alice," + DST_USERS)).isNotNull();
    }

    // ── Operator-approved delete of a held scope-exit ──────────────────────────

    /** alice is synced, then leaves scope under deletePolicy=REVIEW: her delete is held. */
    private String holdAliceDeleteForReview() throws Exception {
        peopleSet.setDeletePolicy(com.ldapportal.entity.enums.SyncDeletePolicy.REVIEW);
        peopleSet = setRepo.save(peopleSet);
        addPerson("alice", "staff", "alice@src");
        engine.process(peopleSet.getId(), dn("alice"));
        String identity = membership("alice").getIdentity();
        source.modify(dn("alice"), new Modification(ModificationType.REPLACE, "employeeType", "contractor"));
        engine.process(peopleSet.getId(), dn("alice"));
        assertThat(SyncReviewKind.of(membership(identity).getState(), membership(identity).getFailReason()))
                .isEqualTo(SyncReviewKind.PENDING_DELETE);
        return identity;
    }

    @Test
    void approveDelete_deletesHeldTarget_despiteReviewPolicy() throws Exception {
        String identity = holdAliceDeleteForReview();
        // A recompute alone leaves a REVIEW-policy delete held — the gap this closes.
        engine.process(peopleSet.getId(), identity);
        assertThat(target.getEntry("uid=alice," + DST_USERS)).isNotNull();

        SyncDeleteApproval result = engine.approveDelete(peopleSet.getId(), identity);

        assertThat(result.outcome()).isEqualTo(SyncPreviewAction.DELETE);
        assertThat(result.targetDn()).isEqualToIgnoringCase("uid=alice," + DST_USERS);
        assertThat(target.getEntry("uid=alice," + DST_USERS)).isNull();
        assertThat(membershipRepo.findAllBySyncSetId(peopleSet.getId())).isEmpty();
    }

    @Test
    void approveDelete_refusesWhenTheEntryIsBackInScope() throws Exception {
        String identity = holdAliceDeleteForReview();
        source.modify(dn("alice"), new Modification(ModificationType.REPLACE, "employeeType", "staff"));

        assertThatThrownBy(() -> engine.approveDelete(peopleSet.getId(), identity))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("back in scope");
        assertThat(target.getEntry("uid=alice," + DST_USERS)).isNotNull();
    }

    @Test
    void approveDelete_refusesARowThatIsNotAHeldDelete() throws Exception {
        addPerson("alice", "staff", "alice@src");
        engine.process(peopleSet.getId(), dn("alice"));

        assertThatThrownBy(() -> engine.approveDelete(peopleSet.getId(), membership("alice").getIdentity()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no deletion waiting");
        assertThat(target.getEntry("uid=alice," + DST_USERS)).isNotNull();
    }

    @Test
    void approveDelete_refusesOnADisabledSet() throws Exception {
        String identity = holdAliceDeleteForReview();
        peopleSet.setEnabled(false);
        setRepo.save(peopleSet);

        assertThatThrownBy(() -> engine.approveDelete(peopleSet.getId(), identity))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Enable the sync set");
        assertThat(target.getEntry("uid=alice," + DST_USERS)).isNotNull();
    }

    // ── identityKey override ─────────────────────────────────────────────────────

    @Test
    void identityKey_override_keysOnConfiguredAttribute() throws Exception {
        peopleSet.setIdentityKey("uid");
        setRepo.save(peopleSet);
        addPerson("alice", "staff", "alice@src");

        engine.process(peopleSet.getId(), dn("alice"));

        // Identity is the uid value, not the entryUUID.
        assertThat(membershipRepo.findAllBySyncSetId(peopleSet.getId())).hasSize(1);
        assertThat(membership("alice").getIdentity()).isEqualTo("alice");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    // ── Regressions: a target entry the source still has is never deleted ───────

    @Test
    void deleteAndRecreateAtSameDn_coalescedTrigger_neverDeletesLiveTarget() throws Exception {
        addPerson("alice", "staff", "alice@v1");
        engine.process(peopleSet.getId(), dn("alice"));
        String oldIdentity = membership("alice").getIdentity();

        // Delete + re-create at the same DN (new entryUUID) before the engine sees
        // either write — exactly what the coalescing queue produces.
        source.delete(dn("alice"));
        addPerson("alice", "staff", "alice@v2");
        engine.process(peopleSet.getId(), dn("alice"));

        // The new identity took the target DN; the stale row was retired with it.
        assertThat(target.getEntry("uid=alice," + DST_USERS).getAttributeValue("mail")).isEqualTo("alice@v2");
        assertThat(membershipRepo.findAllBySyncSetId(peopleSet.getId())).hasSize(1);
        assertThat(membership("alice").getIdentity()).isNotEqualTo(oldIdentity);

        // A later reconcile (the not-seen sweep) must leave the live entry alone.
        reconciler.reconcile(peopleSet.getId());
        assertThat(target.getEntry("uid=alice," + DST_USERS)).isNotNull();
        assertThat(membershipRepo.findAllBySyncSetId(peopleSet.getId())).hasSize(1);
    }

    @Test
    void staleRowInAnotherSet_goingOut_dropsRowWithoutDeletingOwnedTarget() throws Exception {
        addPerson("alice", "staff", "alice@src");
        engine.process(peopleSet.getId(), dn("alice"));
        Membership live = membership("alice");

        // A second set on the same link still remembers a (now absent) source entry
        // whose target DN is the one alice's live row owns — e.g. an identity-key
        // change or an old identity of the same DN.
        SyncSet other = setRepo.save(syncSet("people-2", SRC_PEOPLE, DST_USERS,
                "(&(objectClass=inetOrgPerson)(employeeType=staff))", null));
        Membership stale = new Membership();
        stale.setSyncSetId(other.getId());
        stale.setIdentity("00000000-dead-beef-0000-000000000000");
        stale.setSourceDn(SyncDnUtil.normalize(dn("ghost")));
        stale.setTargetDn(live.getTargetDn().toUpperCase(java.util.Locale.ROOT));
        stale.setContentHash(new byte[0]);
        stale.setState(MembershipState.FAILED);
        membershipRepo.save(stale);

        // Ghost has no source entry → OUT. The target DN is owned by a live row, so
        // the engine must drop the stale row and leave the entry untouched.
        engine.process(other.getId(), stale.getIdentity());

        assertThat(membershipRepo.findById(
                new com.ldapportal.entity.MembershipId(other.getId(), stale.getIdentity()))).isEmpty();
        assertThat(target.getEntry("uid=alice," + DST_USERS)).isNotNull();
        assertThat(membership("alice").getState()).isEqualTo(MembershipState.APPLIED);
    }

    @Test
    void failedRename_keepsOldTargetDn_andRetriesAsRename_noOrphan() throws Exception {
        addPerson("bob", "staff", "bob@src");
        engine.process(peopleSet.getId(), dn("bob"));

        // The source gains a sub-OU the target does not have yet; bob moves into it.
        source.add(new Entry("ou=sub," + SRC_PEOPLE, new Attribute("objectClass", "top", "organizationalUnit"),
                new Attribute("ou", "sub")));
        source.modifyDN(dn("bob"), "uid=bob", true, "ou=sub," + SRC_PEOPLE);
        String newSrcDn = "uid=bob,ou=sub," + SRC_PEOPLE;
        engine.process(peopleSet.getId(), newSrcDn);

        // Rename failed (no parent on the target): FAILED, still remembering the OLD DN.
        Membership m = membership("bob");
        assertThat(m.getState()).isEqualTo(MembershipState.FAILED);
        assertThat(m.getTargetDn()).isEqualToIgnoringCase("uid=bob," + DST_USERS);
        assertThat(m.getFailReason()).contains("rename");
        assertThat(target.getEntry("uid=bob," + DST_USERS)).isNotNull();

        // Operator creates the OU; the retry converges as a rename, leaving no orphan.
        target.add(new Entry("ou=sub," + DST_USERS, new Attribute("objectClass", "top", "organizationalUnit"),
                new Attribute("ou", "sub")));
        engine.process(peopleSet.getId(), newSrcDn);

        assertThat(membership("bob").getState()).isEqualTo(MembershipState.APPLIED);
        assertThat(target.getEntry("uid=bob,ou=sub," + DST_USERS)).isNotNull();
        assertThat(target.getEntry("uid=bob," + DST_USERS)).as("old target entry must be gone").isNull();
        assertThat(membershipRepo.findAllBySyncSetId(peopleSet.getId())).hasSize(1);
    }

    @Test
    void unevaluableApplicabilityFilter_isAFault_notAScopeExit() throws Exception {
        addPerson("alice", "staff", "alice@src");
        engine.process(peopleSet.getId(), dn("alice"));

        // Syntactically valid, but the client-side matcher cannot evaluate an
        // approximate-match filter. This must not become OUT → delete.
        peopleSet.setApplicabilityFilter("(cn~=alice)");
        peopleSet = setRepo.save(peopleSet);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> engine.process(peopleSet.getId(), dn("alice")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(target.getEntry("uid=alice," + DST_USERS)).isNotNull();
        assertThat(membership("alice").getState()).isEqualTo(MembershipState.APPLIED);
    }

    // ── Regressions: a source change is never dropped ───────────────────────────

    @Test
    void enqueue_afterWorkerSettledTheKey_insertsAFreshRequest() {
        enqueuer.enqueue(peopleSet.getId(), dn("alice"), null);
        assertThat(requestRepo.claim(peopleSet.getId(), dn("alice"), java.time.OffsetDateTime.now())).isEqualTo(1);
        // Worker finishes and settles the request…
        requestRepo.deleteIfClaimed(peopleSet.getId(), dn("alice"));
        assertThat(requestRepo.findAllBySyncSetId(peopleSet.getId())).isEmpty();

        // …and a re-trigger that races that settle must land as a new, unclaimed row.
        enqueuer.enqueue(peopleSet.getId(), dn("alice"), 7L);

        var rows = requestRepo.findAllBySyncSetId(peopleSet.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getClaimedAt()).isNull();
        assertThat(rows.get(0).getSrcCursor()).isEqualTo(7L);
    }

    @Test
    void enqueue_onClaimedKey_reopensIt_andKeepsHighestCursor() {
        enqueuer.enqueue(peopleSet.getId(), dn("alice"), 10L);
        requestRepo.claim(peopleSet.getId(), dn("alice"), java.time.OffsetDateTime.now());

        enqueuer.enqueue(peopleSet.getId(), dn("alice"), 3L);

        var row = requestRepo.findAllBySyncSetId(peopleSet.getId()).get(0);
        assertThat(row.getClaimedAt()).as("re-trigger must unclaim so the settle misses").isNull();
        assertThat(row.getSrcCursor()).isEqualTo(10L);
    }

    @Test
    void moveOutOfScope_triggeredByOldDn_deletesTarget() throws Exception {
        addPerson("alice", "staff", "alice@src");
        engine.process(peopleSet.getId(), dn("alice"));
        source.add(new Entry("ou=archive," + SRC_BASE, new Attribute("objectClass", "top", "organizationalUnit"),
                new Attribute("ou", "archive")));
        source.modifyDN(dn("alice"), "uid=alice", true, "ou=archive," + SRC_BASE);

        // The app intercept / changelog now emit the OLD DN for a move (see
        // SyncCapturingLdapInterfaceTest); the engine converges it to a scope-exit.
        engine.process(peopleSet.getId(), dn("alice"));

        assertThat(target.getEntry("uid=alice," + DST_USERS)).isNull();
        assertThat(membershipRepo.findAllBySyncSetId(peopleSet.getId())).isEmpty();
    }

    @Test
    void reconcile_pagesThroughScopesLargerThanOnePage() throws Exception {
        DirectoryConnection src = directoryRepo.findAll().stream()
                .filter(d -> d.getSlug().equals("src")).findFirst().orElseThrow();
        src.setPagingSize(2);
        directoryRepo.save(src);
        for (int i = 0; i < 7; i++) {
            addPerson("u" + i, "staff", "u" + i + "@src");
        }

        int enumerated = reconciler.reconcile(peopleSet.getId());

        // 7 people + the scope base OU itself (enumerated, evaluated OUT).
        assertThat(enumerated).isEqualTo(8);
        for (int i = 0; i < 7; i++) {
            assertThat(target.getEntry("uid=u" + i + "," + DST_USERS)).isNotNull();
        }
        assertThat(membershipRepo.findAllBySyncSetId(peopleSet.getId())).hasSize(7);
    }

    @Test
    void reconcile_recordsWhatItDidToEachEntry() throws Exception {
        addPerson("alice", "staff", "alice@src");
        addPerson("bob", "staff", "bob@src");
        addPerson("dave", "staff", "dave@src");
        reconciler.reconcile(peopleSet.getId(), SyncReconcileTrigger.SCHEDULED, null);

        // Then: bob changes, alice leaves the source, carol arrives, and dave's
        // index row is lost while his target entry still matches the source.
        source.modify(dn("bob"), new Modification(ModificationType.REPLACE, "mail", "bob@new"));
        source.delete(dn("alice"));
        addPerson("carol", "staff", "carol@src");
        membershipRepo.delete(membership("dave"));

        reconciler.reconcile(peopleSet.getId(), SyncReconcileTrigger.MANUAL, "operator");

        List<SyncReconcileRun> runs = runRepo.findAll().stream()
                .sorted(java.util.Comparator.comparing(SyncReconcileRun::getStartedAt))
                .toList();
        assertThat(runs).hasSize(2);
        SyncReconcileRun first = runs.get(0);
        assertThat(first.getTrigger()).isEqualTo(SyncReconcileTrigger.SCHEDULED);
        assertThat(first.getAdded()).isEqualTo(3);
        assertThat(first.getManagedBefore()).isZero();
        assertThat(first.getManagedAfter()).isEqualTo(3L);

        SyncReconcileRun run = runs.get(1);
        assertThat(run.getStatus()).isEqualTo(SyncReconcileRunStatus.COMPLETE);
        assertThat(run.getTrigger()).isEqualTo(SyncReconcileTrigger.MANUAL);
        assertThat(run.getRequestedBy()).isEqualTo("operator");
        assertThat(run.getFinishedAt()).isNotNull();
        assertThat(run.isCompleteScan()).isTrue();
        assertThat(run.isGuardTripped()).isFalse();
        // The scope base OU + bob, carol, dave; alice is a not-seen row on top.
        assertThat(run.getSourceCount()).isEqualTo(4);
        assertThat(run.getProcessed()).isEqualTo(5);
        assertThat(run.getManagedBefore()).isEqualTo(2L);
        assertThat(run.getManagedAfter()).isEqualTo(3L);
        assertThat(run.getAdded()).isEqualTo(1);        // carol
        assertThat(run.getModified()).isEqualTo(1);     // bob
        assertThat(run.getDeleted()).isEqualTo(1);      // alice
        assertThat(run.getNoChange()).isEqualTo(1);     // dave, re-adopted without a write
        assertThat(run.getUnchanged()).isEqualTo(1);    // the OU: out of scope, never tracked
        assertThat(run.getFailed() + run.getFaulted() + run.getSkipped() + run.getMoved()
                + run.getHeldForReview() + run.getForgotten()).isZero();
    }

    @Test
    void reconcile_recordsDetails_instanceTimingSettingsAndSampleDns() throws Exception {
        addPerson("alice", "staff", "alice@src");
        addPerson("bob", "staff", "bob@src");
        reconciler.reconcile(peopleSet.getId(), SyncReconcileTrigger.SCHEDULED, null);
        source.delete(dn("alice"));
        addPerson("carol", "staff", "carol@src");
        reconciler.reconcile(peopleSet.getId(), SyncReconcileTrigger.MANUAL, "operator");

        SyncReconcileRun latest = runRepo.findAll().stream()
                .max(java.util.Comparator.comparing(SyncReconcileRun::getStartedAt)).orElseThrow();
        SyncReconcileRunDetail detail = history.detail(peopleSet.getId(), latest.getId());

        assertThat(detail.run().instanceName()).isNotBlank();
        assertThat(detail.run().plannedAt()).isBetween(detail.run().startedAt(), detail.run().finishedAt());
        assertThat(detail.settings().deletePolicy()).isEqualTo("DELETE");
        assertThat(detail.settings().applicabilityFilter()).isEqualTo("(&(objectClass=inetOrgPerson)(employeeType=staff))");
        assertThat(detail.settings().guardMaxDeletes()).isEqualTo(MembershipReconciler.DELETE_GUARD_MAX_ABSOLUTE);
        assertThat(detail.samples().get("ADD")).singleElement().asString().containsIgnoringCase("uid=carol");
        assertThat(detail.samples().get("DELETE")).singleElement().asString().containsIgnoringCase("uid=alice");
        assertThat(detail.failureReasons()).isEmpty();
    }

    @Test
    void reconcile_groupsFailuresByReason() throws Exception {
        addPerson("alice", "staff", "alice@src");
        addPerson("bob", "staff", "bob@src");
        target.delete(DST_USERS); // every placement's parent is missing

        reconciler.reconcile(peopleSet.getId(), SyncReconcileTrigger.SCHEDULED, null);

        SyncReconcileRun run = runRepo.findAll().get(0);
        SyncReconcileRunDetail detail = history.detail(peopleSet.getId(), run.getId());
        assertThat(detail.run().failed()).isEqualTo(2);
        assertThat(detail.failureReasons()).singleElement().satisfies(r -> {
            assertThat(r.reason()).isEqualTo("apply failed: 32 (no such object)");
            assertThat(r.count()).isEqualTo(2);
            assertThat(r.example()).contains("ou=Users");
        });
        assertThat(detail.samples().get("ERROR")).hasSize(2);
    }

    @Test
    void reconcile_isRefused_whileAnotherHoldsTheLease_andNotRecorded() throws Exception {
        addPerson("alice", "staff", "alice@src");
        OffsetDateTime now = OffsetDateTime.now();
        // Another instance's reconcile is running (fresh heartbeat).
        setRepo.claimReconcile(peopleSet.getId(), java.util.UUID.randomUUID(), now, now.minusHours(1));

        assertThatThrownBy(() -> reconciler.reconcile(peopleSet.getId(), SyncReconcileTrigger.SCHEDULED, null))
                .isInstanceOf(com.ldapportal.exception.ConflictException.class);
        assertThat(runRepo.findAll()).isEmpty();
        assertThat(target.getEntry("uid=alice," + DST_USERS)).isNull();
    }

    @Test
    void reconcile_reclaimsAStaleLease() throws Exception {
        addPerson("alice", "staff", "alice@src");
        OffsetDateTime longAgo = OffsetDateTime.now().minus(MembershipReconciler.LEASE_STALE).minusMinutes(1);
        // A reconcile whose server stopped mid-run left its lease behind.
        setRepo.claimReconcile(peopleSet.getId(), java.util.UUID.randomUUID(), longAgo, longAgo.minusHours(1));

        reconciler.reconcile(peopleSet.getId(), SyncReconcileTrigger.SCHEDULED, null);

        assertThat(target.getEntry("uid=alice," + DST_USERS)).isNotNull();
    }

    @Test
    void reconcile_releasesItsLease_andStampsTheRunWithoutBumpingTheVersion() throws Exception {
        addPerson("alice", "staff", "alice@src");
        long versionBefore = setRepo.findById(peopleSet.getId()).orElseThrow().getVersion();

        reconciler.reconcile(peopleSet.getId(), SyncReconcileTrigger.SCHEDULED, null);

        SyncSet after = setRepo.findById(peopleSet.getId()).orElseThrow();
        assertThat(after.getReconcileLeaseToken()).isNull();
        assertThat(after.getReconcileLastRunAt()).isNotNull();
        // No entity save: an admin edit made during the run can't collide with it.
        assertThat(after.getVersion()).isEqualTo(versionBefore);
        // ...and the next run can claim the lease straight away.
        reconciler.reconcile(peopleSet.getId(), SyncReconcileTrigger.MANUAL, "operator");
        assertThat(runRepo.findAll()).hasSize(2);
    }

    @Test
    void reconcile_survivesAnAdminEditOfTheSetDuringTheRun() throws Exception {
        addPerson("alice", "staff", "alice@src");
        // An admin saves the set (bumping its version) while the reconcile works:
        // the old code then failed saving its stale copy of the set at the end.
        org.mockito.Mockito.doAnswer(inv -> {
            SyncSet s = setRepo.findById(peopleSet.getId()).orElseThrow();
            s.setName("people (edited)");
            setRepo.save(s);
            return inv.callRealMethod();
        }).when(engine).process(org.mockito.ArgumentMatchers.eq(peopleSet.getId()),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyBoolean());

        reconciler.reconcile(peopleSet.getId(), SyncReconcileTrigger.SCHEDULED, null);

        assertThat(target.getEntry("uid=alice," + DST_USERS)).isNotNull();
        assertThat(runRepo.findAll()).singleElement()
                .extracting(SyncReconcileRun::getStatus).isEqualTo(SyncReconcileRunStatus.COMPLETE);
        assertThat(setRepo.findById(peopleSet.getId()).orElseThrow().getName()).isEqualTo("people (edited)");
    }

    @Test
    void reconcile_ofDisabledSet_isNotRecorded() {
        peopleSet.setEnabled(false);
        setRepo.save(peopleSet);

        reconciler.reconcile(peopleSet.getId(), SyncReconcileTrigger.SCHEDULED, null);

        assertThat(runRepo.findAll()).isEmpty();
    }

    private String sourceEntryUuid(String uid) throws Exception {
        return source.getEntry("uid=" + uid + "," + SRC_PEOPLE, "entryUUID").getAttributeValue("entryUUID");
    }

    private static InMemoryDirectoryServer startServer(String base) throws Exception {
        InMemoryDirectoryServerConfig cfg = new InMemoryDirectoryServerConfig(base);
        cfg.addAdditionalBindCredentials(BIND_DN, BIND_PASS);
        // Default schema => the server auto-generates entryUUID (the identity).
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

    private SyncSet syncSet(String name, String srcBase, String dstBase, String filter, String refAttrs) {
        SyncSet s = new SyncSet();
        s.setLinkId(link.getId());
        s.setName(name);
        s.setObjectScopeBaseDn(srcBase);
        s.setObjectScope(SyncScope.SUB);
        s.setTargetBaseDn(dstBase);
        s.setApplicabilityFilter(filter);
        s.setReferenceAttributes(refAttrs);
        // These lifecycle tests assert hard deletes; the default is now REVIEW.
        s.setDeletePolicy(com.ldapportal.entity.enums.SyncDeletePolicy.DELETE);
        s.setEnabled(true);
        return s;
    }

    private void addPerson(String uid, String employeeType, String mail) throws Exception {
        source.add(new Entry("uid=" + uid + "," + SRC_PEOPLE,
                new Attribute("objectClass", "top", "person", "organizationalPerson", "inetOrgPerson"),
                new Attribute("uid", uid),
                new Attribute("cn", uid),
                new Attribute("sn", uid),
                new Attribute("employeeType", employeeType),
                new Attribute("mail", mail)));
    }

    private String dn(String uid) {
        return "uid=" + uid + "," + SRC_PEOPLE;
    }

    private Membership membership(String identityOrUid) {
        // Resolve by uid via the people index when a bare uid is passed.
        for (Membership m : membershipRepo.findAll()) {
            if (m.getIdentity().equals(identityOrUid)) {
                return m;
            }
        }
        // Fall back: match by source DN built from a uid.
        return membershipRepo.findAll().stream()
                .filter(m -> m.getSourceDn().contains("uid=" + identityOrUid + ","))
                .findFirst().orElseThrow(() ->
                        new AssertionError("no membership for " + identityOrUid));
    }

}
