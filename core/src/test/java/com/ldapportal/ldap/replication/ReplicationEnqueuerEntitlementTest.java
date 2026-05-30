// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.core.entitlement.Entitlement;
import com.ldapportal.core.entitlement.EntitlementService;
import com.unboundid.ldap.sdk.Attribute;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R2 community-edition degradation: when the {@code DIRECTORY_SYNC}
 * entitlement is absent, the enqueuer drops captured writes before persist —
 * so no event rows accumulate regardless of a directory's
 * {@code replication_enabled} DB value. An entitlement downgrade/upgrade
 * round-trips cleanly because the column is untouched.
 *
 * <p>The cheap {@code links.isEmpty()} lookup runs before the entitlement
 * check (so the per-write license read is skipped when there's nothing to
 * enqueue), so the gate is observed by the absence of a persist, not the
 * absence of a link lookup.
 */
class ReplicationEnqueuerEntitlementTest {

    private final ReplicationReadOps        readOps        = mock(ReplicationReadOps.class);
    private final ReplicationEventPersister persister      = mock(ReplicationEventPersister.class);
    private final EntitlementService        entitlement    = mock(EntitlementService.class);

    private CapturedWrite sampleWrite() {
        return CapturedWrite.add("cn=alice,dc=example,dc=com",
                List.of(new Attribute("cn", "alice")));
    }

    private static ReplicationLinkSnapshot identityLink() {
        // null/null base DNs → identity DN mapping, so the write maps to a
        // non-null target and would persist if the entitlement gate allowed it.
        return new ReplicationLinkSnapshot(
                UUID.randomUUID(), "L", null, null, null, null, true, false, List.of());
    }

    @Test
    void enqueue_dropsWrite_whenDirectorySyncNotEntitled() {
        // A matching link exists, so the links short-circuit does NOT fire;
        // the entitlement gate is what prevents the persist.
        when(readOps.snapshotsForSource(any())).thenReturn(List.of(identityLink()));
        when(entitlement.has(Entitlement.DIRECTORY_SYNC)).thenReturn(false);
        ReplicationEnqueuer enqueuer =
                new ReplicationEnqueuer(readOps, persister, entitlement);

        enqueuer.enqueue(UUID.randomUUID(), sampleWrite());

        verify(persister, never()).saveAll(any());
    }

    @Test
    void enqueue_persists_whenEntitled() {
        when(readOps.snapshotsForSource(any())).thenReturn(List.of(identityLink()));
        when(entitlement.has(Entitlement.DIRECTORY_SYNC)).thenReturn(true);
        ReplicationEnqueuer enqueuer =
                new ReplicationEnqueuer(readOps, persister, entitlement);

        enqueuer.enqueue(UUID.randomUUID(), sampleWrite());

        // Entitled + a matching link → the write is persisted.
        verify(persister).saveAll(any());
    }

    @Test
    void enqueue_skipsEntitlementRead_whenNoLinks() {
        // No links → cheap short-circuit before the (potentially IO-heavy)
        // entitlement read. has() must not be consulted at all.
        when(readOps.snapshotsForSource(any())).thenReturn(List.of());
        ReplicationEnqueuer enqueuer =
                new ReplicationEnqueuer(readOps, persister, entitlement);

        enqueuer.enqueue(UUID.randomUUID(), sampleWrite());

        verify(entitlement, never()).has(any());
        verify(persister, never()).saveAll(any());
    }
}
