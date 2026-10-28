// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.dto.sync.SyncReconcilePreview;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.SyncLink;
import com.ldapportal.entity.SyncSet;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.entity.enums.SyncHoldForReview;
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
 * The impact preview must plan for a <em>disabled</em> set or link: the UI
 * creates sets disabled and runs this preview as the confirmation step when the
 * operator enables one. A preview that returned "unavailable" zeros for a
 * disabled set made that confirmation never fire.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "ldapportal.sync.worker.fixed-delay-ms=3600000",
        "ldapportal.sync.reconcile.initial-delay-ms=3600000",
        "ldapportal.sync.changelog.fixed-delay-ms=3600000"})
class MembershipReconcilerPreviewTest {

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

    private InMemoryDirectoryServer source;
    private InMemoryDirectoryServer target;
    private SyncLink link;
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
        addPerson("alice");
        addPerson("bob");

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
        s.setApplicabilityFilter("(objectClass=inetOrgPerson)");
        s.setHoldForReview(SyncHoldForReview.NONE);
        set = setRepo.save(s);

        // Converge once while enabled, so the set manages alice and bob.
        reconciler.reconcile(set.getId());
        assertThat(target.getEntry("uid=bob," + DST_USERS)).isNotNull();
    }

    @AfterEach
    void tearDown() {
        if (source != null) source.shutDown(true);
        if (target != null) target.shutDown(true);
    }

    @Test
    void preview_plansForADisabledSet_withoutWriting() throws Exception {
        disableSet();
        source.delete("uid=bob," + SRC_PEOPLE);   // bob would be deleted on enable
        addPerson("carol");                       // carol would be added

        SyncReconcilePreview p = reconciler.preview(set.getId());

        assertThat(p.completeScan()).isTrue();
        assertThat(p.managedCount()).isEqualTo(2);
        assertThat(p.plannedAdds()).isEqualTo(1);
        assertThat(p.plannedDeletes()).isEqualTo(1);
        assertThat(p.sampleDeleteDns()).containsExactly("uid=bob," + DST_USERS);
        // Read-only: nothing applied.
        assertThat(target.getEntry("uid=bob," + DST_USERS)).isNotNull();
        assertThat(target.getEntry("uid=carol," + DST_USERS)).isNull();
    }

    @Test
    void preview_plansWhenTheLinkIsDisabledToo() throws Exception {
        disableSet();
        SyncLink l = linkRepo.findById(link.getId()).orElseThrow();
        l.setEnabled(false);
        linkRepo.save(l);
        source.delete("uid=bob," + SRC_PEOPLE);

        SyncReconcilePreview p = reconciler.preview(set.getId());

        assertThat(p.completeScan()).isTrue();
        assertThat(p.plannedDeletes()).isEqualTo(1);
    }

    @Test
    void reconcile_stillSkipsADisabledSet() throws Exception {
        disableSet();
        source.delete("uid=bob," + SRC_PEOPLE);

        assertThat(reconciler.reconcile(set.getId())).isZero();

        assertThat(target.getEntry("uid=bob," + DST_USERS)).isNotNull();
    }

    private void disableSet() {
        SyncSet s = setRepo.findById(set.getId()).orElseThrow();
        s.setEnabled(false);
        setRepo.save(s);
    }

    private void addPerson(String uid) throws Exception {
        source.add(new Entry("uid=" + uid + "," + SRC_PEOPLE,
                new Attribute("objectClass", "top", "person", "organizationalPerson", "inetOrgPerson"),
                new Attribute("uid", uid), new Attribute("cn", uid), new Attribute("sn", uid)));
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
