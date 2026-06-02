// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.ldap.replication.reconcile;

import com.ldapportal.entity.enums.ReconciliationFindingType;
import com.ldapportal.entity.enums.ReplicationOperationType;

import java.util.Map;

/**
 * A discrepancy produced by the diff, before persistence. The {@code payload}
 * is already in the {@code replication_events} JSONB shape
 * ({@code ReplicationPayloadCodec}) so a corrective event can be enqueued
 * directly. Persisted (R-P2) as a {@code ReconciliationFinding} row.
 *
 * @param type      the discrepancy class
 * @param operation the corrective LDAP operation (ADD / MODIFY / DELETE)
 * @param sourceDn  source DN (null for EXTRA_IN_TARGET — no source entry)
 * @param targetDn  target DN the correction applies to
 * @param payload   corrective-event payload (UI keys before/currentTarget may be present)
 */
public record FindingCandidate(
        ReconciliationFindingType type,
        ReplicationOperationType operation,
        String sourceDn,
        String targetDn,
        Map<String, Object> payload) {}
