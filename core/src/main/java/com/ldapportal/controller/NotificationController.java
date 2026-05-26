// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.controller;

import com.ldapportal.auth.AuthPrincipal;
import com.ldapportal.dto.notification.NotificationDto;
import com.ldapportal.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public Page<NotificationDto> list(
            @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return notificationService.getAll(principal.id(), page, size);
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount(@AuthenticationPrincipal AuthPrincipal principal) {
        return Map.of("count", notificationService.getUnreadCount(principal.id()));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> markRead(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthPrincipal principal) {
        notificationService.markRead(id, principal.id());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/mark-all-read")
    public ResponseEntity<Void> markAllRead(@AuthenticationPrincipal AuthPrincipal principal) {
        notificationService.markAllRead(principal.id());
        return ResponseEntity.noContent().build();
    }
}
