// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication;

import com.ldapportal.entity.ReplicationEvent;
import com.ldapportal.entity.ReplicationLink;
import com.ldapportal.entity.enums.ReplicationEventStatus;
import com.ldapportal.entity.enums.ReplicationOperationType;
import com.ldapportal.repository.ReplicationEventRepository;
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
    @Mock private ReplicationEventRepository eventRepo;
    @InjectMocks private ReplicationEnqueuer enqueuer;

    @Test
    void noLinks_enqueueIsNoOp() {
        // The common path: the source directory has no replication
        // configured. Hit on every LDAP write so it must short-circuit
        // before doing any work beyond the link query.
        UUID source = UUID.randomUUID();
        when(linkRepo.findAllBySourceDirectoryIdAndEnabledTrue(source)).thenReturn(List.of());

        enqueuer.enqueue(source, CapturedWrite.delete("uid=alice,dc=corp"));

        verifyNoInteractions(eventRepo);
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

        ArgumentCaptor<ReplicationEvent> cap = ArgumentCaptor.forClass(ReplicationEvent.class);
        verify(eventRepo).save(cap.capture());
        ReplicationEvent ev = cap.getValue();
        assertThat(ev.getOperation()).isEqualTo(ReplicationOperationType.ADD);
        assertThat(ev.getStatus()).isEqualTo(ReplicationEventStatus.PENDING);
        assertThat(ev.getSourceDn()).isEqualTo("uid=alice,ou=people,dc=src,dc=com");
        assertThat(ev.getTargetDn()).contains("dc=tgt,dc=com").contains("uid=alice");
        assertThat(ev.getPayload()).containsKey("attributes");
    }

    @Test
    void multipleLinks_persistsOneEventPerLink() {
        // Fan-out: one source write → one event per matching enabled link.
        UUID source = UUID.randomUUID();
        ReplicationLink linkA = link(null, null);  // identity
        ReplicationLink linkB = link("dc=src,dc=com", "dc=mirror,dc=com");
        when(linkRepo.findAllBySourceDirectoryIdAndEnabledTrue(source))
                .thenReturn(List.of(linkA, linkB));

        enqueuer.enqueue(source, CapturedWrite.delete("uid=alice,dc=src,dc=com"));

        verify(eventRepo, times(2)).save(any(ReplicationEvent.class));
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

        verify(eventRepo, times(1)).save(any(ReplicationEvent.class));
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

        ArgumentCaptor<ReplicationEvent> cap = ArgumentCaptor.forClass(ReplicationEvent.class);
        verify(eventRepo).save(cap.capture());
        ReplicationEvent ev = cap.getValue();
        assertThat(ev.getOperation()).isEqualTo(ReplicationOperationType.MODIFY);
        assertThat(ev.getPayload()).containsKey("modifications");
    }

    @Test
    void enqueueFailure_doesNotPropagate() {
        // Source LDAP write has already committed; an enqueue failure
        // here must not propagate to the caller (would tie source
        // durability to replication-queue durability). Verified by
        // arranging save() to throw and asserting enqueue returns
        // normally.
        UUID source = UUID.randomUUID();
        ReplicationLink link = link(null, null);
        when(linkRepo.findAllBySourceDirectoryIdAndEnabledTrue(source))
                .thenReturn(List.of(link));
        when(eventRepo.save(any())).thenThrow(new RuntimeException("simulated DB failure"));

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
