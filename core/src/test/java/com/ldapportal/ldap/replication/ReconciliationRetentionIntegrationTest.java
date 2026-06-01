// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.entity.ReconciliationFinding;
import com.ldapportal.entity.ReconciliationRun;
import com.ldapportal.entity.ReplicationLink;
import com.ldapportal.entity.enums.DirectoryType;
import com.ldapportal.entity.enums.ReconcileMode;
import com.ldapportal.entity.enums.ReconciliationFindingStatus;
import com.ldapportal.entity.enums.ReconciliationFindingType;
import com.ldapportal.entity.enums.ReconciliationRunStatus;
import com.ldapportal.entity.enums.ReconciliationRunTrigger;
import com.ldapportal.entity.enums.ReplicationOperationType;
import com.ldapportal.entity.enums.SslMode;
import com.ldapportal.repository.DirectoryConnectionRepository;
import com.ldapportal.repository.ReconciliationFindingRepository;
import com.ldapportal.repository.ReconciliationRunRepository;
import com.ldapportal.repository.ReplicationEventRepository;
import com.ldapportal.repository.ReplicationLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-P3 retention: verifies the JPQL delete behind
 * {@link ReconciliationRetentionScheduler} against a real JPA session (H2
 * under the {@code test} profile). Covers the two subtle parts the Mockito
 * scheduler test can't reach: the {@code NOT IN (subquery)} that spares runs
 * still holding a {@code PROPOSED} finding, and the {@code run_id} FK
 * {@code ON DELETE CASCADE} that takes a deleted run's resolved findings with
 * it.
 *
 * <p>Reuses the entitled-license {@code @TestConfiguration} from
 * {@link ReplicationPersistenceIntegrationTest} so the context is shared with
 * the other directory-sync integration tests.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(ReplicationPersistenceIntegrationTest.DirectorySyncEntitledConfig.class)
class ReconciliationRetentionIntegrationTest {

    @Autowired private ReplicationLinkRepository        linkRepo;
    @Autowired private ReplicationEventRepository       eventRepo;
    @Autowired private ReconciliationRunRepository      runRepo;
    @Autowired private ReconciliationFindingRepository  findingRepo;
    @Autowired private DirectoryConnectionRepository    dirRepo;
    @Autowired private TransactionTemplate              txTemplate;
    @Autowired private JdbcTemplate                     jdbc;

    private UUID linkId;

    @BeforeEach
    void setup() {
        // No @Transactional on the test, so each delete commits. The context
        // is shared with the other directory-sync integration tests on a
        // persistent in-memory H2, so clear every child of replication_links
        // (events + reconciliation runs/findings) before the links/dirs, in
        // FK-dependency order, to drain any residue they left behind.
        findingRepo.deleteAll();
        runRepo.deleteAll();
        eventRepo.deleteAll();
        linkRepo.deleteAll();
        dirRepo.deleteAll();
        txTemplate.executeWithoutResult(tx -> {
            UUID src = dirRepo.save(buildDir("src")).getId();
            UUID tgt = dirRepo.save(buildDir("tgt")).getId();
            ReplicationLink link = new ReplicationLink();
            link.setDisplayName("recon-link");
            link.setSourceDirectory(dirRepo.findById(src).orElseThrow());
            link.setTargetDirectory(dirRepo.findById(tgt).orElseThrow());
            link.setEnabled(true);
            linkId = linkRepo.save(link).getId();
        });
    }

    @Test
    void purge_deletesOldFinishedRuns_andCascadesTheirResolvedFindings() {
        OffsetDateTime now = OffsetDateTime.now();
        UUID oldRun = insertFinishedRun(now.minusDays(100));
        insertFinding(oldRun, ReconciliationFindingStatus.APPLIED);   // resolved → cascades away
        UUID recentRun = insertFinishedRun(now.minusDays(5));

        int deleted = runRepo.deleteFinishedWithoutOpenFindingsBefore(now.minusDays(90));

        assertThat(deleted).isEqualTo(1);
        assertThat(remainingRunIds()).containsExactly(recentRun);
        // The old run's resolved finding went with it (FK ON DELETE CASCADE).
        assertThat(findingRepo.count()).isZero();
    }

