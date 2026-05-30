// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.core.observability.CorrelationContext;
import com.unboundid.ldap.sdk.Attribute;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R2: the source-side correlation id must reach the persisted replication
 * event payload (key {@code correlationId}) so dispatch-side audit rows can
 * pivot back to the originating operation. Covers both the wrapper-stamped
 * path ({@link CapturedWrite#withCorrelation}) and the ambient-scope
 * fallback ({@link CorrelationContext}). The full DB pivot (source-side
 * USER_UPDATE + dispatch-side REPLICATION_EVENT_* sharing the id) is an
 * integration concern exercised under Testcontainers.
 */
class ReplicationCorrelationFlowTest {

    private final ReplicationReadOps        readOps   = mock(ReplicationReadOps.class);
    private final ReplicationEventPersister persister = mock(ReplicationEventPersister.class);
    // Null entitlement service → gate treated as open (see ReplicationEnqueuer).
    private final ReplicationEnqueuer enqueuer =
            new ReplicationEnqueuer(readOps, persister, null);

    @AfterEach
    void cleanup() {
        CorrelationContext.clear();
    }

    private static ReplicationLinkSnapshot link() {
        return new ReplicationLinkSnapshot(
                UUID.randomUUID(), "L",
                UUID.randomUUID(), UUID.randomUUID(),
                "dc=src,dc=com", "dc=tgt,dc=com",
                false, List.of());
    }

    @SuppressWarnings("unchecked")
    private String capturedCorrelationId() {
        ArgumentCaptor<List<PendingReplicationEvent>> cap =
                ArgumentCaptor.forClass(List.class);
        verify(persister).saveAll(cap.capture());
        assertThat(cap.getValue()).hasSize(1);
        Object id = cap.getValue().get(0).payload().get("correlationId");
        return id == null ? null : id.toString();
    }

    @Test
    void wrapperStampedCorrelation_reachesPayload() {
        UUID source = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        when(readOps.snapshotsForSource(source)).thenReturn(List.of(link()));

        enqueuer.enqueue(source, CapturedWrite
                .add("uid=alice,dc=src,dc=com", List.of(new Attribute("uid", "alice")))
                .withCorrelation(correlation));

        assertThat(capturedCorrelationId()).isEqualTo(correlation.toString());
    }

    @Test
    void ambientScopeCorrelation_reachesPayload_whenWriteUnstamped() {
        UUID source = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        when(readOps.snapshotsForSource(source)).thenReturn(List.of(link()));

        CorrelationContext.withCorrelation(correlation, () ->
                enqueuer.enqueue(source, CapturedWrite.add(
                        "uid=bob,dc=src,dc=com", List.of(new Attribute("uid", "bob")))));

        assertThat(capturedCorrelationId()).isEqualTo(correlation.toString());
    }
}
