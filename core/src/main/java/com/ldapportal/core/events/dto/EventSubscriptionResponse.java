// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.events.dto;

import com.ldapportal.core.events.entity.EventSubscription;
import com.ldapportal.core.events.enums.ChannelType;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record EventSubscriptionResponse(
    UUID            id,
    String          name,
    String          description,
    ChannelType     channelType,
    DestinationConfigResponse destination,
    List<String>    eventTypeFilter,
    boolean         enabled,
    UUID            createdById,
    String          createdByUsername,
    Instant         createdAt,
    Instant         updatedAt,
    Long            version
) {
    public static EventSubscriptionResponse from(EventSubscription s) {
        return new EventSubscriptionResponse(
                s.getId(),
                s.getName(),
                s.getDescription(),
                s.getChannelType(),
                destinationResponse(s.getDestinationConfig()),
                s.getEventTypeFilter(),
                s.isEnabled(),
                s.getCreatedBy() != null ? s.getCreatedBy().getId() : null,
                s.getCreatedBy() != null ? s.getCreatedBy().getUsername() : null,
                s.getCreatedAt(),
                s.getUpdatedAt(),
                s.getVersion()
        );
    }

    private static DestinationConfigResponse destinationResponse(Map<String, Object> cfg) {
        if (cfg == null) return null;
        Object url = cfg.get("url");
        Object auth = cfg.get("auth");
        WebhookAuthResponse authResp = null;
        if (auth instanceof Map<?, ?> authMap) {
            Object type = authMap.get("type");
            if ("bearer".equals(type)) {
                authResp = new WebhookAuthResponse("bearer",
                        maskFromCiphertext((String) authMap.get("tokenEnc")), null);
            } else if ("hmac".equals(type)) {
                authResp = new WebhookAuthResponse("hmac",
                        null, maskFromCiphertext((String) authMap.get("secretEnc")));
            }
        }
        return new DestinationConfigResponse(
                url instanceof String s ? s : null, authResp);
    }

    /** Derive a preview from the ciphertext length — safe to leak, gives the
     *  operator something to correlate against the plaintext they saved.
     *  Ciphertext is deterministic-shaped; length-based preview doesn't leak
     *  the secret. */
    private static String maskFromCiphertext(String ciphertext) {
        if (ciphertext == null || ciphertext.length() < 6) return "***";
        return ciphertext.substring(0, 3) + "…" + ciphertext.substring(ciphertext.length() - 3);
    }
}
