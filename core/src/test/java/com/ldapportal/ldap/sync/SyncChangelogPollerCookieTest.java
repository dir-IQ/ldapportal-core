// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.SyncLink;
import com.ldapportal.entity.SyncSet;
import com.ldapportal.entity.enums.ChangelogFormat;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.entity.enums.SyncCaptureMode;
import com.ldapportal.entity.enums.SyncChangelogHealth;
import com.ldapportal.entity.enums.SyncDeletePolicy;
import com.ldapportal.entity.enums.SyncScope;
import com.ldapportal.ldap.changelog.OudCookieChangelogStrategy;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.MembershipRepository;
import com.ldapportal.repository.RecomputeRequestRepository;
import com.ldapportal.repository.SyncLinkRepository;
import com.ldapportal.repository.SyncSetRepository;
import com.ldapportal.service.EncryptionService;
import com.unboundid.asn1.ASN1Exception;
import com.unboundid.asn1.ASN1OctetString;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedSearchEntry;
import com.unboundid.ldap.listener.interceptor.InMemoryInterceptedSearchRequest;
import com.unboundid.ldap.listener.interceptor.InMemoryOperationInterceptor;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Control;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ReadOnlySearchRequest;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Drives {@link SyncChangelogPoller#pollOne} in OUD cookie mode against an
 * UnboundID source fronted by {@link FakeCookieChangelog}, an interceptor that
 * answers {@code cn=changelog} searches the way OUD's external changelog does in
 * cookie mode: changes after the cookie in the request control, a
 * {@code changeLogCookie} per entry, {@code UNWILLING_TO_PERFORM} for a purged
 * cookie, and the head in the Root DSE's {@code lastExternalChangelogCookie}.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "ldapportal.sync.worker.fixed-delay-ms=3600000",
        "ldapportal.sync.reconcile.initial-delay-ms=3600000",
        "ldapportal.sync.changelog.fixed-delay-ms=3600000"})
class SyncChangelogPollerCookieTest {

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
    @Autowired private MembershipReconciler reconciler;

    private final FakeCookieChangelog changelog = new FakeCookieChangelog();
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

        source = startServer(SRC_BASE, changelog);
        target = startServer(DST_BASE, null);
        source.add(new Entry(SRC_PEOPLE, new Attribute("objectClass", "top", "organizationalUnit"),
                new Attribute("ou", "people")));
        target.add(new Entry(DST_USERS, new Attribute("objectClass", "top", "organizationalUnit"),
                new Attribute("ou", "Users")));

        DirectoryConnection src = directoryRepo.save(directory("src", source.getListenPort(), SRC_BASE));
        DirectoryConnection dst = directoryRepo.save(directory("dst", target.getListenPort(), DST_BASE));

        SyncLink l = new SyncLink();
        l.setDisplayName("cookie link");
        l.setSourceDirId(src.getId());
        l.setTargetDirId(dst.getId());
        l.setCaptureMode(SyncCaptureMode.CHANGELOG);
        l.setChangelogFormat(ChangelogFormat.OUD_COOKIE_CHANGELOG);
        l.setChangelogBaseDn("cn=changelog");
        link = linkRepo.save(l);

