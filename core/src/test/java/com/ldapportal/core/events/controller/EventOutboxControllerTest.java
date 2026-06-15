// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.events.controller;

import com.ldapportal.auth.ApiTokenAuthenticationDetails;
import com.ldapportal.controller.BaseControllerTest;
import com.ldapportal.core.events.entity.OutboxEntry;
import com.ldapportal.core.events.enums.OutboxStatus;
import com.ldapportal.core.events.repository.EventSubscriptionRepository;
import com.ldapportal.core.events.repository.OutboxEntryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = EventOutboxController.class)
class EventOutboxControllerTest extends BaseControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private OutboxEntryRepository outboxRepository;
    @MockitoBean private EventSubscriptionRepository subscriptionRepository;
    @MockitoBean private Clock clock;

    private UsernamePasswordAuthenticationToken apiTokenAuth() {
        UsernamePasswordAuthenticationToken a = superadminAuth();
        a.setDetails(new ApiTokenAuthenticationDetails(UUID.randomUUID(), "ci"));
        return a;
    }

    private OutboxEntry entry(OutboxStatus status) {
        OutboxEntry e = new OutboxEntry();
        e.setId(UUID.randomUUID());
        e.setSubscriptionId(UUID.randomUUID());
        e.setEventId(UUID.randomUUID());
        e.setEventType("api_token.created");
        e.setOccurredAt(Instant.now());
        e.setEnvelope(Map.of("type", "api_token.created"));
        e.setStatus(status);
        e.setAttempts(0);
        e.setNextAttemptAt(Instant.now());
        e.setCreatedAt(Instant.now());
        return e;
    }

    @Test
    void list_paginated_returnsEntries() throws Exception {
        Page<OutboxEntry> page = new PageImpl<>(List.of(entry(OutboxStatus.PENDING)));
        when(outboxRepository.findAll(any(PageRequest.class))).thenReturn(page);

        mockMvc.perform(get("/api/v1/superadmin/event-outbox")
                        .with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void list_filterByStatus() throws Exception {
        Page<OutboxEntry> page = new PageImpl<>(List.of(entry(OutboxStatus.DEAD_LETTERED)));
        when(outboxRepository.findAllByStatus(eq(OutboxStatus.DEAD_LETTERED), any(PageRequest.class)))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/superadmin/event-outbox")
                        .param("status", "DEAD_LETTERED")
                        .with(authentication(superadminAuth())))
                .andExpect(status().isOk());
    }

    @Test
    void getById_returnsEntry() throws Exception {
        OutboxEntry e = entry(OutboxStatus.DELIVERED);
        when(outboxRepository.findById(e.getId())).thenReturn(Optional.of(e));
        when(subscriptionRepository.findById(e.getSubscriptionId())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/superadmin/event-outbox/" + e.getId())
                        .with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(e.getId().toString()));
    }

    @Test
    void getById_unknown_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(outboxRepository.findById(id)).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/v1/superadmin/event-outbox/" + id)
                        .with(authentication(superadminAuth())))
                .andExpect(status().isNotFound());
    }

    @Test
    void requeue_deadLettered_returns200() throws Exception {
        OutboxEntry e = entry(OutboxStatus.DEAD_LETTERED);
        e.setDeadLetteredAt(Instant.now());
        when(outboxRepository.findById(e.getId())).thenReturn(Optional.of(e));
        when(outboxRepository.save(any(OutboxEntry.class))).thenReturn(e);

        mockMvc.perform(post("/api/v1/superadmin/event-outbox/" + e.getId() + "/requeue")
                        .with(authentication(superadminAuth())))
                .andExpect(status().isOk());
    }

    @Test
    void requeue_pending_returns409() throws Exception {
        OutboxEntry e = entry(OutboxStatus.PENDING);
        when(outboxRepository.findById(e.getId())).thenReturn(Optional.of(e));

        mockMvc.perform(post("/api/v1/superadmin/event-outbox/" + e.getId() + "/requeue")
                        .with(authentication(superadminAuth())))
                .andExpect(status().isConflict());
    }

    @Test
    void requeue_delivered_returns409() throws Exception {
        OutboxEntry e = entry(OutboxStatus.DELIVERED);
        when(outboxRepository.findById(e.getId())).thenReturn(Optional.of(e));

        mockMvc.perform(post("/api/v1/superadmin/event-outbox/" + e.getId() + "/requeue")
                        .with(authentication(superadminAuth())))
                .andExpect(status().isConflict());
    }

    @Test
    void requeue_unknown_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(outboxRepository.findById(id)).thenReturn(Optional.empty());
        mockMvc.perform(post("/api/v1/superadmin/event-outbox/" + id + "/requeue")
                        .with(authentication(superadminAuth())))
                .andExpect(status().isNotFound());
    }

    @Test
    void requeue_asApiTokenCaller_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/superadmin/event-outbox/" + UUID.randomUUID() + "/requeue")
                        .with(authentication(apiTokenAuth())))
                .andExpect(status().isForbidden());
    }
}
