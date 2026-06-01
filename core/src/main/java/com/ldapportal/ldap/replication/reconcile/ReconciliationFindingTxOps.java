// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication.reconcile;

import com.ldapportal.entity.ReconciliationFinding;
import com.ldapportal.entity.ReconciliationRun;
import com.ldapportal.entity.ReplicationEvent;
import com.ldapportal.entity.ReplicationLink;
import com.ldapportal.entity.enums.ReconcileDeleteAction;
import com.ldapportal.entity.enums.ReconcileMode;
import com.ldapportal.entity.enums.ReconciliationFindingStatus;
import com.ldapportal.entity.enums.ReconciliationFindingType;
import com.ldapportal.entity.enums.ReplicationEnqueueSource;
import com.ldapportal.entity.enums.ReplicationEventStatus;
import com.ldapportal.entity.enums.ReplicationOperationType;
import com.ldapportal.repository.ReconciliationFindingRepository;
import com.ldapportal.repository.ReplicationEventRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Committed boundaries for reconciliation findings (R-P2): persist a run's
 * findings (auto-applying per mode / delete-action), and the operator
 * apply / dismiss transitions. A corrective action always rides the existing
 * {@code replication_events} queue — built here the same way
 * {@code ReplicationEventPersister} builds one — and the finding is linked to
 * its event via {@code event_id}.
 */
@Component
@RequiredArgsConstructor
public class ReconciliationFindingTxOps {

    private final ReconciliationFindingRepository findingRepo;
    private final ReplicationEventRepository      eventRepo;

    @PersistenceContext
    private EntityManager em;

    /** Lightweight projection returned for auditing apply/dismiss. */
    public record FindingSummary(UUID findingId, ReconciliationFindingType type,
                                 String targetDn, UUID eventId) {}

    /**
     * Persist every surviving finding for a run. Missing/drift are auto-applied
     * when the mode is AUTO_CORRECT; extras when the delete-action is AUTO —
     * those get a corrective event and {@code AUTO_APPLIED}; the rest are
     * {@code PROPOSED} for review.
     * @return number of findings auto-applied (corrective events enqueued)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int persistFindings(UUID runId, UUID linkId, List<FindingCandidate> candidates,
                               ReconcileMode mode, ReconcileDeleteAction deleteAction) {
        ReconciliationRun run  = em.getReference(ReconciliationRun.class, runId);
        ReplicationLink    link = em.getReference(ReplicationLink.class, linkId);
        OffsetDateTime now = OffsetDateTime.now();
        int applied = 0;
        for (FindingCandidate c : candidates) {
            ReconciliationFinding f = new ReconciliationFinding();
            f.setRun(run);
            f.setLink(link);
            f.setFindingType(c.type());
            f.setSuggestedOp(c.operation());
            f.setSourceDn(c.sourceDn());
            f.setTargetDn(c.targetDn());
            f.setDetail(c.payload());
            boolean auto = switch (c.type()) {
                case MISSING_IN_TARGET, ATTRIBUTE_DRIFT -> mode == ReconcileMode.AUTO_CORRECT;
                case EXTRA_IN_TARGET                     -> deleteAction == ReconcileDeleteAction.AUTO;
            };
            if (auto) {
                UUID eventId = enqueueCorrection(link, c.operation(), c.sourceDn(), c.targetDn(), c.payload());
                f.setStatus(ReconciliationFindingStatus.AUTO_APPLIED);
                f.setEventId(eventId);
                f.setResolvedAt(now);
                applied++;
            } else {
                f.setStatus(ReconciliationFindingStatus.PROPOSED);
            }
            findingRepo.save(f);
        }
        return applied;
    }

    /** Apply selected (or all) PROPOSED findings: enqueue a corrective event each. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<FindingSummary> apply(UUID runId, List<UUID> findingIds, boolean applyAll,
                                      ReconciliationFindingType typeFilter, UUID actorId) {
        List<ReconciliationFinding> targets = applyAll
                ? findingRepo.findByRunIdAndStatus(runId, ReconciliationFindingStatus.PROPOSED)
                : findingRepo.findByRunIdAndIdIn(runId, safe(findingIds));
        OffsetDateTime now = OffsetDateTime.now();
        List<FindingSummary> applied = new ArrayList<>();
        for (ReconciliationFinding f : targets) {
            if (f.getStatus() != ReconciliationFindingStatus.PROPOSED) continue;   // idempotent
            if (applyAll && typeFilter != null && f.getFindingType() != typeFilter) continue;
            UUID eventId = enqueueCorrection(f.getLink(), f.getSuggestedOp(),
                    f.getSourceDn(), f.getTargetDn(), f.getDetail());
            f.setStatus(ReconciliationFindingStatus.APPLIED);
            f.setEventId(eventId);
            f.setResolvedBy(actorId);
            f.setResolvedAt(now);
            applied.add(new FindingSummary(f.getId(), f.getFindingType(), f.getTargetDn(), eventId));
        }
        return applied;
    }

    /** Dismiss selected PROPOSED findings without acting. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<FindingSummary> dismiss(UUID runId, List<UUID> findingIds, UUID actorId) {
        List<ReconciliationFinding> targets = findingRepo.findByRunIdAndIdIn(runId, safe(findingIds));
        OffsetDateTime now = OffsetDateTime.now();
        List<FindingSummary> dismissed = new ArrayList<>();
        for (ReconciliationFinding f : targets) {
            if (f.getStatus() != ReconciliationFindingStatus.PROPOSED) continue;
            f.setStatus(ReconciliationFindingStatus.DISMISSED);
            f.setResolvedBy(actorId);
            f.setResolvedAt(now);
            dismissed.add(new FindingSummary(f.getId(), f.getFindingType(), f.getTargetDn(), null));
        }
        return dismissed;
    }

    private UUID enqueueCorrection(ReplicationLink link, ReplicationOperationType op,
                                   String sourceDn, String targetDn, Map<String, Object> detail) {
        ReplicationEvent e = new ReplicationEvent();
        e.setLink(link);
        e.setEnqueueSource(ReplicationEnqueueSource.RECONCILIATION);
        e.setOperation(op);
        // source_dn is NOT NULL; deletes have no source entry so stamp the target DN.
        e.setSourceDn(sourceDn != null ? sourceDn : targetDn);
        e.setTargetDn(targetDn);
        e.setStatus(ReplicationEventStatus.PENDING);
        e.setPayload(correctivePayload(op, detail));
        eventRepo.save(e);
        return e.getId();
    }

    /** Strip UI-only keys ({@code before}/{@code currentTarget}) for the wire payload. */
    static Map<String, Object> correctivePayload(ReplicationOperationType op, Map<String, Object> detail) {
        return switch (op) {
            case ADD    -> Map.of("attributes", detail.getOrDefault("attributes", Map.of()));
            case MODIFY -> Map.of("modifications", detail.getOrDefault("modifications", List.of()));
            default     -> Map.of();   // DELETE / MODIFY_DN — DN alone identifies the op
        };
    }

    private static List<UUID> safe(List<UUID> ids) {
        return ids == null ? List.of() : ids;
    }
}
