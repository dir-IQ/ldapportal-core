// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.service;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PrincipalType;
import com.ldapportal.dto.diff.DirectoryDiffEntryDetail;
import com.ldapportal.dto.diff.DirectoryDiffEntryResponse;
import com.ldapportal.dto.diff.DirectoryDiffLiveEntry;
import com.ldapportal.dto.diff.DirectoryDiffRequest;
import com.ldapportal.dto.diff.DirectoryDiffRunResponse;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.enums.DirectoryDiffCategory;
import com.ldapportal.entity.enums.DirectoryDiffPairing;
import com.ldapportal.entity.enums.DirectoryDiffStatus;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.DirectoryDiffRunRepository;
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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "ldapportal.directory-diff.keep-per-pair=2")
class DirectoryDiffServiceIntegrationTest {

    private static final String BASE_A = "dc=a,dc=com";
    private static final String BASE_B = "dc=b,dc=com";
    private static final String BIND_DN = "cn=admin,dc=com";
    private static final String BIND_PASS = "adminpass";
    private static final AuthPrincipal SUPERADMIN =
            new AuthPrincipal(PrincipalType.SUPERADMIN, UUID.randomUUID(), "superadmin");

    @MockitoBean private EncryptionService encryptionService;
    @Autowired private DirectoryConnectionRepository directoryRepo;
    @Autowired private DirectoryDiffRunRepository runRepo;
    @Autowired private DirectoryDiffService service;

    private InMemoryDirectoryServer serverA;
    private InMemoryDirectoryServer serverB;
    private DirectoryConnection dirA;
    private DirectoryConnection dirB;

    @BeforeEach
    void setUp() throws Exception {
        when(encryptionService.decrypt(anyString())).thenReturn(BIND_PASS);
        runRepo.deleteAll();
        directoryRepo.deleteAll();

        serverA = startServer(BASE_A);
        serverB = startServer(BASE_B);
        for (InMemoryDirectoryServer s : List.of(serverA, serverB)) {
            String base = s == serverA ? BASE_A : BASE_B;
            s.add(new Entry("ou=people," + base, new Attribute("objectClass", "top", "organizationalUnit"),
                    new Attribute("ou", "people")));
        }
        serverA.add(person("alice", BASE_A, "Alice", "1"));
        serverB.add(person("alice", BASE_B, "Alice", "1"));
        serverA.add(person("bob", BASE_A, "Bob", "2"));
        serverB.add(person("bob", BASE_B, "Robert", "2"));
        serverA.add(person("carol", BASE_A, "Carol", "3"));
        serverB.add(person("dave", BASE_B, "Dave", "4"));
        serverA.modify("uid=alice,ou=people," + BASE_A,
                new Modification(ModificationType.ADD, "userPassword", "secretA"));
        serverB.modify("uid=alice,ou=people," + BASE_B,
                new Modification(ModificationType.ADD, "userPassword", "secretB"));

        dirA = directoryRepo.save(directory("a", serverA.getListenPort(), BASE_A));
        dirB = directoryRepo.save(directory("b", serverB.getListenPort(), BASE_B));
    }

    @AfterEach
    void tearDown() {
        serverA.shutDown(true);
        serverB.shutDown(true);
    }

