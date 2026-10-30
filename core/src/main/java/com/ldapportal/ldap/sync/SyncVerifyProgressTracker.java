// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.sync;

import com.ldapportal.dto.sync.SyncVerifyProgressStatus;
import com.ldapportal.entity.SyncVerifyProgress;
import com.ldapportal.entity.enums.SyncPreviewRunStatus;
import com.ldapportal.entity.enums.SyncVerifyPhase;
import com.ldapportal.repository.SyncVerifyProgressRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Records each content verification's progress ({@link SyncContentVerifier}),
 * so the inventory can show it while it runs: the verifier updates a
 * {@link Handle} in memory, and this writes it about every second, from its own
 * thread, whatever the verifier is blocked on (a long LDAP read included).
 *
 * <p>Bookkeeping only: a failure to record is logged and swallowed, never
 * allowed to fail the verification.
 */
@Component
@Slf4j
public class SyncVerifyProgressTracker {

    /** A RUNNING verification silent this long belonged to a server that stopped. */
    static final Duration STALE_AFTER = Duration.ofMinutes(2);
    static final String INTERRUPTED = "Interrupted: the server running this check stopped before it finished.";

    private final SyncVerifyProgressRepository repo;
    private final Map<UUID, Handle> live = new ConcurrentHashMap<>();
    private final ScheduledExecutorService flusher;

    public SyncVerifyProgressTracker(SyncVerifyProgressRepository repo,
                                     @Value("${ldapportal.sync.verify.progress-ms:1000}") long progressMs) {
        this.repo = repo;
        this.flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sync-verify-progress");
            t.setDaemon(true);
            return t;
        });
        long every = Math.max(1, progressMs);
        this.flusher.scheduleWithFixedDelay(this::flush, every, every, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void shutdown() {
        flusher.shutdownNow();
    }

    /** Start recording a verification of {@code setId}, replacing the last one. Never {@code null}. */
    public Handle start(UUID setId) {
        Handle h = new Handle(setId, UUID.randomUUID());
        OffsetDateTime now = OffsetDateTime.now();
        try {
            if (repo.restart(setId, h.runId, now) == 0) {
                SyncVerifyProgress p = new SyncVerifyProgress();
                p.setSyncSetId(setId);
                p.setRunId(h.runId);
                p.setStatus(SyncPreviewRunStatus.RUNNING);
                p.setPhase(SyncVerifyPhase.SOURCE);
                p.setStartedAt(now);
                p.setHeartbeatAt(now);
                repo.saveAndFlush(p);
            }
            live.put(h.runId, h);
        } catch (Exception ex) {
            log.warn("Sync set {}: could not record the content check's progress: {}", setId, ex.toString());
        }
        return h;
    }

    /** The set's latest verification, or {@code null} when it has none. */
    public SyncVerifyProgressStatus status(UUID setId) {
        return repo.findById(setId).map(SyncVerifyProgressTracker::toStatus).orElse(null);
    }

    static SyncVerifyProgressStatus toStatus(SyncVerifyProgress p) {
        SyncPreviewRunStatus status = p.getStatus();
        String error = p.getError();
        if (status == SyncPreviewRunStatus.RUNNING
                && p.getHeartbeatAt().isBefore(OffsetDateTime.now().minus(STALE_AFTER))) {
            status = SyncPreviewRunStatus.FAILED;
            error = INTERRUPTED;
        }
        boolean running = status == SyncPreviewRunStatus.RUNNING;
        return new SyncVerifyProgressStatus(p.getRunId(), status, running ? p.getPhase() : SyncVerifyPhase.DONE,
                p.getProcessed(), p.getTotal(), running ? p.getCurrentDn() : null, error, p.getStartedAt(),
                p.getHeartbeatAt(), p.getFinishedAt());
    }

    /** Write every running verification's progress (and keep it visibly alive). */
    void flush() {
        OffsetDateTime now = OffsetDateTime.now();
        live.forEach((runId, h) -> {
            try {
                repo.progress(h.setId, runId, h.phase, h.processed, h.total, h.currentDn, now);
            } catch (Exception ex) {
                log.warn("Sync set {}: content check progress update failed: {}", h.setId, ex.toString());
            }
        });
    }

    private void finish(Handle h, SyncPreviewRunStatus status, String error) {
        live.remove(h.runId);
        try {
            repo.finish(h.setId, h.runId, status, h.processed, h.total, error, OffsetDateTime.now());
        } catch (Exception ex) {
            log.warn("Sync set {}: could not record the content check's end: {}", h.setId, ex.toString());
        }
    }

    /** One verification's progress, updated by the verifier as it goes. */
    public final class Handle {
        private final UUID setId;
        private final UUID runId;
        private volatile SyncVerifyPhase phase = SyncVerifyPhase.SOURCE;
        private volatile int processed;
        private volatile int total;
        private volatile String currentDn;

        private Handle(UUID setId, UUID runId) {
            this.setId = setId;
            this.runId = runId;
        }

        /** Moved on to {@code next}; counts start again from zero. */
        public void phase(SyncVerifyPhase next) {
            this.phase = next;
            this.processed = 0;
            this.currentDn = null;
        }

        /** Entries read or compared so far in this phase. */
        public void processed(int n) {
            this.processed = n;
        }

        /** Entries the comparison will go through. */
        public void total(int n) {
            this.total = n;
        }

        /** The entry being compared now. */
        public void current(String dn) {
            this.currentDn = dn;
        }

        public void complete() {
            finish(this, SyncPreviewRunStatus.COMPLETE, null);
        }

        public void fail(String error) {
            finish(this, SyncPreviewRunStatus.FAILED, error);
        }
    }
}
