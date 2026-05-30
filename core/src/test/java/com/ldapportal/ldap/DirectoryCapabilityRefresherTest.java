// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap;

import com.ldapportal.core.directory.event.DirectoryConnectionSavedEvent;
import com.ldapportal.entity.DirectoryConnection;
import com.ldapportal.ldap.model.DirectoryCapabilities;
import com.ldapportal.repository.DirectoryConnectionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class DirectoryCapabilityRefresherTest {

    @Mock private DirectoryConnectionRepository dirRepo;
    @Mock private LdapCapabilityProbeService    probeService;
    @Mock private CapabilityWriter              writer;
    @InjectMocks private DirectoryCapabilityRefresher refresher;

    @Test
    void onDirectorySaved_persistsViaWriter_whenProbeReturnsSnapshot() {
        UUID id = UUID.randomUUID();
        DirectoryConnection dc = new DirectoryConnection();
        dc.setId(id);
        when(dirRepo.findById(id)).thenReturn(Optional.of(dc));
        DirectoryCapabilities caps = new DirectoryCapabilities(
                "Vendor", "1.0", List.of(), List.of(), List.of(), List.of(),
                OffsetDateTime.now());
        when(probeService.probe(dc)).thenReturn(caps);
        when(writer.persistCapabilities(id, caps)).thenReturn(1);

        refresher.onDirectorySaved(new DirectoryConnectionSavedEvent(id));

        verify(writer).persistCapabilities(id, caps);
        // Refresher must NOT call dirRepo.save — that would emit a
        // full-row UPDATE and risk clobbering a concurrent admin edit.
        // The targeted writer is the only persistence path now.
        verify(dirRepo, never()).save(any());
    }

    @Test
    void onDirectorySaved_doesNotPersist_whenProbeReturnsNull() {
        // Pinning the contract that complements the probe-service change:
        // a null snapshot (Entra short-circuit, server hid root DSE,
        // bind failure) must NOT touch the row. DirectoryConnectionService
        // already cleared capabilities at save time; the listener leaves
        // it cleared.
        UUID id = UUID.randomUUID();
        DirectoryConnection dc = new DirectoryConnection();
        dc.setId(id);
        when(dirRepo.findById(id)).thenReturn(Optional.of(dc));
        when(probeService.probe(dc)).thenReturn(null);

        refresher.onDirectorySaved(new DirectoryConnectionSavedEvent(id));

        verifyNoInteractions(writer);
    }

    @Test
    void onDirectorySaved_skipsSilently_whenDirectoryRemovedBeforeRefresh() {
        // Race window: directory created, saved-event published, but
        // before the AFTER_COMMIT listener runs the row gets deleted
        // (operator immediately deleted, transactional cleanup, etc.).
        // findById returns empty; refresher must not throw — that would
        // surface as an unhandled async exception in the executor log.
        UUID id = UUID.randomUUID();
        when(dirRepo.findById(id)).thenReturn(Optional.empty());

        refresher.onDirectorySaved(new DirectoryConnectionSavedEvent(id));

        verifyNoInteractions(probeService);
        verifyNoInteractions(writer);
    }

    @Test
    void onDirectorySaved_swallowsException_whenWriterThrows() {
        // The outer try/catch is load-bearing: without it, a transient
        // DB exception in the writer (DataIntegrityViolation, deadlock,
        // optimistic-lock if @Version is ever added) propagates out of
        // an @Async listener into SimpleAsyncUncaughtExceptionHandler,
        // which logs at warn and discards. The catch upgrades that to a
        // tagged error log with the directory id and short-circuits the
        // rest of the body.
        UUID id = UUID.randomUUID();
        DirectoryConnection dc = new DirectoryConnection();
        dc.setId(id);
        when(dirRepo.findById(id)).thenReturn(Optional.of(dc));
        DirectoryCapabilities caps = new DirectoryCapabilities(
                "Vendor", "1.0", List.of(), List.of(), List.of(), List.of(),
                OffsetDateTime.now());
        when(probeService.probe(dc)).thenReturn(caps);
        when(writer.persistCapabilities(eq(id), any()))
                .thenThrow(new RuntimeException("simulated DB failure"));

        // Must not throw out of the listener.
        refresher.onDirectorySaved(new DirectoryConnectionSavedEvent(id));
    }

    @Test
    void onDirectorySaved_logsAndContinues_whenWriterReturnsZeroRows() {
        // Row was deleted between the listener's findById and the
        // writer's UPDATE — zero rows affected. The listener must not
        // throw on this; it logs at debug and returns. Symmetric to the
        // findById-empty path above but at a different race window.
        UUID id = UUID.randomUUID();
        DirectoryConnection dc = new DirectoryConnection();
        dc.setId(id);
        when(dirRepo.findById(id)).thenReturn(Optional.of(dc));
        DirectoryCapabilities caps = new DirectoryCapabilities(
                "Vendor", "1.0", List.of(), List.of(), List.of(), List.of(),
                OffsetDateTime.now());
        when(probeService.probe(dc)).thenReturn(caps);
        when(writer.persistCapabilities(id, caps)).thenReturn(0);

        refresher.onDirectorySaved(new DirectoryConnectionSavedEvent(id));

        verify(writer).persistCapabilities(id, caps);
    }
}