    @Test
    void comparesTwoDirectoriesByDn() throws Exception {
        DirectoryDiffRunResponse run = finish(service.start(request(DirectoryDiffPairing.DN, null, null), SUPERADMIN));

        assertThat(run.status()).isEqualTo(DirectoryDiffStatus.COMPLETE);
        // ou=people and alice are the same; the bases (dc=a vs dc=b) and bob differ; carol only A; dave only B
        assertThat(run.counts().same()).isEqualTo(2);
        assertThat(run.counts().different()).isEqualTo(2);
        assertThat(run.counts().onlyA()).isEqualTo(1);
        assertThat(run.counts().onlyB()).isEqualTo(1);
        assertThat(run.attributeCounts()).extracting(DirectoryDiffRunResponse.AttributeCount::name)
                .containsExactlyInAnyOrder("cn", "dc");
        // userPassword differs on alice but is never compared
        assertThat(service.entries(run.id(), DirectoryDiffCategory.SAME, null, "alice", 0, 50).content()).hasSize(1);

        List<DirectoryDiffEntryResponse> different =
                service.entries(run.id(), DirectoryDiffCategory.DIFFERENT, "cn", null, 0, 50).content();
        assertThat(different).singleElement().satisfies(e -> {
            assertThat(e.dnA()).isEqualTo("uid=bob,ou=people," + BASE_A);
            assertThat(e.dnB()).isEqualTo("uid=bob,ou=people," + BASE_B);
            assertThat(e.attributes()).containsExactly("cn");
        });
        assertThat(service.entries(run.id(), DirectoryDiffCategory.DIFFERENT, null, null, 0, 50).content())
                .hasSize(2);
        assertThat(service.entries(run.id(), DirectoryDiffCategory.DIFFERENT, "mail", null, 0, 50).content())
                .isEmpty();
        assertThat(service.entries(run.id(), DirectoryDiffCategory.SAME, null, "ALICE", 0, 50).content())
                .hasSize(1);
        assertThat(service.entries(run.id(), DirectoryDiffCategory.ONLY_A, null, null, 0, 50).content())
                .extracting(DirectoryDiffEntryResponse::dnA).containsExactly("uid=carol,ou=people," + BASE_A);
        assertThat(service.entries(run.id(), DirectoryDiffCategory.ONLY_B, null, null, 0, 50).content())
                .extracting(DirectoryDiffEntryResponse::dnB).containsExactly("uid=dave,ou=people," + BASE_B);

        int bobSeq = different.get(0).seq();
        DirectoryDiffEntryDetail detail = service.entry(run.id(), bobSeq);
        assertThat(detail.diffs()).singleElement().satisfies(d -> {
            assertThat(d.onlyA()).containsExactly("Bob");
            assertThat(d.onlyB()).containsExactly("Robert");
        });
        assertThat(detail.sameAttributes()).contains("sn", "employeeNumber");

        // fixed since the run: the live re-read says so
        serverB.modify("uid=bob,ou=people," + BASE_B, new Modification(ModificationType.REPLACE, "cn", "Bob"));
        DirectoryDiffLiveEntry live = service.live(run.id(), bobSeq);
        assertThat(live.category()).isEqualTo(DirectoryDiffCategory.SAME);
        assertThat(live.same()).extracting(DirectoryDiffLiveEntry.SameAttribute::name).contains("cn");
        assertThat(live.same()).extracting(DirectoryDiffLiveEntry.SameAttribute::name)
                .doesNotContain("userPassword");
    }

    @Test
    void pairsByAttributeAcrossDifferentDns() throws Exception {
        serverB.add(new Entry("ou=staff," + BASE_B, new Attribute("objectClass", "top", "organizationalUnit"),
                new Attribute("ou", "staff")));
        serverB.add(new Entry("cn=Carol,ou=staff," + BASE_B,
                new Attribute("objectClass", "top", "person", "organizationalPerson", "inetOrgPerson"),
                new Attribute("uid", "carol"), new Attribute("cn", "Carol"), new Attribute("sn", "carol"), new Attribute("employeeNumber", "3")));
        DirectoryDiffRequest req = new DirectoryDiffRequest(
                new DirectoryDiffRequest.Side(dirA.getId(), "ou=people," + BASE_A, "ONE", "(objectClass=inetOrgPerson)"),
                new DirectoryDiffRequest.Side(dirB.getId(), BASE_B, "SUB", "(objectClass=inetOrgPerson)"),
                DirectoryDiffPairing.ATTRIBUTE, "employeeNumber", null, null);

        DirectoryDiffRunResponse run = finish(service.start(req, SUPERADMIN));

        assertThat(run.status()).isEqualTo(DirectoryDiffStatus.COMPLETE);
        assertThat(run.counts().same()).isEqualTo(2); // alice, carol
        assertThat(run.counts().different()).isEqualTo(1); // bob
        assertThat(run.counts().onlyB()).isEqualTo(1); // dave
        assertThat(run.counts().onlyA()).isZero();
    }

    @Test
    void onlyListedAttributesCount() throws Exception {
        DirectoryDiffRunResponse run = finish(service.start(
                request(DirectoryDiffPairing.DN, List.of("sn"), null), SUPERADMIN));
        assertThat(run.counts().different()).isZero();
        assertThat(run.counts().same()).isEqualTo(4);
    }

