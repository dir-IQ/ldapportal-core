// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.dto.sync.SyncPreviewStatus;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.SyncLink;
import com.ldapportal.entity.SyncSet;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.entity.enums.SyncDeletePolicy;
import com.ldapportal.entity.enums.SyncPreviewRunStatus;
import com.ldapportal.entity.enums.SyncScope;
import com.ldapportal.exception.ResourceNotFoundException;
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
import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedSearchRequest;
import com.unboundid.ldap.listener.interceptor.InMemoryOperationInterceptor;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.SearchScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * The preview as a background job: liveness, consistency between a page and
 * its run, and when a completed run counts as outdated. Uses short timings
 * (stale after 600 ms, heartbeat every 50 ms) so liveness can be observed.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "ldapportal.sync.worker.fixed-delay-ms=3600000",
        "ldapportal.sync.reconcile.initial-delay-ms=3600000",
        "ldapportal.sync.changelog.fixed-delay-ms=3600000",
        "ldapportal.sync.preview.stale-after-ms=600",
        "ldapportal.sync.preview.heartbeat-ms=50",
        "ldapportal.sync.preview.progress-ms=50"})
class SyncPreviewJobTest {

    private static final String SRC_BASE = "dc=src,dc=com";
    private static final String DST_BASE = "dc=dst,dc=com";
    private static final String SRC_PEOPLE = "ou=people," + SRC_BASE;
    private static final String DST_USERS = "ou=Users," + DST_BASE;
    private static final String BIND_DN = "cn=admin,dc=com";
    private static final String BIND_PASS = "adminpass";

    @MockitoBean private EncryptionService encryptionService;
    @MockitoSpyBean private SyncPreviewEntryRowRepository entryRepo;
    @Autowired private SyncPreviewRunRepository runRepo;
    @Autowired private DirectoryConnectionRepository directoryRepo;
    @Autowired private SyncLinkRepository linkRepo;
    @Autowired private SyncSetRepository setRepo;
    @Autowired private MembershipRepository membershipRepo;
    @Autowired private RecomputeRequestRepository requestRepo;
    @Autowired private SyncPreviewService preview;
    @Autowired private TaskScheduler taskScheduler;

    private InMemoryDirectoryServer source;
    private InMemoryDirectoryServer target;
    private DirectoryConnection dst;
    private SyncLink link;
    private SyncSet set;
    /** Milliseconds each subtree scan of the source takes, to stretch the enumeration. */
    private volatile long scanDelayMs;
    /** Milliseconds each entry read on the target takes, to stretch one entry's dry run. */
    private volatile long targetReadDelayMs;

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
        scanDelayMs = 0;
        targetReadDelayMs = 0;

