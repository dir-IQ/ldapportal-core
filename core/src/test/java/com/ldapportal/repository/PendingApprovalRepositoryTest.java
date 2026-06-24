// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.repository;

import com.ldapportal.entity.PendingApproval;
import com.ldapportal.entity.enums.ApprovalRequestType;
import com.ldapportal.entity.enums.ApprovalStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The GROUP BY count queries that collapse the dashboard's per-profile and
 * per-directory pending-approval counts into a single round trip each (was one
 * count query per profile / per directory). Runs against H2 in PostgreSQL mode
 * (see application-test.yml).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class PendingApprovalRepositoryTest {

    @Autowired private PendingApprovalRepository repository;

    private PendingApproval approval(UUID directoryId, UUID profileId, ApprovalStatus status) {
        PendingApproval pa = new PendingApproval();
        pa.setDirectoryId(directoryId);
        pa.setProfileId(profileId);
        pa.setRequestedBy(UUID.randomUUID());
        pa.setStatus(status);
        pa.setRequestType(ApprovalRequestType.USER_CREATE);
        pa.setPayload("{}");
        return pa;
    }

    private static Map<UUID, Long> toMap(List<Object[]> rows) {
        Map<UUID, Long> m = new HashMap<>();
        for (Object[] row : rows) {
            m.put((UUID) row[0], (Long) row[1]);
        }
        return m;
    }

    @Test
    void countPendingByProfile_counts_only_pending_for_requested_profiles() {
        UUID dir = UUID.randomUUID();
        UUID profileA = UUID.randomUUID();
        UUID profileB = UUID.randomUUID();
        UUID profileC = UUID.randomUUID();

        repository.save(approval(dir, profileA, ApprovalStatus.PENDING));
        repository.save(approval(dir, profileA, ApprovalStatus.PENDING));
        repository.save(approval(dir, profileA, ApprovalStatus.APPROVED)); // wrong status
        repository.save(approval(dir, profileB, ApprovalStatus.PENDING));
        repository.save(approval(dir, profileC, ApprovalStatus.PENDING));   // not requested
        repository.flush();

        Map<UUID, Long> counts = toMap(repository.countPendingByProfile(
                ApprovalStatus.PENDING, List.of(profileA, profileB)));

        assertThat(counts).containsOnlyKeys(profileA, profileB);
        assertThat(counts.get(profileA)).isEqualTo(2L);
        assertThat(counts.get(profileB)).isEqualTo(1L);
    }

    @Test
    void countPendingByProfile_omits_profiles_with_no_pending() {
        UUID dir = UUID.randomUUID();
        UUID withPending = UUID.randomUUID();
        UUID withoutPending = UUID.randomUUID();
        repository.save(approval(dir, withPending, ApprovalStatus.PENDING));
        repository.save(approval(dir, withoutPending, ApprovalStatus.REJECTED));
        repository.flush();

        Map<UUID, Long> counts = toMap(repository.countPendingByProfile(
                ApprovalStatus.PENDING, List.of(withPending, withoutPending)));

        assertThat(counts).containsOnlyKeys(withPending);
        assertThat(counts.get(withPending)).isEqualTo(1L);
    }

    @Test
    void countPendingByDirectory_counts_only_pending_for_requested_directories() {
        UUID dirA = UUID.randomUUID();
        UUID dirB = UUID.randomUUID();
        UUID dirC = UUID.randomUUID();
        UUID profile = UUID.randomUUID();

        repository.save(approval(dirA, profile, ApprovalStatus.PENDING));
        repository.save(approval(dirA, profile, ApprovalStatus.PENDING));
        repository.save(approval(dirA, profile, ApprovalStatus.APPROVED)); // wrong status
        repository.save(approval(dirB, profile, ApprovalStatus.PENDING));
        repository.save(approval(dirC, profile, ApprovalStatus.PENDING));   // not requested
        repository.flush();

        Map<UUID, Long> counts = toMap(repository.countPendingByDirectory(
                ApprovalStatus.PENDING, List.of(dirA, dirB)));

        assertThat(counts).containsOnlyKeys(dirA, dirB);
        assertThat(counts.get(dirA)).isEqualTo(2L);
        assertThat(counts.get(dirB)).isEqualTo(1L);
    }

    @Test
    void countPendingByDirectory_counts_rows_with_a_null_profile() {
        // profileId is nullable (directory-level approvals); the directory
        // grouping must still count them.
        UUID dir = UUID.randomUUID();
        repository.save(approval(dir, null, ApprovalStatus.PENDING));
        repository.save(approval(dir, null, ApprovalStatus.PENDING));
        repository.flush();

        Map<UUID, Long> counts = toMap(repository.countPendingByDirectory(
                ApprovalStatus.PENDING, List.of(dir)));

        assertThat(counts.get(dir)).isEqualTo(2L);
    }
}
