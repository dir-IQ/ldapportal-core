// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.replication;

import com.ldapportal.entity.enums.ReconciliationFindingType;

import java.util.List;
import java.util.UUID;

/**
 * Body for the findings apply / dismiss endpoints. Apply accepts either an
 * explicit {@code findingIds} list or {@code applyAll} (optionally narrowed to
 * one {@code type}); dismiss uses {@code findingIds}.
 */
public record FindingActionRequest(
        List<UUID> findingIds,
        boolean applyAll,
        ReconciliationFindingType type) {}
