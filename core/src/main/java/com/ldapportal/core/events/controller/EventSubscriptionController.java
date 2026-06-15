// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.events.controller;

import com.ldapportal.auth.AuthContextHelper;
import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.core.events.dto.CreateEventSubscriptionRequest;
import com.ldapportal.core.events.dto.EventSubscriptionResponse;
import com.ldapportal.core.events.dto.TestDeliveryResult;
import com.ldapportal.core.events.dto.UpdateEventSubscriptionRequest;
import com.ldapportal.core.events.entity.EventSubscription;
import com.ldapportal.core.events.enums.ChannelType;
import com.ldapportal.core.events.service.EventSubscriptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/superadmin/event-subscriptions")
@PreAuthorize("hasRole('SUPERADMIN')")
@RequiredArgsConstructor
public class EventSubscriptionController {

    private final EventSubscriptionService service;

    @GetMapping
    public List<EventSubscriptionResponse> list(
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) ChannelType channelType) {
        return service.list(enabled, channelType).stream()
                .map(EventSubscriptionResponse::from).toList();
    }

    @GetMapping("/{id}")
    public EventSubscriptionResponse get(@PathVariable UUID id) {
        return EventSubscriptionResponse.from(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EventSubscriptionResponse create(
            @Valid @RequestBody CreateEventSubscriptionRequest req,
            @AuthenticationPrincipal AuthPrincipal principal) {
        rejectApiTokenCaller();
        EventSubscription saved = service.create(req, principal);
        return EventSubscriptionResponse.from(saved);
    }

    @PutMapping("/{id}")
    public EventSubscriptionResponse update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateEventSubscriptionRequest req) {
        rejectApiTokenCaller();
        return EventSubscriptionResponse.from(service.update(id, req));
    }

    @PatchMapping("/{id}/enabled")
    public EventSubscriptionResponse setEnabled(
            @PathVariable UUID id,
            @RequestBody Map<String, Boolean> body) {
        rejectApiTokenCaller();
        Boolean enabled = body.get("enabled");
        if (enabled == null) {
            throw new IllegalArgumentException("request body must contain 'enabled'");
        }
        return EventSubscriptionResponse.from(service.setEnabled(id, enabled));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        rejectApiTokenCaller();
        service.delete(id);
    }

    @PostMapping("/{id}/test")
    public TestDeliveryResult testDelivery(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthPrincipal principal) {
        rejectApiTokenCaller();
        return service.testDelivery(id, principal);
    }

    /**
     * Policy mirror of {@code ApiTokenController.rejectApiTokenCaller}: a
     * request authenticated via an API token cannot mutate the subscription
     * surface. Compromised token → new exfiltration-subscription is a real
     * escalation path we refuse to enable.
     */
    private static void rejectApiTokenCaller() {
        if (AuthContextHelper.currentApiToken().isPresent()) {
            throw new AccessDeniedException(
                    "API tokens cannot create, modify, test, or delete event subscriptions");
        }
    }
}
