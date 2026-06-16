// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.SyncLink;
import com.ldapportal.entity.SyncSet;
import com.ldapportal.entity.enums.ChangelogFormat;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.entity.enums.MembershipState;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.entity.enums.SyncCaptureMode;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Drives {@link SyncChangelogPoller#pollOne} against a UnboundID source whose
 * in-memory changelog is enabled: it should emit recompute(targetDN) per change
 * record (including DELETE) and advance the cursor; draining the queue then
 * converges the target — all without LDIF reconstruction or dedup machinery.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "ldapportal.sync.worker.fixed-delay-ms=3600000",
        "ldapportal.sync.reconcile.initial-delay-ms=3600000",
        "ldapportal.sync.changelog.fixed-delay-ms=3600000"})
class SyncChangelogPollerTest {

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
    @Autowired private SyncChangelogPoller poller;
    @Autowired private RecomputeEngine engine;

    private InMemoryDirectoryServer source;
    private InMemoryDirectoryServer target;
    private SyncLink link;
    private SyncSet peopleSet;

    @BeforeEach
    void setUp() throws Exception {
        when(encryptionService.decrypt(anyString())).thenReturn(BIND_PASS);
        membershipRepo.deleteAll();
        requestRepo.deleteAll();
        setRepo.deleteAll();
        linkRepo.deleteAll();
        directoryRepo.deleteAll();

        source = startServer(SRC_BASE, true);
        target = startServer(DST_BASE, false);
        source.add(new Entry(SRC_PEOPLE, new Attribute("objectClass", "top", "organizationalUnit"),
                new Attribute("ou", "people")));
        target.add(new Entry(DST_USERS, new Attribute("objectClass", "top", "organizationalUnit"),
                new Attribute("ou", "Users")));

        DirectoryConnection src = directoryRepo.save(directory("src", source.getListenPort(), SRC_BASE));
        DirectoryConnection dst = directoryRepo.save(directory("dst", target.getListenPort(), DST_BASE));

        SyncLink l = new SyncLink();
        l.setDisplayName("changelog link");
        l.setSourceDirId(src.getId());
        l.setTargetDirId(dst.getId());
        l.setCaptureMode(SyncCaptureMode.CHANGELOG);
        l.setChangelogFormat(ChangelogFormat.DSEE_CHANGELOG);
        l.setChangelogBaseDn("cn=changelog");
        link = linkRepo.save(l);

        SyncSet s = new SyncSet();
        s.setLinkId(link.getId());
        s.setName("people");
        s.setObjectScopeBaseDn(SRC_PEOPLE);
        s.setObjectScope(SyncScope.SUB);
        s.setTargetBaseDn(DST_USERS);
        s.setApplicabilityFilter("(objectClass=inetOrgPerson)");
        // This test asserts a DELETE record removes the target; the default is now REVIEW.
        s.setDeletePolicy(com.ldapportal.entity.enums.SyncDeletePolicy.DELETE);
        peopleSet = setRepo.save(s);
    }

    @AfterEach
    void tearDown() {
        if (source != null) source.shutDown(true);
        if (target != null) target.shutDown(true);
    }

    @Test
    void poll_emitsRecomputePerChange_advancesCursor_andConverges() throws Exception {
        addPerson("alice");
        addPerson("bob");

        poller.pollOne(link.getId());

        // Recompute requests enqueued for the changed DNs, cursor advanced.
        assertThat(requestRepo.findAllBySyncSetId(peopleSet.getId())).hasSizeGreaterThanOrEqualTo(2);
        SyncLink after = linkRepo.findById(link.getId()).orElseThrow();
        assertThat(after.getChangelogLastChangeNumber()).isNotNull().isGreaterThan(0L);
        // The canonical cursor is the opaque token (DSEE: the changeNumber as text).
        assertThat(after.getChangelogCursorToken()).isNotBlank();
        assertThat(SyncChangelogCursor.toChangeNumber(after.getChangelogCursorToken()))
                .isEqualTo(after.getChangelogLastChangeNumber());

        // Drain → target converges.
        drain();
        assertThat(target.getEntry("uid=alice," + DST_USERS)).isNotNull();
        assertThat(target.getEntry("uid=bob," + DST_USERS)).isNotNull();

        // A DELETE record likewise drives a target delete (no source attrs needed).
        source.delete("uid=bob," + SRC_PEOPLE);
        poller.pollOne(link.getId());
        drain();
        assertThat(target.getEntry("uid=bob," + DST_USERS)).isNull();
        assertThat(membershipRepo.findAllBySyncSetId(peopleSet.getId()))
                .noneMatch(m -> m.getState() == MembershipState.FAILED);
    }

    private void drain() {
        var batch = requestRepo.findAll();
        batch.forEach(r -> engine.process(r.getSyncSetId(), r.getRequestKey()));
        requestRepo.deleteAll(batch);
    }

    private void addPerson(String uid) throws Exception {
        source.add(new Entry("uid=" + uid + "," + SRC_PEOPLE,
                new Attribute("objectClass", "top", "person", "organizationalPerson", "inetOrgPerson"),
                new Attribute("uid", uid), new Attribute("cn", uid), new Attribute("sn", uid)));
    }

    private static InMemoryDirectoryServer startServer(String base, boolean changelog) throws Exception {
        InMemoryDirectoryServerConfig cfg = new InMemoryDirectoryServerConfig(base);
        cfg.addAdditionalBindCredentials(BIND_DN, BIND_PASS);
        if (changelog) {
            cfg.setMaxChangeLogEntries(1000);
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
