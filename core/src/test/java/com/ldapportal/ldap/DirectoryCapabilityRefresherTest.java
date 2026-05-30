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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DirectoryCapabilityRefresherTest {

    @Mock private DirectoryConnectionRepository dirRepo;
    @Mock private LdapCapabilityProbeService    probeService;
    @InjectMocks private DirectoryCapabilityRefresher refresher;

    @Test
    void onDirectorySaved_persistsSnapshot_whenProbeReturnsSnapshot() {
        UUID id = UUID.randomUUID();
        DirectoryConnection dc = new DirectoryConnection();
        dc.setId(id);
        when(dirRepo.findById(id)).thenReturn(Optional.of(dc));
        DirectoryCapabilities caps = new DirectoryCapabilities(
                "Vendor", "1.0", List.of(), List.of(), List.of(), List.of(),
                OffsetDateTime.now());
        when(probeService.probe(dc)).thenReturn(caps);

        refresher.onDirectorySaved(new DirectoryConnectionSavedEvent(id));

        assertThat(dc.getCapabilities()).isSameAs(caps);
        verify(dirRepo).save(dc);
    }

    @Test
    void onDirectorySaved_doesNotPersist_whenProbeReturnsNull() {
        // Pinning the contract that complements the probe-service change:
        // a null snapshot (Entra short-circuit, server hid root DSE,
        // bind failure) MUST NOT save the row. DirectoryConnectionService
        // explicitly clears capabilities on type change before publishing
        // the event; if the refresher saved a null-snapshot here, that
        // clearing would be undone with a no-op write.
        UUID id = UUID.randomUUID();
        DirectoryConnection dc = new DirectoryConnection();
        dc.setId(id);
        when(dirRepo.findById(id)).thenReturn(Optional.of(dc));
        when(probeService.probe(dc)).thenReturn(null);

        refresher.onDirectorySaved(new DirectoryConnectionSavedEvent(id));

        verify(dirRepo, never()).save(any());
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

        verify(probeService, never()).probe(any());
        verify(dirRepo, never()).save(any());
    }
}