        SyncSet s = new SyncSet();
        s.setLinkId(link.getId());
        s.setName("people");
        s.setObjectScopeBaseDn(SRC_PEOPLE);
        s.setObjectScope(SyncScope.SUB);
        s.setTargetBaseDn(DST_USERS);
        s.setApplicabilityFilter("(objectClass=inetOrgPerson)");
        s.setDeletePolicy(SyncDeletePolicy.DELETE);
        peopleSet = setRepo.save(s);
    }

    @AfterEach
    void tearDown() {
        if (source != null) source.shutDown(true);
        if (target != null) target.shutDown(true);
    }

    @Test
    void firstPoll_seedsCursorFromHead_andRequestsReconcileWithoutRunningIt() throws Exception {
        addPerson("alice");          // pre-existing: only a reconcile can see it
        changelog.record("add", "uid=alice," + SRC_PEOPLE);
        clearReconcileRequests();

        poller.pollOne(link.getId());

        SyncLink after = reload();
        assertThat(after.getChangelogCursorToken()).isEqualTo(changelog.head());
        assertThat(after.getChangelogHealth()).isEqualTo(SyncChangelogHealth.HEALTHY);
        assertThat(after.getChangelogLastChangeNumber()).isNull();
        assertThat(changelog.searches).isZero(); // seeded from the head, not a replay
        // Reconcile is handed to the scheduler (null last-run = due), not run on
        // the poll thread.
        assertThat(reconcileRequested()).isTrue();
        assertThat(target.getEntry("uid=alice," + DST_USERS)).isNull();
    }

    @Test
    void poll_sendsCookieInCriticalControl_enqueuesChanges_andAdvancesToLastEntryCookie() throws Exception {
        seedCursor();
        addPerson("bob");
        changelog.record("add", "uid=bob," + SRC_PEOPLE);
        addPerson("carol");
        changelog.record("add", "uid=carol," + SRC_PEOPLE);
        String seeded = reload().getChangelogCursorToken();

        poller.pollOne(link.getId());

        assertThat(changelog.lastRequestCookie).isEqualTo(seeded);
        assertThat(changelog.lastRequestCritical).isTrue();
        assertThat(requestRepo.findAllBySyncSetId(peopleSet.getId()))
                .extracting(r -> r.getRequestKey())
                .contains("uid=bob," + SRC_PEOPLE, "uid=carol," + SRC_PEOPLE);
        SyncLink after = reload();
        assertThat(after.getChangelogCursorToken()).isEqualTo(changelog.cookieAt(2));
        assertThat(after.getChangelogHealth()).isEqualTo(SyncChangelogHealth.HEALTHY);

        drain();
        assertThat(target.getEntry("uid=bob," + DST_USERS)).isNotNull();
        assertThat(target.getEntry("uid=carol," + DST_USERS)).isNotNull();

        // Caught up: an empty page keeps the cursor.
        poller.pollOne(link.getId());
        assertThat(reload().getChangelogCursorToken()).isEqualTo(changelog.cookieAt(2));
    }

    @Test
    void poll_modrdnIntoScope_enqueuesPostMoveDn() throws Exception {
        seedCursor();
        source.add(new Entry("ou=staging," + SRC_BASE,
                new Attribute("objectClass", "top", "organizationalUnit"), new Attribute("ou", "staging")));
        addPerson("dave");
        source.modifyDN("uid=dave," + SRC_PEOPLE, "uid=dave", true, "ou=staging," + SRC_BASE);
        source.modifyDN("uid=dave,ou=staging," + SRC_BASE, "uid=dave", true, SRC_PEOPLE);
        changelog.recordModrdn("uid=dave,ou=staging," + SRC_BASE, "uid=dave", SRC_PEOPLE);

        poller.pollOne(link.getId());

        assertThat(requestRepo.findAllBySyncSetId(peopleSet.getId()))
                .extracting(r -> r.getRequestKey())
                .contains("uid=dave," + SRC_PEOPLE);
    }

    @Test
    void refusedCookie_reseedsAndRequestsReconcile_thenARepeatRefusalStallsWithoutRequestingAgain() throws Exception {
        seedCursor();
        String stale = reload().getChangelogCursorToken();
        changelog.record("add", "uid=unrelated,ou=elsewhere," + SRC_BASE);
        changelog.refuse(stale);
        clearReconcileRequests();

        poller.pollOne(link.getId());

        SyncLink afterGap = reload();
        assertThat(afterGap.getChangelogHealth()).isEqualTo(SyncChangelogHealth.GAP_DETECTED);
        assertThat(afterGap.getChangelogCursorToken()).isEqualTo(changelog.head());
        assertThat(reconcileRequested()).isTrue();

        // The fresh head cookie is refused too: a server problem, not a gap.
        // It must not request a reconcile on every poll.
        changelog.refuse(afterGap.getChangelogCursorToken());
        clearReconcileRequests();
        poller.pollOne(link.getId());

        SyncLink afterRepeat = reload();
        assertThat(afterRepeat.getChangelogHealth()).isEqualTo(SyncChangelogHealth.STALLED);
        assertThat(afterRepeat.getChangelogLastError()).contains("refused again");
        assertThat(afterRepeat.getChangelogCursorToken()).isEqualTo(afterGap.getChangelogCursorToken());
        assertThat(reconcileRequested()).isFalse();

        poller.pollOne(link.getId());
        assertThat(reload().getChangelogHealth()).isEqualTo(SyncChangelogHealth.STALLED);
        assertThat(reconcileRequested()).isFalse();
    }

    @Test
    void refusalAfterTheBackoffWindow_isAGapAgain() throws Exception {
        seedCursor();
        String stale = reload().getChangelogCursorToken();
        changelog.refuse(stale);
        poller.pollOne(link.getId());
        assertThat(reload().getChangelogHealth()).isEqualTo(SyncChangelogHealth.GAP_DETECTED);

        // Age the re-seed past the backoff; a refusal now is a fresh gap.
        SyncLink l = reload();
        l.setChangelogLastPolledAt(OffsetDateTime.now().minusHours(2));
        linkRepo.save(l);
        changelog.record("add", "uid=unrelated,ou=elsewhere," + SRC_BASE);
        changelog.refuse(l.getChangelogCursorToken());
        clearReconcileRequests();

        poller.pollOne(link.getId());

        SyncLink after = reload();
        assertThat(after.getChangelogHealth()).isEqualTo(SyncChangelogHealth.GAP_DETECTED);
        assertThat(after.getChangelogCursorToken()).isEqualTo(changelog.head());
        assertThat(reconcileRequested()).isTrue();
    }

    @Test
    void criticalControlRejected_stallsWithTheServerError() throws Exception {
        seedCursor();
        changelog.rejectControl = true;

        poller.pollOne(link.getId());

        SyncLink after = reload();
        assertThat(after.getChangelogHealth()).isEqualTo(SyncChangelogHealth.STALLED);
        assertThat(after.getChangelogLastError()).isNotBlank();
        assertThat(after.getChangelogCursorToken()).isEqualTo(changelog.head());
    }

    @Test
    void sizeLimitedPage_isKept_andThePassReadsOnToTheHead() throws Exception {
        seedCursor();
        for (int i = 0; i < 501; i++) {
            changelog.record("modify", "uid=u" + i + ",ou=elsewhere," + SRC_BASE);
        }

        poller.pollOne(link.getId());

        // Page 1 is cut short at 500; the same pass reads page 2.
        SyncLink after = reload();
        assertThat(changelog.searches).isEqualTo(2);
        assertThat(after.getChangelogCursorToken()).isEqualTo(changelog.cookieAt(501));
        assertThat(after.getChangelogHealth()).isEqualTo(SyncChangelogHealth.HEALTHY);
    }

    @Test
    void timeLimitedPage_isKeptLikeASizeLimitedOne() throws Exception {
        seedCursor();
        changelog.truncateWith = ResultCode.TIME_LIMIT_EXCEEDED;
        for (int i = 0; i < 501; i++) {
            changelog.record("modify", "uid=u" + i + ",ou=elsewhere," + SRC_BASE);
        }

        poller.pollOne(link.getId());

        SyncLink after = reload();
        assertThat(after.getChangelogCursorToken()).isEqualTo(changelog.cookieAt(501));
        assertThat(after.getChangelogHealth()).isEqualTo(SyncChangelogHealth.HEALTHY);
    }

    @Test
    void backlogBeyondOnePass_reportsLagging_andLaterPassesCatchUp() throws Exception {
        seedCursor();
        int total = 20 * 500 + 1; // one record more than a full pass can read
        for (int i = 0; i < total; i++) {
            changelog.record("modify", "uid=u" + i + ",ou=elsewhere," + SRC_BASE);
        }

        poller.pollOne(link.getId());

        SyncLink first = reload();
        assertThat(first.getChangelogHealth()).isEqualTo(SyncChangelogHealth.LAGGING);
        assertThat(first.getChangelogCursorToken()).isNotEqualTo(changelog.head());

        for (int pass = 0; pass < 5 && reload().getChangelogHealth() == SyncChangelogHealth.LAGGING; pass++) {
            poller.pollOne(link.getId());
        }
        SyncLink caughtUp = reload();
        assertThat(caughtUp.getChangelogCursorToken()).isEqualTo(changelog.head());
        assertThat(caughtUp.getChangelogHealth()).isEqualTo(SyncChangelogHealth.HEALTHY);
    }

    @Test
    void cutShortPageWithNoProgress_stallsInsteadOfReportingLagging() throws Exception {
        seedCursor();
        String seeded = reload().getChangelogCursorToken();
        changelog.record("modify", "uid=u0,ou=elsewhere," + SRC_BASE);
        changelog.truncateBeforeFirstEntry = true;

        poller.pollOne(link.getId());

        SyncLink after = reload();
        assertThat(after.getChangelogHealth()).isEqualTo(SyncChangelogHealth.STALLED);
        assertThat(after.getChangelogLastError()).contains("cut short by a server limit");
        assertThat(after.getChangelogCursorToken()).isEqualTo(seeded);
    }

    @Test
    void progressThenACutShortPageWithNoProgress_keepsTheProgress_andTheNextPassStalls() throws Exception {
        seedCursor();
        for (int i = 0; i < 600; i++) {
            changelog.record("modify", "uid=u" + i + ",ou=elsewhere," + SRC_BASE);
        }
        changelog.truncateBeforeFirstEntryFromSearch = 2; // page 1 fine, page 2 cut before any entry

        poller.pollOne(link.getId());

        SyncLink first = reload();
        assertThat(first.getChangelogCursorToken()).isEqualTo(changelog.cookieAt(500));
        assertThat(first.getChangelogHealth()).isEqualTo(SyncChangelogHealth.LAGGING);

        poller.pollOne(link.getId());

        SyncLink second = reload();
        assertThat(second.getChangelogHealth()).isEqualTo(SyncChangelogHealth.STALLED);
        assertThat(second.getChangelogLastError()).contains("cut short by a server limit");
        assertThat(second.getChangelogCursorToken()).isEqualTo(changelog.cookieAt(500));
    }

    // ── reconcile request durability ─────────────────────────────────────────

    @Test
    void reconcileRequest_survivesAStaleEntitySave() {
        // A reconcile (or config edit) that loaded the set before the request
        // saves its copy afterwards; the request must not be overwritten.
        SyncSet stale = setRepo.findById(peopleSet.getId()).orElseThrow();
        clearReconcileRequests();
        setRepo.requestReconcile(link.getId());

        stale.setReconcileCadenceSeconds(7200L);
        setRepo.save(stale);

        assertThat(reconcileRequested()).isTrue();
    }

    @Test
    void reconcileThatStartedBeforeARequest_leavesItPending() {
        clearReconcileRequests();
        long seenAtStart = setRepo.findById(peopleSet.getId()).orElseThrow().getReconcileRequestSeq();
        setRepo.requestReconcile(link.getId());          // raised while that reconcile runs

        setRepo.markReconcileRun(peopleSet.getId(), seenAtStart, true);

        assertThat(reconcileRequested()).isTrue();
    }

    @Test
    void incompleteReconcile_attemptsButDoesNotHandleTheRequest() {
        clearReconcileRequests();
        setRepo.requestReconcile(link.getId());
        long seq = setRepo.findById(peopleSet.getId()).orElseThrow().getReconcileRequestSeq();

        setRepo.markReconcileRun(peopleSet.getId(), seq, false);   // enumeration failed partway

        SyncSet s = setRepo.findById(peopleSet.getId()).orElseThrow();
        assertThat(s.getReconcileAttemptedSeq()).isEqualTo(seq);   // not due at once again…
        assertThat(s.getReconcileHandledSeq()).isLessThan(seq);    // …but still owed a retry
    }

    @Test
    void reconcile_clearsTheRequestItCovers_andAnOlderFinishCantReopenIt() {
        clearReconcileRequests();
        long older = setRepo.findById(peopleSet.getId()).orElseThrow().getReconcileRequestSeq();
        setRepo.requestReconcile(link.getId());

        reconciler.reconcile(peopleSet.getId());
        assertThat(reconcileRequested()).isFalse();

        // A slower reconcile that started earlier finishing now must not move
        // the handled mark backwards.
        setRepo.markReconcileRun(peopleSet.getId(), older, true);
        assertThat(reconcileRequested()).isFalse();
        SyncSet s = setRepo.findById(peopleSet.getId()).orElseThrow();
        assertThat(s.getReconcileAttemptedSeq()).isEqualTo(s.getReconcileRequestSeq());
    }

    @Test
    void sourceWithoutExternalChangelog_isAConfigError() {
        changelog.publishHead = false;

        poller.pollOne(link.getId());

        SyncLink after = reload();
        assertThat(after.getChangelogHealth()).isEqualTo(SyncChangelogHealth.DISABLED_CONFIG_ERROR);
        assertThat(after.getChangelogCursorToken()).isNull();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** First poll seeds the cursor from the head (and requests a reconcile). */
    private void seedCursor() {
        poller.pollOne(link.getId());
        assertThat(reload().getChangelogCursorToken()).isEqualTo(changelog.head());
    }

    /** Mark any pending reconcile request on the set as handled. */
    private void clearReconcileRequests() {
        SyncSet s = setRepo.findById(peopleSet.getId()).orElseThrow();
        setRepo.markReconcileRun(s.getId(), s.getReconcileRequestSeq(), true);
    }

    private boolean reconcileRequested() {
        SyncSet s = setRepo.findById(peopleSet.getId()).orElseThrow();
        return s.getReconcileRequestSeq() > s.getReconcileHandledSeq();
    }

    private SyncLink reload() {
        return linkRepo.findById(link.getId()).orElseThrow();
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
        d.setDirectoryType(DirectoryType.ORACLE_UNIFIED_DIRECTORY);
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

    /**
     * Minimal OUD cookie-mode external changelog. Cookie {@code n} means "seen
     * the first n records"; its text mimics a real cookie so nothing parses it
     * as a number.
     */
    static final class FakeCookieChangelog extends InMemoryOperationInterceptor {

        private final List<Entry> records = new ArrayList<>();
        private final List<String> refused = new ArrayList<>();
        volatile boolean publishHead = true;
        volatile boolean rejectControl;
        volatile ResultCode truncateWith = ResultCode.SIZE_LIMIT_EXCEEDED;
        volatile boolean truncateBeforeFirstEntry;
        /** From this search number on (1-based), cut short before any entry; 0 = never. */
        volatile int truncateBeforeFirstEntryFromSearch;
        volatile String lastRequestCookie;
        volatile boolean lastRequestCritical;
        volatile int searches;

        synchronized void record(String changeType, String targetDn) {
            append(new Attribute("changeType", changeType), new Attribute("targetDN", targetDn));
        }

        synchronized void recordModrdn(String oldDn, String newRdn, String newSuperior) {
            append(new Attribute("changeType", "modrdn"), new Attribute("targetDN", oldDn),
                    new Attribute("newRDN", newRdn), new Attribute("deleteOldRDN", "TRUE"),
                    new Attribute("newSuperior", newSuperior));
        }

        private void append(Attribute... attrs) {
            int n = records.size() + 1;
            Entry e = new Entry("replicationCSN=" + csn(n) + "," + SRC_BASE + ",cn=changelog", attrs);
            e.addAttribute(OudCookieChangelogStrategy.COOKIE_ATTR, cookieAt(n));
            records.add(e);
        }

        synchronized void refuse(String cookie) {
            refused.add(cookie);
        }

        synchronized String head() {
            return cookieAt(records.size());
        }

        String cookieAt(int n) {
            return SRC_BASE + ":" + csn(n) + ";";
        }

        private static String csn(int n) {
            return String.format("0000018c2f3a%04x0001%08x", n, n);
        }

        private int position(String cookie) {
            for (int n = 0; n <= records.size(); n++) {
                if (cookieAt(n).equals(cookie)) {
                    return n;
                }
            }
            return cookie.isEmpty() ? 0 : -1;
        }

        @Override
        public synchronized void processSearchRequest(InMemoryInterceptedSearchRequest request)
                throws LDAPException {
            ReadOnlySearchRequest req = request.getRequest();
            if (!"cn=changelog".equalsIgnoreCase(req.getBaseDN())) {
                return;
            }
            searches++;
            Control c = req.getControl(OudCookieChangelogStrategy.ECL_COOKIE_CONTROL_OID);
            if (c == null) {
                throw new LDAPException(ResultCode.UNWILLING_TO_PERFORM, "test fake only serves cookie mode");
            }
            if (rejectControl) {
                throw new LDAPException(ResultCode.UNAVAILABLE_CRITICAL_EXTENSION,
                        "critical control " + c.getOID() + " is not supported");
            }
            lastRequestCritical = c.isCritical();
            String cookie;
            try {
                cookie = ASN1OctetString.decodeAsOctetString(c.getValue().getValue()).stringValue();
            } catch (ASN1Exception ex) {
                throw new LDAPException(ResultCode.PROTOCOL_ERROR, "cookie control value is not a BER OCTET STRING");
            }
            lastRequestCookie = cookie;
            int from = position(cookie);
            if (from < 0 || refused.contains(cookie)) {
                throw new LDAPException(ResultCode.UNWILLING_TO_PERFORM,
                        "Full resync required. Reason: The provided cookie is older than the start of historical");
            }
            boolean emptyCut = truncateBeforeFirstEntry
                    || (truncateBeforeFirstEntryFromSearch > 0 && searches >= truncateBeforeFirstEntryFromSearch);
            if (emptyCut && from < records.size()) {
                throw new LDAPException(truncateWith, "limit exceeded before any entry");
            }
            int limit = req.getSizeLimit() > 0 ? req.getSizeLimit() : Integer.MAX_VALUE;
            int sent = 0;
            for (int i = from; i < records.size(); i++) {
                if (sent == limit) {
                    throw new LDAPException(truncateWith, "limit exceeded");
                }
                request.sendSearchEntry(records.get(i));
                sent++;
            }
            // Entries are sent; let the backend answer an empty success.
            request.setRequest(new SearchRequest(SRC_BASE, SearchScope.BASE, "(objectClass=nonexistentClass)"));
        }

        @Override
        public synchronized void processSearchEntry(InMemoryInterceptedSearchEntry entry) {
            if (publishHead && entry.getSearchEntry().getDN().isEmpty()) {
                Entry root = entry.getSearchEntry().duplicate();
                root.setAttribute(OudCookieChangelogStrategy.ROOT_DSE_LAST_COOKIE, head());
                entry.setSearchEntry(root);
            }
        }
    }
}
