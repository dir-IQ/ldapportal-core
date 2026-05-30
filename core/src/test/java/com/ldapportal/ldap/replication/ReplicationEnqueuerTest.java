// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.entity.ReplicationEvent;
import com.ldapportal.entity.ReplicationLink;
import com.ldapportal.entity.enums.ReplicationEventStatus;
import com.ldapportal.entity.enums.ReplicationOperationType;
import com.ldapportal.ldap.replication.ReplicationEventPersister;
import com.ldapportal.repository.ReplicationLinkRepository;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReplicationEnqueuerTest {

    @Mock private ReplicationLinkRepository  linkRepo;
    @Mock private ReplicationEventPersister persister;
    @InjectMocks private ReplicationEnqueuer enqueuer;

    @Test
    void noLinks_enqueueIsNoOp() {
        // The common path: the source directory has no replication
        // configured. Hit on every LDAP write so it must short-circuit
        // before doing any work beyond the link query.
        UUID source = UUID.randomUUID();
        when(linkRepo.findAllBySourceDirectoryIdAndEnabledTrue(source)).thenReturn(List.of());

        enqueuer.enqueue(source, CapturedWrite.delete("uid=alice,dc=corp"));

        verifyNoInteractions(persister);
    }

    @Test
    void singleLink_addOp_persistsEventWithMappedDnAndAttributes() {
        UUID source = UUID.randomUUID();
        ReplicationLink link = link("dc=src,dc=com", "dc=tgt,dc=com");
        when(linkRepo.findAllBySourceDirectoryIdAndEnabledTrue(source))
                .thenReturn(List.of(link));

        enqueuer.enqueue(source, CapturedWrite.add(
                "uid=alice,ou=people,dc=src,dc=com",
                List.of(new Attribute("uid", "alice"),
                        new Attribute("mail", "alice@src.com"))));

        ArgumentCaptor<List<ReplicationEvent>> cap = ArgumentCaptor.forClass(List.class);
        verify(persister).saveAll(cap.capture());
        assertThat(cap.getValue()).hasSize(1);
        ReplicationEvent ev = cap.getValue().get(0);
        assertThat(ev.getOperation()).isEqualTo(ReplicationOperationType.ADD);
        assertThat(ev.getStatus()).isEqualTo(ReplicationEventStatus.PENDING);
        assertThat(ev.getSourceDn()).isEqualTo("uid=alice,ou=people,dc=src,dc=com");
        assertThat(ev.getTargetDn()).contains("dc=tgt,dc=com").contains("uid=alice");
        assertThat(ev.getPayload()).containsKey("attributes");
    }

    @Test
    void multipleLinks_persistsOneEventPerLink() {
        // Fan-out: one source write → one event per matching enabled link.
        // Single saveAll call with both events in the batch (the
        // persister opens one tx for the batch, not one tx per event).
        UUID source = UUID.randomUUID();
        ReplicationLink linkA = link(null, null);  // identity
        ReplicationLink linkB = link("dc=src,dc=com", "dc=mirror,dc=com");
        when(linkRepo.findAllBySourceDirectoryIdAndEnabledTrue(source))
                .thenReturn(List.of(linkA, linkB));

        enqueuer.enqueue(source, CapturedWrite.delete("uid=alice,dc=src,dc=com"));

        ArgumentCaptor<List<ReplicationEvent>> cap = ArgumentCaptor.forClass(List.class);
        verify(persister).saveAll(cap.capture());
        assertThat(cap.getValue()).hasSize(2);
    }

    @Test
    void outOfScopeDn_skipsThatLink() {
        // The DN is below one link's source base but not the other's.
        // The in-scope link gets an event; the out-of-scope link is
        // silently skipped (no error, no save).
        UUID source = UUID.randomUUID();
        ReplicationLink inScope  = link("ou=people,dc=src,dc=com", "ou=people,dc=tgt,dc=com");
        ReplicationLink outScope = link("ou=other,dc=src,dc=com",  "ou=other,dc=tgt,dc=com");
        when(linkRepo.findAllBySourceDirectoryIdAndEnabledTrue(source))
                .thenReturn(List.of(inScope, outScope));

        enqueuer.enqueue(source, CapturedWrite.delete("uid=alice,ou=people,dc=src,dc=com"));

        ArgumentCaptor<List<ReplicationEvent>> cap = ArgumentCaptor.forClass(List.class);
        verify(persister).saveAll(cap.capture());
        assertThat(cap.getValue()).hasSize(1);
    }

    @Test
    void allLinksOutOfScope_doesNotOpenTransactionAtAll() {
        // Every link's DnMapper returns null (no scope match). The
        // hot-path optimisation: skip the persister call entirely so
        // we don't open the REQUIRES_NEW tx for zero events. This
        // matches the no-links path's cost profile.
        UUID source = UUID.randomUUID();
        ReplicationLink outScope = link("ou=other,dc=src,dc=com", "ou=other,dc=tgt,dc=com");
        when(linkRepo.findAllBySourceDirectoryIdAndEnabledTrue(source))
                .thenReturn(List.of(outScope));

        enqueuer.enqueue(source, CapturedWrite.delete("uid=alice,ou=people,dc=src,dc=com"));

        verifyNoInteractions(persister);
    }

    @Test
    void modifyOp_persistsMappedModifications() {
        UUID source = UUID.randomUUID();
        ReplicationLink link = link(null, null);
        when(linkRepo.findAllBySourceDirectoryIdAndEnabledTrue(source))
                .thenReturn(List.of(link));

        enqueuer.enqueue(source, CapturedWrite.modify(
                "uid=alice,dc=corp,dc=com",
                List.of(new Modification(ModificationType.REPLACE, "mail", "new@corp.com"))));

        ArgumentCaptor<List<ReplicationEvent>> cap = ArgumentCaptor.forClass(List.class);
        verify(persister).saveAll(cap.capture());
        ReplicationEvent ev = cap.getValue().get(0);
        assertThat(ev.getOperation()).isEqualTo(ReplicationOperationType.MODIFY);
        assertThat(ev.getPayload()).containsKey("modifications");
    }

    @Test
    void modifyOp_withNullValues_doesNotNPE() {
        // Regression: UnboundID returns null (not an empty array) for the
        // 'delete the entire attribute' form. Earlier enqueuer code did
        // `values.length` -> NPE -> outer catch swallowed -> source write
        // succeeded but no event was enqueued. Pin the null-safe path.
        UUID source = UUID.randomUUID();
        ReplicationLink link = link(null, null);
        when(linkRepo.findAllBySourceDirectoryIdAndEnabledTrue(source))
                .thenReturn(List.of(link));

        // Modification(DELETE, attrName) with no values — the wipe form.
        Modification wipe = new Modification(ModificationType.DELETE, "description");

        enqueuer.enqueue(source, CapturedWrite.modify(
                "uid=alice,dc=corp,dc=com", List.of(wipe)));

        // Must not have NPE'd; persister got the event with an empty
        // values list in the modifications payload.
        ArgumentCaptor<List<ReplicationEvent>> cap = ArgumentCaptor.forClass(List.class);
        verify(persister).saveAll(cap.capture());
        assertThat(cap.getValue()).hasSize(1);
    }

    @Test
    void enqueueFailure_doesNotPropagate() {
        // Source LDAP write has already committed; an enqueue failure
        // here must not propagate to the caller (would tie source
        // durability to replication-queue durability). Verified by
        // arranging persister.saveAll to throw and asserting enqueue
        // returns normally.
        UUID source = UUID.randomUUID();
        ReplicationLink link = link(null, null);
        when(linkRepo.findAllBySourceDirectoryIdAndEnabledTrue(source))
                .thenReturn(List.of(link));
        org.mockito.Mockito.doThrow(new RuntimeException("simulated DB failure"))
                .when(persister).saveAll(any());

        // Must not throw.
        enqueuer.enqueue(source, CapturedWrite.delete("uid=alice,dc=corp"));
    }

    private static ReplicationLink link(String sourceBaseDn, String targetBaseDn) {
        ReplicationLink l = new ReplicationLink();
        l.setSourceBaseDn(sourceBaseDn);
        l.setTargetBaseDn(targetBaseDn);
        return l;
    }
}
