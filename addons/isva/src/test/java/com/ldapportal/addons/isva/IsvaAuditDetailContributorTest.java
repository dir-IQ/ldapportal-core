// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva;

import com.ldapportal.addons.isva.entity.IsvaTopologyMode;
import com.ldapportal.addons.isva.entity.VendorIntegrationIsvaConfig;
import com.ldapportal.addons.isva.repository.VendorIntegrationIsvaConfigRepository;
import com.ldapportal.entity.enums.AuditAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IsvaAuditDetailContributorTest {

    @Mock private VendorIntegrationIsvaConfigRepository configRepo;

    private IsvaAuditDetailContributor contributor;
    private final UUID directoryId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        contributor = new IsvaAuditDetailContributor(configRepo);
    }

    @Test
    void contribute_returnsEmpty_whenDirectoryIsNull() {
        assertThat(contributor.contribute(null, AuditAction.USER_CREATE, "uid=alice", null))
                .isEmpty();
    }

    @Test
    void contribute_returnsEmpty_whenNoConfigRow() {
        when(configRepo.findById(directoryId)).thenReturn(Optional.empty());

        assertThat(contributor.contribute(directoryId, AuditAction.USER_CREATE, "uid=alice", null))
                .isEmpty();
    }

    @Test
    void contribute_returnsEmpty_whenConfigDisabled() {
        VendorIntegrationIsvaConfig cfg = config(IsvaTopologyMode.INLINE);
        cfg.setEnabled(false);
        when(configRepo.findById(directoryId)).thenReturn(Optional.of(cfg));

        assertThat(contributor.contribute(directoryId, AuditAction.USER_CREATE, "uid=alice", null))
                .isEmpty();
    }

    @Test
    void contribute_tagsVendorIntegration_onEnabledDirectory() {
        when(configRepo.findById(directoryId)).thenReturn(
                Optional.of(config(IsvaTopologyMode.INLINE)));

        Map<String, Object> extra = contributor.contribute(directoryId,
                AuditAction.USER_CREATE, "uid=alice", null);

        assertThat(extra).containsEntry("vendorIntegration", "ISVA");
    }

    @Test
    void contribute_doesNotMarkSoftDisable_onDelete() {
        // Deletes are always hard now (both entries removed) — there's no
        // delete-vs-soft-disable distinction for the audit row to carry.
        when(configRepo.findById(directoryId)).thenReturn(
                Optional.of(config(IsvaTopologyMode.LINKED)));

        Map<String, Object> extra = contributor.contribute(directoryId,
                AuditAction.USER_DELETE, "uid=alice", null);

        assertThat(extra)
                .containsEntry("vendorIntegration", "ISVA")
                .doesNotContainKey("softDisable");
    }

    @Test
    void contribute_forwardsProfileId_whenPresentInBaseDetail() {
        when(configRepo.findById(directoryId)).thenReturn(
                Optional.of(config(IsvaTopologyMode.INLINE)));
        UUID profileId = UUID.randomUUID();

        Map<String, Object> extra = contributor.contribute(directoryId,
                AuditAction.USER_CREATE, "uid=alice", Map.of("profileId", profileId));

        assertThat(extra)
                .containsEntry("vendorIntegration", "ISVA")
                .containsEntry("profileId", profileId);
    }

    private VendorIntegrationIsvaConfig config(IsvaTopologyMode mode) {
        VendorIntegrationIsvaConfig cfg = new VendorIntegrationIsvaConfig();
        cfg.setDirectoryConnectionId(directoryId);
        cfg.setEnabled(true);
        cfg.setTopologyMode(mode);
        return cfg;
    }
}
