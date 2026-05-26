// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.addons.isva.service;

import com.ldapportal.addons.isva.entity.IsvaProfileOverride;
import com.ldapportal.addons.isva.entity.IsvaProfileOverrideEntity;
import com.ldapportal.addons.isva.repository.IsvaProfileOverrideRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IsvaProfileOverrideServiceTest {

    @Mock private IsvaProfileOverrideRepository repository;

    private IsvaProfileOverrideService service;

    private final UUID profileId = UUID.randomUUID();

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new IsvaProfileOverrideService(repository);
    }

    @Test
    void forceOffRow_isExempt() {
        when(repository.findById(profileId)).thenReturn(Optional.of(row(IsvaProfileOverride.FORCE_OFF)));
        assertThat(service.isExemptFromIvia(profileId)).isTrue();
    }

    @Test
    void inheritRow_isNotExempt() {
        when(repository.findById(profileId)).thenReturn(Optional.of(row(IsvaProfileOverride.INHERIT)));
        assertThat(service.isExemptFromIvia(profileId)).isFalse();
    }

    @Test
    void noRow_isNotExempt_andResolvesInherit() {
        when(repository.findById(profileId)).thenReturn(Optional.empty());
        assertThat(service.isExemptFromIvia(profileId)).isFalse();
        assertThat(service.getOverride(profileId)).isEqualTo(IsvaProfileOverride.INHERIT);
    }

    @Test
    void nullProfileId_isNotExempt_andNeverHitsRepository() {
        assertThat(service.isExemptFromIvia(null)).isFalse();
        assertThat(service.getOverride(null)).isEqualTo(IsvaProfileOverride.INHERIT);
        verifyNoInteractions(repository);
    }

    @Test
    void setOverride_upsertsAndStampsUpdatedBy() {
        when(repository.findById(profileId)).thenReturn(Optional.empty());
        when(repository.save(any(IsvaProfileOverrideEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        IsvaProfileOverride result = service.setOverride(profileId, IsvaProfileOverride.FORCE_OFF, "alice");

        assertThat(result).isEqualTo(IsvaProfileOverride.FORCE_OFF);
        ArgumentCaptor<IsvaProfileOverrideEntity> captor =
                ArgumentCaptor.forClass(IsvaProfileOverrideEntity.class);
        org.mockito.Mockito.verify(repository).save(captor.capture());
        assertThat(captor.getValue().getProfileId()).isEqualTo(profileId);
        assertThat(captor.getValue().getOverride()).isEqualTo(IsvaProfileOverride.FORCE_OFF);
        assertThat(captor.getValue().getUpdatedBy()).isEqualTo("alice");
    }

    @Test
    void setOverride_nullOverride_defaultsToInherit() {
        lenient().when(repository.findById(profileId)).thenReturn(Optional.empty());
        when(repository.save(any(IsvaProfileOverrideEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        IsvaProfileOverride result = service.setOverride(profileId, null, "system");

        assertThat(result).isEqualTo(IsvaProfileOverride.INHERIT);
    }

    private IsvaProfileOverrideEntity row(IsvaProfileOverride override) {
        IsvaProfileOverrideEntity e = new IsvaProfileOverrideEntity();
        e.setProfileId(profileId);
        e.setOverride(override);
        return e;
    }
}