    @Test
    void purge_sparesOldRunsThatStillHaveProposedFindings() {
        OffsetDateTime now = OffsetDateTime.now();
        UUID oldRunWithOpen = insertFinishedRun(now.minusDays(100));
        insertFinding(oldRunWithOpen, ReconciliationFindingStatus.PROPOSED);   // open → run spared

        int deleted = runRepo.deleteFinishedWithoutOpenFindingsBefore(now.minusDays(90));

        assertThat(deleted).isZero();
        assertThat(remainingRunIds()).containsExactly(oldRunWithOpen);
        assertThat(findingRepo.count()).isEqualTo(1);
    }

    @Test
    void purge_ignoresUnfinishedRuns_evenWhenOld() {
        OffsetDateTime now = OffsetDateTime.now();
        UUID running = insertRun(ReconciliationRunStatus.RUNNING, now.minusDays(100), null);

        int deleted = runRepo.deleteFinishedWithoutOpenFindingsBefore(now.minusDays(90));

        assertThat(deleted).isZero();
        assertThat(remainingRunIds()).containsExactly(running);
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private Set<UUID> remainingRunIds() {
        return runRepo.findAll().stream().map(ReconciliationRun::getId).collect(Collectors.toSet());
    }

    private UUID insertFinishedRun(OffsetDateTime finishedAt) {
        return insertRun(ReconciliationRunStatus.COMPLETED, finishedAt, finishedAt);
    }

    private UUID insertRun(ReconciliationRunStatus status, OffsetDateTime startedAt, OffsetDateTime finishedAt) {
        UUID id = txTemplate.execute(tx -> {
            ReconciliationRun r = new ReconciliationRun();
            r.setLink(linkRepo.findById(linkId).orElseThrow());
            r.setTrigger(ReconciliationRunTrigger.SCHEDULED);
            r.setMode(ReconcileMode.REVIEW);
            r.setStatus(status);
            return runRepo.save(r).getId();
        });
        // started_at defaults to now() on the entity; finished_at is set by the
        // worker. Backdate both via native SQL so the age filter sees them.
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "UPDATE reconciliation_runs SET started_at = ?, finished_at = ? WHERE id = ?");
            ps.setObject(1, startedAt);
            ps.setObject(2, finishedAt);
            ps.setObject(3, id);
            return ps;
        });
        return id;
    }

    private void insertFinding(UUID runId, ReconciliationFindingStatus status) {
        txTemplate.executeWithoutResult(tx -> {
            ReconciliationFinding f = new ReconciliationFinding();
            f.setRun(runRepo.findById(runId).orElseThrow());
            f.setLink(linkRepo.findById(linkId).orElseThrow());
            f.setFindingType(ReconciliationFindingType.MISSING_IN_TARGET);
            f.setSuggestedOp(ReplicationOperationType.ADD);
            f.setSourceDn("uid=a,dc=src,dc=com");
            f.setTargetDn("uid=a,dc=tgt,dc=com");
            f.setDetail(Map.of("attributes", Map.of("cn", List.of("X"))));
            f.setStatus(status);
            findingRepo.save(f);
        });
    }

    private static DirectoryConnection buildDir(String label) {
        DirectoryConnection dc = new DirectoryConnection();
        dc.setDisplayName("dir-" + label);
        dc.setDirectoryType(DirectoryType.OPENLDAP);
        dc.setHost(label + ".example.com");
        dc.setPort(389);
        dc.setSslMode(SslMode.NONE);
        dc.setBindDn("cn=admin,dc=" + label);
        dc.setBindPasswordEncrypted("enc-placeholder");
        dc.setBaseDn("dc=" + label + ",dc=com");
        dc.setPagingSize(500);
        dc.setPoolMinSize(1);
        dc.setPoolMaxSize(2);
        dc.setPoolConnectTimeoutSeconds(10);
        dc.setPoolResponseTimeoutSeconds(30);
        return dc;
    }
}
