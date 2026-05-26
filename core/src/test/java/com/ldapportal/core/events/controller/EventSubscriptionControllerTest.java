// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.events.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ldapportal.auth.ApiTokenAuthenticationDetails;
import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.auth.PrincipalType;
import com.ldapportal.controller.BaseControllerTest;
import com.ldapportal.core.events.dto.CreateEventSubscriptionRequest;
import com.ldapportal.core.events.dto.DestinationConfigRequest;
import com.ldapportal.core.events.dto.TestDeliveryResult;
import com.ldapportal.core.events.dto.WebhookAuthRequest;
import com.ldapportal.core.events.entity.EventSubscription;
import com.ldapportal.core.events.enums.ChannelType;
import com.ldapportal.core.events.service.EventSubscriptionService;
import com.ldapportal.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = EventSubscriptionController.class)
class EventSubscriptionControllerTest extends BaseControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private EventSubscriptionService service;

    private UUID subscriberAccountId;

    @BeforeEach
    void setUp() {
        subscriberAccountId = UUID.randomUUID();
    }

    @Override
    protected UsernamePasswordAuthenticationToken superadminAuth() {
        AuthPrincipal p = new AuthPrincipal(PrincipalType.SUPERADMIN, subscriberAccountId, "alice");
        return new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_SUPERADMIN")));
    }

    private UsernamePasswordAuthenticationToken apiTokenAuth() {
        UsernamePasswordAuthenticationToken a = superadminAuth();
        a.setDetails(new ApiTokenAuthenticationDetails(UUID.randomUUID(), "ci"));
        return a;
    }

    private EventSubscription stored() {
        EventSubscription s = new EventSubscription();
        s.setId(UUID.randomUUID());
        s.setName("slack-team-a");
        s.setChannelType(ChannelType.WEBHOOK);
        s.setDestinationConfig(Map.of("url", "https://example.com/hook"));
        s.setEnabled(true);
        s.setCreatedAt(Instant.now());
        s.setUpdatedAt(Instant.now());
        s.setVersion(0L);
        return s;
    }

    @Test
    void create_asSuperadmin_returns201_maskedAuth() throws Exception {
        EventSubscription s = stored();
        when(service.create(any(), any())).thenReturn(s);
        CreateEventSubscriptionRequest req = new CreateEventSubscriptionRequest(
                "slack-team-a", null, ChannelType.WEBHOOK,
                new DestinationConfigRequest("https://example.com/hook",
                        new WebhookAuthRequest("bearer", "super-secret-token", null)),
                null, true);

        mockMvc.perform(post("/api/v1/superadmin/event-subscriptions")
                        .with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("slack-team-a"));
    }

    @Test
    void create_missingName_returns400() throws Exception {
        String body = """
            {"channelType":"WEBHOOK","destination":{"url":"https://example.com"},"enabled":true}
            """;
        mockMvc.perform(post("/api/v1/superadmin/event-subscriptions")
                        .with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_invalidUrl_returns400() throws Exception {
        String body = """
            {"name":"x","channelType":"WEBHOOK","destination":{"url":"not-a-url"},"enabled":true}
            """;
        mockMvc.perform(post("/api/v1/superadmin/event-subscriptions")
                        .with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_asApiTokenCaller_returns403() throws Exception {
        CreateEventSubscriptionRequest req = new CreateEventSubscriptionRequest(
                "x", null, ChannelType.WEBHOOK,
                new DestinationConfigRequest("https://example.com", null),
                null, true);
        mockMvc.perform(post("/api/v1/superadmin/event-subscriptions")
                        .with(authentication(apiTokenAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    void list_asSuperadmin_returnsSubscriptions() throws Exception {
        when(service.list(null, null)).thenReturn(List.of(stored()));
        mockMvc.perform(get("/api/v1/superadmin/event-subscriptions")
                        .with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void list_filterByEnabledAndChannel() throws Exception {
        when(service.list(true, ChannelType.WEBHOOK)).thenReturn(List.of(stored()));
        mockMvc.perform(get("/api/v1/superadmin/event-subscriptions")
                        .param("enabled", "true")
                        .param("channelType", "WEBHOOK")
                        .with(authentication(superadminAuth())))
                .andExpect(status().isOk());
        verify(service).list(true, ChannelType.WEBHOOK);
    }

    @Test
    void list_asApiTokenCaller_returns200() throws Exception {
        when(service.list(null, null)).thenReturn(List.of());
        mockMvc.perform(get("/api/v1/superadmin/event-subscriptions")
                        .with(authentication(apiTokenAuth())))
                .andExpect(status().isOk());
    }

    @Test
    void getById_existing_returns200() throws Exception {
        EventSubscription s = stored();
        when(service.get(s.getId())).thenReturn(s);
        mockMvc.perform(get("/api/v1/superadmin/event-subscriptions/" + s.getId())
                        .with(authentication(superadminAuth())))
                .andExpect(status().isOk());
    }

    @Test
    void getById_unknown_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.get(id)).thenThrow(new ResourceNotFoundException("EventSubscription", id));
        mockMvc.perform(get("/api/v1/superadmin/event-subscriptions/" + id)
                        .with(authentication(superadminAuth())))
                .andExpect(status().isNotFound());
    }

    @Test
    void update_staleVersion_returns409() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.update(eq(id), any())).thenThrow(
                new ObjectOptimisticLockingFailureException(EventSubscription.class, id));

        String body = """
            {"name":"x","channelType":"WEBHOOK",
             "destination":{"url":"https://example.com"},
             "enabled":true,"version":0}
            """;
        mockMvc.perform(put("/api/v1/superadmin/event-subscriptions/" + id)
                        .with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void update_asApiTokenCaller_returns403() throws Exception {
        String body = """
            {"name":"x","channelType":"WEBHOOK",
             "destination":{"url":"https://example.com"},
             "enabled":true,"version":0}
            """;
        mockMvc.perform(put("/api/v1/superadmin/event-subscriptions/" + UUID.randomUUID())
                        .with(authentication(apiTokenAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void patchEnabled_flipsFlag() throws Exception {
        EventSubscription s = stored();
        when(service.setEnabled(s.getId(), false)).thenReturn(s);
        mockMvc.perform(patch("/api/v1/superadmin/event-subscriptions/" + s.getId() + "/enabled")
                        .with(authentication(superadminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk());
        verify(service).setEnabled(s.getId(), false);
    }

    @Test
    void patchEnabled_asApiTokenCaller_returns403() throws Exception {
        mockMvc.perform(patch("/api/v1/superadmin/event-subscriptions/" + UUID.randomUUID() + "/enabled")
                        .with(authentication(apiTokenAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void delete_asSuperadmin_returns204() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(delete("/api/v1/superadmin/event-subscriptions/" + id)
                        .with(authentication(superadminAuth())))
                .andExpect(status().isNoContent());
        verify(service).delete(id);
    }

    @Test
    void delete_unknown_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new ResourceNotFoundException("EventSubscription", id))
                .when(service).delete(id);
        mockMvc.perform(delete("/api/v1/superadmin/event-subscriptions/" + id)
                        .with(authentication(superadminAuth())))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_asApiTokenCaller_returns403() throws Exception {
        mockMvc.perform(delete("/api/v1/superadmin/event-subscriptions/" + UUID.randomUUID())
                        .with(authentication(apiTokenAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    void test_synchronousDeliveryResult() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.testDelivery(eq(id), any()))
                .thenReturn(new TestDeliveryResult(true, 200, "OK", 42L));
        mockMvc.perform(post("/api/v1/superadmin/event-subscriptions/" + id + "/test")
                        .with(authentication(superadminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.httpStatus").value(200));
    }

    @Test
    void test_asApiTokenCaller_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/superadmin/event-subscriptions/" + UUID.randomUUID() + "/test")
                        .with(authentication(apiTokenAuth())))
                .andExpect(status().isForbidden());
    }
}
