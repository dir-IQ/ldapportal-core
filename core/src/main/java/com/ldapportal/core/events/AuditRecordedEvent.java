// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.core.events;

import com.ldapportal.entity.AuditEvent;

/**
 * Fired once per {@code AuditService.record*} call, inside the source
 * transaction's {@code BEFORE_COMMIT} phase. The {@link OutboundEventBridge}
 * is the sole consumer; other code should not listen for this event.
 */
public record AuditRecordedEvent(AuditEvent auditEvent) {}
