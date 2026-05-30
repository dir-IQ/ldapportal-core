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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * R2 community-edition degradation: when the {@code DIRECTORY_SYNC}
 * entitlement is absent, the enqueuer drops captured writes before any
 * link lookup or persist — so no event rows accumulate regardless of a
 * directory's {@code replication_enabled} DB value. An entitlement
 * downgrade/upgrade round-trips cleanly because the column is untouched.
 */
class ReplicationEnqueuerEntitlementTest {

    private final ReplicationReadOps        readOps        = mock(ReplicationReadOps.class);
    private final ReplicationEventPersister persister      = mock(ReplicationEventPersister.class);
    private final EntitlementService        entitlement    = mock(EntitlementService.class);

    private CapturedWrite sampleWrite() {
        return CapturedWrite.add("cn=alice,dc=example,dc=com",
                List.of(new Attribute("cn", "alice")));
    }

    @Test
    void enqueue_dropsWrite_whenDirectorySyncNotEntitled() {
        when(entitlement.has(Entitlement.DIRECTORY_SYNC)).thenReturn(false);
        ReplicationEnqueuer enqueuer =
                new ReplicationEnqueuer(readOps, persister, entitlement);

        enqueuer.enqueue(UUID.randomUUID(), sampleWrite());

        // Gate fires before any link lookup or persist.
        verifyNoInteractions(readOps);
        verify(persister, never()).saveAll(any());
    }

    @Test
    void enqueue_proceedsToLinkLookup_whenEntitled() {
        when(entitlement.has(Entitlement.DIRECTORY_SYNC)).thenReturn(true);
        when(readOps.snapshotsForSource(any())).thenReturn(List.of());  // no links → cheap no-op
        ReplicationEnqueuer enqueuer =
                new ReplicationEnqueuer(readOps, persister, entitlement);

        enqueuer.enqueue(UUID.randomUUID(), sampleWrite());

        verify(readOps).snapshotsForSource(any());
        verify(persister, never()).saveAll(any());  // empty links → nothing persisted
    }
}
