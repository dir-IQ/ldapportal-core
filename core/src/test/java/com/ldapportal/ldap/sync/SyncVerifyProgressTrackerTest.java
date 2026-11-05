// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.entity.SyncVerifyProgress;
import com.ldapportal.entity.enums.SyncPreviewRunStatus;
import com.ldapportal.entity.enums.SyncVerifyPhase;
import com.ldapportal.repository.SyncVerifyProgressRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SyncVerifyProgressTrackerTest {

    private static final UUID SET = UUID.randomUUID();

    @Mock private SyncVerifyProgressRepository repo;

    private SyncVerifyProgressTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new SyncVerifyProgressTracker(repo, 3_600_000); // flushed by hand
    }

    @AfterEach
    void tearDown() {
        tracker.shutdown();
    }

    @Test
    void aVerification_reportsEachPhase_andItsEnd() {
        when(repo.restart(eq(SET), any(), any())).thenReturn(1);
        SyncVerifyProgressTracker.Handle h = tracker.start(SET);

        h.phase(SyncVerifyPhase.COMPARING);
        h.total(40);
        h.processed(12);
        h.current("uid=bob,ou=Users,dc=dst");
        tracker.flush();

        verify(repo).progress(eq(SET), any(), eq(SyncVerifyPhase.COMPARING), eq(12), eq(40),
                eq("uid=bob,ou=Users,dc=dst"), any());

        h.processed(40);
        h.complete();
        verify(repo).finish(eq(SET), any(), eq(SyncPreviewRunStatus.COMPLETE), eq(40), eq(40), isNull(), any());

        tracker.flush(); // finished: nothing more is written
        verify(repo, never()).progress(any(), any(), any(), eq(40), anyInt(), any(), any());
    }

    @Test
    void aSetsFirstVerification_insertsItsRow() {
        when(repo.restart(eq(SET), any(), any())).thenReturn(0);

        tracker.start(SET);

        verify(repo).saveAndFlush(any(SyncVerifyProgress.class));
    }

    @Test
    void aRunningVerification_whoseServerStopped_readsFailed() {
        SyncVerifyProgress p = new SyncVerifyProgress();
        p.setSyncSetId(SET);
        p.setRunId(UUID.randomUUID());
        p.setStatus(SyncPreviewRunStatus.RUNNING);
        p.setPhase(SyncVerifyPhase.TARGET);
        p.setCurrentDn("uid=x,dc=dst");
        p.setStartedAt(OffsetDateTime.now().minusMinutes(10));
        p.setHeartbeatAt(OffsetDateTime.now().minusMinutes(5));

        var status = SyncVerifyProgressTracker.toStatus(p);

        assertThat(status.status()).isEqualTo(SyncPreviewRunStatus.FAILED);
        assertThat(status.error()).isEqualTo(SyncVerifyProgressTracker.INTERRUPTED);
        assertThat(status.phase()).isEqualTo(SyncVerifyPhase.DONE);
        assertThat(status.currentDn()).isNull();
    }
}