    @Test
    void rejectsRequestsThatCantRun() {
        assertThatThrownBy(() -> service.start(new DirectoryDiffRequest(
                new DirectoryDiffRequest.Side(dirA.getId(), null, null, null),
                new DirectoryDiffRequest.Side(dirA.getId(), null, null, null),
                null, null, null, null), SUPERADMIN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.start(new DirectoryDiffRequest(
                new DirectoryDiffRequest.Side(dirA.getId(), null, null, "(broken"),
                new DirectoryDiffRequest.Side(dirB.getId(), null, null, null),
                null, null, null, null), SUPERADMIN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.start(new DirectoryDiffRequest(
                new DirectoryDiffRequest.Side(dirA.getId(), "dc=elsewhere", null, null),
                new DirectoryDiffRequest.Side(dirB.getId(), null, null, null),
                null, null, null, null), SUPERADMIN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.start(new DirectoryDiffRequest(
                new DirectoryDiffRequest.Side(dirA.getId(), null, "BASE", null),
                new DirectoryDiffRequest.Side(dirB.getId(), null, null, null),
                null, null, null, null), SUPERADMIN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.start(request(DirectoryDiffPairing.ATTRIBUTE, null, null), SUPERADMIN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keepsOnlyTheLatestRunsPerPair() throws Exception {
        UUID first = finish(service.start(request(DirectoryDiffPairing.DN, null, null), SUPERADMIN)).id();
        finish(service.start(request(DirectoryDiffPairing.DN, null, null), SUPERADMIN));
        finish(service.start(request(DirectoryDiffPairing.DN, null, null), SUPERADMIN));
        assertThat(runRepo.findById(first)).isEmpty();
        assertThat(runRepo.count()).isEqualTo(2);
    }

    @Test
    void failedRunsNeverPushOutTheLastGoodResult() {
        // keep-per-pair=2. Newest first: 3 failed, then 2 complete, then an old complete one.
        OffsetDateTime t = OffsetDateTime.now().minusHours(1);
        UUID failed1 = savedRun(DirectoryDiffStatus.FAILED, t.plusMinutes(6));
        UUID failed2 = savedRun(DirectoryDiffStatus.CANCELLED, t.plusMinutes(5));
        UUID failed3 = savedRun(DirectoryDiffStatus.FAILED, t.plusMinutes(4));
        UUID good1 = savedRun(DirectoryDiffStatus.COMPLETE, t.plusMinutes(3));
        UUID good2 = savedRun(DirectoryDiffStatus.COMPLETE, t.plusMinutes(2));
        UUID good3 = savedRun(DirectoryDiffStatus.COMPLETE, t.plusMinutes(1));

        service.prune(dirA.getId(), dirB.getId());

        assertThat(runRepo.findAll()).extracting(r -> r.getId())
                .containsExactlyInAnyOrder(failed1, failed2, good1, good2);
        assertThat(runRepo.findById(failed3)).isEmpty();
        assertThat(runRepo.findById(good3)).isEmpty();
    }

    @Test
    void compactKeepsOnlyComparedAndPairingAttributes() {
        Entry e = new Entry("uid=a,dc=x", new Attribute("cn", "A"), new Attribute("userPassword", "secret"),
                new Attribute("modifyTimestamp", "20260101000000Z"), new Attribute("employeeNumber", "7"),
                new Attribute("description", "d"));
        Entry compact = DirectoryDiffService.compact(e,
                name -> List.of("cn", "employeenumber").contains(name));
        assertThat(compact.getDN()).isEqualTo("uid=a,dc=x");
        assertThat(compact.getAttributes()).extracting(Attribute::getName)
                .containsExactlyInAnyOrder("cn", "employeeNumber");
        assertThat(DirectoryDiffService.estimateBytes(compact)).isLessThan(DirectoryDiffService.estimateBytes(e));
    }

    private UUID savedRun(DirectoryDiffStatus status, OffsetDateTime startedAt) {
        com.ldapportal.entity.DirectoryDiffRun run = new com.ldapportal.entity.DirectoryDiffRun();
        run.setId(UUID.randomUUID());
        run.setStatus(status);
        run.setPhase(com.ldapportal.entity.enums.DirectoryDiffPhase.DONE);
        run.setStartedAt(startedAt);
        run.setHeartbeatAt(startedAt);
        run.setFinishedAt(startedAt);
        run.setDirectoryAId(dirA.getId());
        run.setDirectoryBId(dirB.getId());
        run.setBaseA(BASE_A);
        run.setBaseB(BASE_B);
        run.setPairing(DirectoryDiffPairing.DN);
        run.setSettings("{}");
        return runRepo.save(run).getId();
    }

    private DirectoryDiffRequest request(DirectoryDiffPairing pairing, List<String> only, List<String> ignore) {
        return new DirectoryDiffRequest(new DirectoryDiffRequest.Side(dirA.getId(), null, null, null),
                new DirectoryDiffRequest.Side(dirB.getId(), null, null, null), pairing, null, only, ignore);
    }

    private DirectoryDiffRunResponse finish(DirectoryDiffRunResponse started) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        DirectoryDiffRunResponse run = started;
        while (run.status() == DirectoryDiffStatus.RUNNING && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            run = service.get(started.id());
        }
        assertThat(run.status()).as("run finished: %s", run.error()).isNotEqualTo(DirectoryDiffStatus.RUNNING);
        return run;
    }

    private static Entry person(String uid, String base, String cn, String employeeNumber) {
        return new Entry("uid=" + uid + ",ou=people," + base,
                new Attribute("objectClass", "top", "person", "organizationalPerson", "inetOrgPerson"),
                new Attribute("uid", uid), new Attribute("cn", cn), new Attribute("sn", uid),
                new Attribute("employeeNumber", employeeNumber));
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

    private static DirectoryConnection directory(String slug, int port, String base) {
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
