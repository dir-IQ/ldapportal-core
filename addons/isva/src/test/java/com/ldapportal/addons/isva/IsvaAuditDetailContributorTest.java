// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva;

import com.ldapportal.addons.isva.entity.IsvaDeletePolicy;
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
        VendorIntegrationIsvaConfig cfg = config(IsvaTopologyMode.INLINE, IsvaDeletePolicy.DISABLE);
        cfg.setEnabled(false);
        when(configRepo.findById(directoryId)).thenReturn(Optional.of(cfg));

        assertThat(contributor.contribute(directoryId, AuditAction.USER_CREATE, "uid=alice", null))
                .isEmpty();
    }

    @Test
    void contribute_tagsVendorIntegration_onEnabledDirectory() {
        when(configRepo.findById(directoryId)).thenReturn(
                Optional.of(config(IsvaTopologyMode.INLINE, IsvaDeletePolicy.HARD_DELETE)));

        Map<String, Object> extra = contributor.contribute(directoryId,
                AuditAction.USER_CREATE, "uid=alice", null);

        assertThat(extra).containsEntry("vendorIntegration", "ISVA");
        assertThat(extra).doesNotContainKey("softDisable");
    }

    @Test
    void contribute_marksSoftDisable_onDeleteWithDisablePolicy() {
        when(configRepo.findById(directoryId)).thenReturn(
                Optional.of(config(IsvaTopologyMode.LINKED, IsvaDeletePolicy.DISABLE)));

        Map<String, Object> extra = contributor.contribute(directoryId,
                AuditAction.USER_DELETE, "uid=alice", null);

        assertThat(extra)
                .containsEntry("vendorIntegration", "ISVA")
                .containsEntry("softDisable", true);
    }

    @Test
    void contribute_omitsSoftDisable_onDeleteWithHardDeletePolicy() {
        when(configRepo.findById(directoryId)).thenReturn(
                Optional.of(config(IsvaTopologyMode.INLINE, IsvaDeletePolicy.HARD_DELETE)));

        Map<String, Object> extra = contributor.contribute(directoryId,
                AuditAction.USER_DELETE, "uid=alice", null);

        assertThat(extra)
                .containsEntry("vendorIntegration", "ISVA")
                .doesNotContainKey("softDisable");
    }

    @Test
    void contribute_omitsSoftDisable_onNonDeleteActions() {
        when(configRepo.findById(directoryId)).thenReturn(
                Optional.of(config(IsvaTopologyMode.INLINE, IsvaDeletePolicy.DISABLE)));

        // Even with DISABLE policy, USER_UPDATE is not a delete — no softDisable
        Map<String, Object> extra = contributor.contribute(directoryId,
                AuditAction.USER_UPDATE, "uid=alice", null);

        assertThat(extra)
                .containsEntry("vendorIntegration", "ISVA")
                .doesNotContainKey("softDisable");
    }

    private VendorIntegrationIsvaConfig config(IsvaTopologyMode mode, IsvaDeletePolicy policy) {
        VendorIntegrationIsvaConfig cfg = new VendorIntegrationIsvaConfig();
        cfg.setDirectoryConnectionId(directoryId);
        cfg.setEnabled(true);
        cfg.setTopologyMode(mode);
        cfg.setDeletePolicy(policy);
        return cfg;
    }
}
