// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication.reconcile;

import com.ldapportal.entity.enums.ReconciliationFindingType;
import com.ldapportal.entity.enums.ReplicationOperationType;

import java.util.Map;

/**
 * One discrepancy found by {@link ReconciliationDiffer}. The
 * {@code payload} is already in the {@code replication_events} JSONB
 * shape ({@code ReplicationPayloadCodec}) so a corrective event can be
 * enqueued directly without re-deriving it.
 *
 * @param type      the discrepancy class
 * @param operation the corrective LDAP operation (ADD / MODIFY / DELETE)
 * @param sourceDn  source DN (null for EXTRA_IN_TARGET — no source entry)
 * @param targetDn  target DN the correction applies to
 * @param payload   corrective-event payload (empty for DELETE)
 */
public record ReconciliationFinding(
        ReconciliationFindingType type,
        ReplicationOperationType operation,
        String sourceDn,
        String targetDn,
        Map<String, Object> payload) {}