        source = startServer(SRC_BASE, new InMemoryOperationInterceptor() {
            @Override
            public void processSearchRequest(InMemoryInterceptedSearchRequest request) {
                if (scanDelayMs > 0 && request.getRequest().getScope() == SearchScope.SUB) {
                    try {
                        Thread.sleep(scanDelayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        });
        target = startServer(DST_BASE, new InMemoryOperationInterceptor() {
            @Override
            public void processSearchRequest(InMemoryInterceptedSearchRequest request) {
                if (targetReadDelayMs > 0 && request.getRequest().getScope() == SearchScope.BASE
                        && request.getRequest().getBaseDN().toLowerCase().endsWith(DST_USERS.toLowerCase())) {
                    try {
                        Thread.sleep(targetReadDelayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        });
        source.add(new Entry(SRC_PEOPLE, new Attribute("objectClass", "top", "organizationalUnit"),
                new Attribute("ou", "people")));
        target.add(new Entry(DST_USERS, new Attribute("objectClass", "top", "organizationalUnit"),
                new Attribute("ou", "Users")));
        source.add(new Entry("uid=alice," + SRC_PEOPLE,
                new Attribute("objectClass", "top", "person", "organizationalPerson", "inetOrgPerson"),
                new Attribute("uid", "alice"), new Attribute("cn", "alice"), new Attribute("sn", "alice")));

        DirectoryConnection src = directoryRepo.save(directory("src", source.getListenPort(), SRC_BASE));
        dst = directoryRepo.save(directory("dst", target.getListenPort(), DST_BASE));
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
        s.setDeletePolicy(SyncDeletePolicy.DELETE);
        set = setRepo.save(s);
    }

    @AfterEach
    void tearDown() {
        if (source != null) source.shutDown(true);
        if (target != null) target.shutDown(true);
    }

    @Test
    void aLongRun_staysAlive_whileTheAppSchedulerIsBusy() throws Exception {
        // Occupy the app's (single-threaded) scheduler, as a long reconcile sweep does.
        CountDownLatch busy = new CountDownLatch(1);
        taskScheduler.schedule(() -> {
            busy.countDown();
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, Instant.now());
        assertThat(busy.await(5, TimeUnit.SECONDS)).isTrue();
        scanDelayMs = 2_000; // the source scan alone outlasts the 600 ms stale window

        preview.start(set.getId(), "ann");

        List<SyncPreviewRunStatus> seen = new ArrayList<>();
        SyncPreviewStatus status = preview.status(set.getId());
        long deadline = System.currentTimeMillis() + 15_000;
        while (status.status() == SyncPreviewRunStatus.RUNNING && System.currentTimeMillis() < deadline) {
            seen.add(status.status());
            Thread.sleep(50);
            status = preview.status(set.getId());
        }
        seen.add(status.status());

        assertThat(seen).doesNotContain(SyncPreviewRunStatus.FAILED);
        assertThat(status.status()).isEqualTo(SyncPreviewRunStatus.COMPLETE);
        assertThat(status.total()).isEqualTo(1);
    }

    @Test
    void aSlowEntry_isNamedInTheProgress_whileItIsChecked() throws Exception {
        // One entry whose dry run takes well over the progress interval, as a large group does.
        targetReadDelayMs = 1_500;

        preview.start(set.getId(), "ann");

        SyncPreviewStatus status = preview.status(set.getId());
        SyncPreviewStatus checking = null;
        long deadline = System.currentTimeMillis() + 15_000;
        while (status.status() == SyncPreviewRunStatus.RUNNING && System.currentTimeMillis() < deadline) {
            if (status.total() > 0 && status.currentDn() != null) {
                checking = status;
            }
            Thread.sleep(50);
            status = preview.status(set.getId());
        }

        assertThat(checking).as("a progress read naming the entry being checked").isNotNull();
        assertThat(checking.currentDn()).isEqualToIgnoringCase("uid=alice," + SRC_PEOPLE);
        assertThat(checking.processed()).isZero();
        assertThat(checking.total()).isEqualTo(1);
        assertThat(checking.heartbeatAt()).isNotNull();
        assertThat(checking.asOf()).isNotNull();
        assertThat(status.status()).isEqualTo(SyncPreviewRunStatus.COMPLETE);
        assertThat(status.processed()).isEqualTo(1);
        assertThat(status.currentDn()).isNull();
    }

    @Test
    void aPageReadWhileANewRunStarts_isReportedAsReplaced_notEmpty() {
        assertThat(preview.runNow(set.getId()).status()).isEqualTo(SyncPreviewRunStatus.COMPLETE);
        // Between reading the run and reading its entries, someone starts a new run,
        // which deletes the old run's entries as it begins: the read finds none.
        doAnswer(inv -> {
            OffsetDateTime now = OffsetDateTime.now();
            runRepo.claim(set.getId(), UUID.randomUUID(), "bob", now, now);
            return List.of();
        }).when(entryRepo).findByRunIdOrderByOrdinalAsc(any(UUID.class), any(Pageable.class));

        assertThatThrownBy(() -> preview.page(set.getId(), 0, 50, false))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("newer run");
    }

    @Test
    void outdated_followsTheLinksDirectories_butNotItsChangelogBookkeeping() throws Exception {
        assertThat(preview.runNow(set.getId()).outdated()).isFalse();
        Thread.sleep(5); // timestamps after the run's start

        // The changelog poller saves the link on every poll: not a reason.
        SyncLink polled = linkRepo.findById(link.getId()).orElseThrow();
        polled.setChangelogLastError("transient");
        linkRepo.save(polled);
        assertThat(preview.status(set.getId()).outdated()).isFalse();

        // An edit to the target directory's connection settings is.
        DirectoryConnection edited = directoryRepo.findById(dst.getId()).orElseThrow();
        edited.setDisplayName("dst (renamed)");
        directoryRepo.save(edited);
        assertThat(preview.status(set.getId()).outdated()).isTrue();

        // So is repointing the link at another directory.
        assertThat(preview.runNow(set.getId()).outdated()).isFalse();
        DirectoryConnection other = directoryRepo.save(directory("other", target.getListenPort(), DST_BASE));
        SyncLink repointed = linkRepo.findById(link.getId()).orElseThrow();
        repointed.setTargetDirId(other.getId());
        linkRepo.save(repointed);
        assertThat(preview.status(set.getId()).outdated()).isTrue();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

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
