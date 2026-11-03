// SPDX-License-Identifier: Apache-2.0
package com.ldapportal.dto.audit;

import com.ldapportal.entity.enums.AuditAction;
import lombok.Builder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Named filter criteria for an audit-log query. Every field is optional
 * ({@code null} = no filter on that dimension).
 *
 * <p>Callers build only the filters they need
 * ({@code AuditQueryCriteria.builder().directoryId(id).from(t).build()})
 * instead of threading a long positional argument list of {@code null}s
 * through {@link com.ldapportal.service.AuditQueryService}. Adding a new
 * filter is a field here plus one unpacking line in the service — call
 * sites that don't use the new filter are untouched, so a widened query
 * can no longer silently mis-bind an existing call site.
 *
 * <p>{@code targetDn} matches a DN exactly (an entry's history);
 * {@code targetDnContains} is a case-insensitive substring of it (the audit
 * log's "Target DN contains" search, e.g. an RDN value).
 *
 * <p>{@code directoryId} is ignored by
 * {@link com.ldapportal.service.AuditQueryService#queryForDirectories},
 * which scopes results to an explicit set of directories instead.
 */
@Builder(toBuilder = true)
public record AuditQueryCriteria(
        UUID directoryId,
        UUID actorId,
        List<AuditAction> actions,
        String targetDn,
        String targetDnContains,
        String source,
        UUID correlationId,
        OffsetDateTime from,
        OffsetDateTime to) {

    /** No filters — every audit row, newest first. */
    public static final AuditQueryCriteria EMPTY = AuditQueryCriteria.builder().build();
}
