// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.controller;

import com.ldapportal.addons.isva.dto.IsvaConfigStatusDto;
import com.ldapportal.addons.isva.entity.VendorIntegrationIsvaConfig;
import com.ldapportal.addons.isva.repository.VendorIntegrationIsvaConfigRepository;
import com.ldapportal.addons.isva.service.IsvaConfigProbeService;
import com.ldapportal.addons.isva.service.IsvaConfigService;
import com.ldapportal.repository.DirectoryConnectionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IsvaConfigStatusControllerTest {

    @Mock private VendorIntegrationIsvaConfigRepository configRepo;
    @Mock private DirectoryConnectionRepository directoryRepo;
    @Mock private IsvaConfigProbeService probeService;

    private IsvaConfigStatusController controller() {
        return new IsvaConfigStatusController(new IsvaConfigService(configRepo, directoryRepo, probeService));
    }

    private static VendorIntegrationIsvaConfig cfg(UUID id, boolean enabled) {
        VendorIntegrationIsvaConfig c = new VendorIntegrationIsvaConfig();
        c.setDirectoryConnectionId(id);
        c.setEnabled(enabled);
        return c;
    }

    @Test
    void listsEachConfiguredDirectoryWithItsEnabledFlag() {
        UUID on = UUID.randomUUID();
        UUID off = UUID.randomUUID();
        when(configRepo.findAll()).thenReturn(List.of(cfg(on, true), cfg(off, false)));

        var body = controller().list().getBody();

        assertThat(body).containsExactlyInAnyOrder(
                new IsvaConfigStatusDto(on, true),
                new IsvaConfigStatusDto(off, false));
    }

    @Test
    void emptyWhenNoDirectoryIsConfigured() {
        when(configRepo.findAll()).thenReturn(List.of());
        assertThat(controller().list().getBody()).isEmpty();
    }
}
