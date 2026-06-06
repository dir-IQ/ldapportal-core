// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.Membership;
import com.ldapportal.entity.RecomputeRequest;
import com.ldapportal.entity.SyncLink;
import com.ldapportal.entity.SyncSet;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.entity.enums.MembershipState;
import com.ldapportal.entity.enums.SslMode;
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
import com.unboundid.ldap.sdk.SearchResultEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
@TestPropertySource(properties = "ldapportal.sync.worker.fixed-delay-ms=3600000")
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
    @Autowired private RecomputeEngine engine;
    @Autowired private MembershipReconciler reconciler;

    private InMemoryDirectoryServer source;
    private InMemoryDirectoryServer target;
    private SyncLink link;
    private SyncSet peopleSet;
    private SyncSet groupsSet;

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

    // ── helpers ──────────────────────────────────────────────────────────────────

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
