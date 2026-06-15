// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.events.service;

import com.ldapportal.core.events.dto.CreateEventSubscriptionRequest;
import com.ldapportal.core.events.dto.DestinationConfigRequest;
import com.ldapportal.core.events.enums.ChannelType;
import com.ldapportal.core.events.repository.EventSubscriptionRepository;
import com.ldapportal.repository.AccountRepository;
import com.ldapportal.service.EncryptionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class EventSubscriptionServiceTest {

    @Mock private EventSubscriptionRepository repository;
    @Mock private AccountRepository accountRepository;
    @Mock private EncryptionService encryptionService;

    private EventSubscriptionService service() {
        return new EventSubscriptionService(
                repository, accountRepository, encryptionService, List.of(),
                Clock.fixed(Instant.parse("2026-05-24T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void create_rejectsSsrfUrl_beforePersisting() {
        CreateEventSubscriptionRequest req = new CreateEventSubscriptionRequest(
                "metadata-probe", null, ChannelType.WEBHOOK,
                new DestinationConfigRequest("http://169.254.169.254/latest/meta-data/", null),
                null, true);

        assertThatThrownBy(() -> service().create(req, null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(encryptionService);
    }

    @Test
    void create_rejectsLoopbackUrl_beforePersisting() {
        CreateEventSubscriptionRequest req = new CreateEventSubscriptionRequest(
                "loopback", null, ChannelType.WEBHOOK,
                new DestinationConfigRequest("http://127.0.0.1:9000/hook", null),
                null, true);

        assertThatThrownBy(() -> service().create(req, null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
